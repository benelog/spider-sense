---
name: spider-sense-sql-tuning
description: >-
  Turn what Spider Sense measured about a database query into a concrete change: the index to add (which columns, in which order,
  covering what), the rewrite, the fetch join or batch that removes an N+1, the projection or the page that stops loading too much,
  each with the numbers that justify it, the execution plan that confirms it, and the compare that proves it.
  Use this skill whenever a Spider Sense `slow-query` or `n-plus-one` finding is on the table, whenever the user asks which index to add,
  why a query is slow, how to fix an N+1 in JPA or in a JDBC loop, whether a LIKE can use an index, or what a query plan says;
  and whenever a change to SQL, a JPA repository, an entity mapping or a schema is to be judged by its effect on query time and count.
  It builds on the spider-sense skill, which runs the loop; this one decides what to change in the database and in the data access code.
license: Apache-2.0
metadata:
  version: "0.1.0"
  homepage: https://github.com/benelog/spider-sense
---

# Query tuning with Spider Sense

Spider Sense records every database statement the application ran, sanitised as the OpenTelemetry agent sanitises it (`where id = ?`), with its duration, the endpoint or job that ran it, how many times it ran inside one request, and, for a slow one, the application frame that issued it.
That is the evidence a tuning proposal is built on.
This skill takes the evidence to a change: an index, a rewrite, a mapping, a batch, a page.
It never guesses at what is slow; the numbers come from the tool, and the change is proven the same way it was found.

The loop itself, starting the application under the agent, marking, exercising, `findings`, `compare` and `check`, is the [spider-sense skill](../spider-sense/SKILL.md); read it first if it is not loaded.
`$SENSE` below is the jar, as that skill finds it.

## 1. Collect the evidence

```bash
java -jar "$SENSE" findings --since=start                # slow-query and n-plus-one, ranked, with the statement and the frame
java -jar "$SENSE" queries --since=start --limit=20      # every statement: calls, p50, p95, max, total, who calls it
java -jar "$SENSE" trace <traceId> --full                # one request as a tree, with the statement under each database span
```

Read from each what it alone carries:

| Answer | What to take from it |
|---|---|
| `findings` `slow-query` | the sanitised statement, `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (which endpoints pay), the `code` frame that issued it, three trace ids, and the schema block: the indexes each table already has, the predicate columns, and `unindexed` |
| `findings` `n-plus-one` | the repeated statement, `affected` of `requests`, `medianRepeats`, `maxRepeats`, `msPerRequest`, the frame, three affected trace ids, and the same schema block |
| `queries` | the whole table: a statement that is not a finding yet but has the largest `total`, or `calls` far above the request count |
| `trace <id> --full` | the order the statements ran in, the parameters' shape (the sanitised statement), which span holds the time, the gap between statements |
| `sql` | anything the others have no column for: [references/store-queries.md](references/store-queries.md) has the statements that find candidates |

Two readings decide what kind of problem it is:

- **p95 far above p50** is a query that is fast for most inputs and slow for some: a plan that degrades with the data, a `LIKE` that scans when the word is common, a range that covers the whole table for some callers.
- **calls far above requests** is a query run in a loop: an N+1, a lookup per row, a check per item.

Quote the numbers as the tool printed them, with the window they came from, and name the trace id that shows the statement in its request.
A statement that ran once, in one request, is not a tuning target however slow it was; say so and move on.

## 2. Name the shape

Match the statement, the schema and the code to one of these; the shape decides the change.

| Shape | How it shows | The change |
|---|---|---|
| Predicate on an unindexed column | `where col = ?` or `where col in (?, …)`, plan says table scan, time grows with the table | an index on the predicate columns |
| Leading wildcard | `where col like ?` with `'%word%'` in the code, full scan every time | no B-tree index can serve an infix match: a prefix match (`'word%'`) with an index, or a search index (PostgreSQL trigram or full text, H2 `FT_*`, Lucene), or accept it and bound it |
| Function on the column | `where lower(col) = ?`, `where date(col) = ?` | an expression index on `lower(col)`, or a normalised column kept by the application |
| Sort or limit on an unindexed column | `order by col limit ?`, the plan sorts the whole result | an index whose leading columns are the predicate's, then the order column, so the limit stops early |
| Aggregate over the whole table | `group by`, `count(*)`, `sum(...)` with no bounding predicate | a covering index over the grouped and summed columns, a bounding range the caller can supply, or a precomputed table the writer keeps |
| N+1 through a JPA association | one `select parent`, then `select child where parent_id = ?` per row; `medianRepeats` is the row count | a fetch join or `@EntityGraph` for that use, `@BatchSize` or `default_batch_fetch_size` for every use, or a DTO projection that joins ([references/jpa.md](references/jpa.md)) |
| N+1 in a loop | the same statement per element in application code, `code` frame inside a `for` | one statement with `in (?, …)` or a join, then a map in memory |
| Loading more than the endpoint uses | `select *` on wide rows, entities loaded to read two fields | a projection: named columns in SQL, an interface or record projection in Spring Data |
| Unbounded result | no `limit`, `calls` low but `maxMs` and row counts high | a page: `Pageable`, `limit`/`offset`, or a keyset predicate on an indexed column |
| Correlated subquery per row | `where exists (select … where x.id = outer.id)` or a subquery in the select list | a join or a lateral join |
| Missing foreign-key index | `delete`/`update` on the parent slow, joins from the child side scan | an index on the child's foreign key column |
| Lock wait | `select … for update` or an update whose time is the wait, not the work | shorten the transaction, order the locks, or take the lock later |
| The same statement twice in one request | identical statement and parameters twice in a trace, `maxRepeats` 2 | one call, kept in a local variable or a request-scoped cache |

When the shape is not in the table, say what the plan says and propose the smallest change that changes the plan.

## 3. Design the index

Start from the finding's schema block: the indexes the table already has are `schema.tables[].indexes`, and the columns to index are `schema.unindexed`, the predicate columns no index of their table leads with.
Only when the finding has no block (`schema` is `null`: the application ran in standalone mode or without the extension, no statement on that table has yet been slow or repeated five times in one trace, or the parse could not vouch for it) does the design ask the database for its indexes.
Ask Spider Sense first, for the tables it has seen — `java -jar "$SENSE" sql "SELECT table_name, indexes FROM db_table WHERE service = '<service>'"` — and the database's own catalog (`\d <table>`, `SHOW INDEX FROM <table>`, `INFORMATION_SCHEMA.INDEXES`) when that is empty too.

An index is a sorted copy of some columns; the design is which columns, in which order, and what else to carry.

1. **Equality columns first**, in any order among themselves: `where status = ? and customer_id = ?` wants `(status, customer_id)` or `(customer_id, status)`; put the more selective one first if the index will also serve queries with only that predicate.
2. **Then the range column**, one of them: `and created_at >= ?` goes after the equalities, and nothing after it is used for seeking.
3. **Then the order-by columns**, in the query's direction, when the query has no range column or the range is on the order column itself; that is what lets `limit` stop early.
4. **Covering columns** last, when the query selects a few columns and the table is wide: PostgreSQL `include (…)`, MySQL and H2 by appending them to the key. The plan then reads the index alone.
5. **Do not** index a column alone when it has a handful of values (`status`, a boolean): the index selects too much to be worth it unless the query wants the rare value, in which case a partial index (PostgreSQL `where status = 'NEW'`) is the right shape.
6. **Every index is a write cost**: one more structure to maintain per insert, update and delete of those columns. Read the indexes already on the table from the finding's `schema.tables[].indexes` before adding one, and prefer widening an existing one to adding a second with the same leading column.
7. **Foreign keys** on the child side almost always want an index; check that it exists before anything else.
8. **Expression indexes** for a function in the predicate (`lower(email)`), and the query has to use the same expression, character for character.

The statement to write:

```sql
-- PostgreSQL
create index concurrently idx_order_customer_created on orders (customer_id, created_at desc) include (total);
create index idx_customer_email_lower on customers (lower(email));
create index idx_order_open on orders (customer_id) where status = 'NEW';

-- MySQL / MariaDB
create index idx_order_customer_created on orders (customer_id, created_at);
create index idx_customer_email_lower on customers ((lower(email)));       -- MySQL 8.0.13+

-- H2 (the example applications)
create index idx_book_title on book (title);
create index idx_events_account on events (account_id, occurred_at);
```

Where it goes: a migration (Flyway `V<n>__…sql`, Liquibase changeset) when the project has them, `schema.sql` when that is how the schema is made, and `@Table(indexes = @Index(columnList = "…"))` only in a project whose schema is generated from the entities; say which one the project uses, and never put the DDL in two places.

## 4. Confirm with the plan

An index that the planner does not use changes nothing.
Before measuring, run the statement with the real parameter shape through the database's `EXPLAIN` and read the plan: [references/explain.md](references/explain.md) has the command and the words to look for in H2, PostgreSQL, MySQL and MariaDB.

The plan has to change from a scan to a seek on the new index (`Index Scan using idx_…`, `ref`/`range` with `key: idx_…`, `/* PUBLIC.IDX_… */` in H2); a plan that still scans means the index does not fit the predicate (column order, a function, a type mismatch, a leading wildcard) and the design goes back to step 3.
For an N+1 the plan is not the point; the count is: the trace after the change shows one statement where there were `medianRepeats`.

## 5. Prove it with Spider Sense

```bash
java -jar "$SENSE" mark before-index
# exercise exactly the same endpoints the evidence came from, the same number of times
# apply the change, restart the application
java -jar "$SENSE" mark after-index
# exercise the same way again
java -jar "$SENSE" compare --before=before-index --after=after-index
java -jar "$SENSE" check --since=after-index --max-slow-queries=0 --max-n-plus-one=0
```

`compare` has a row per query with `calls`, `callsPerRequest`, `p95Ms` and `totalMs` on each side; the row for the tuned statement is the result, and the endpoint's row says what the user gains.
Quote both, before and after, and the `check` verdict.
A change that moves the query's `p95` but not the endpoint's has found the wrong query, and the proposal says so rather than claiming a win.

## The proposal

Write it in this shape, so the reader can act on it and check every line:

```markdown
## <statement, one line>

**Finding**: slow-query, p95 325.7 ms over 5 calls, 1,283.6 ms total, called by GET /items ×5 (window 07:16–07:26). Trace 9683120c…
**Cause**: `where lower(name) like ?` with `'%hinge%'`: a full scan of 400,000 rows; no index can serve an infix match.
**Change**: search by prefix, `lower(name) like 'hinge%'`, and add `create index idx_items_name_lower on items (lower(name))`.
   File: examples/servlet-warehouse/src/main/java/warehouse/web/ItemListServlet.java:58; migration V3__items_name_index.sql.
**Plan**: before `TABLE SCAN`, after `/* PUBLIC.IDX_ITEMS_NAME_LOWER: LOWER(NAME) LIKE ? */` (EXPLAIN ANALYZE, H2).
**Result**: compare before-index → after-index: p95 325.7 → 2.1 ms, total 1,283.6 → 9.8 ms; GET /items p95 331.3 → 6.0 ms; check passed.
**Cost**: one more index on items (400,000 rows, 3 indexes in the finding's schema block, 4 now); inserts unaffected in the load generator's run.
```

Leave out a line only when it is truly not applicable, and say why.

## Rules

- No number that the tool did not print, no trace id that was not in its answer, and the window named every time.
- The plan is read from the database the application uses, not inferred; when the database is not reachable, say the proposal is unconfirmed.
- One change per proposal, proven on its own; two indexes at once cannot be told apart in `compare`.
- The example applications in this repository misbehave on purpose, and their READMEs say what is deliberate (`examples/*/README.md`); do not tune one of them unless the user asks for exactly that.
- A query that is slow because the table is small and cold, or ran once, is not proposed; say what was looked at and why nothing is recommended.
- An index is not "free": name its write cost and the table's existing indexes in every proposal.

## References

| Need | Where |
|---|---|
| `EXPLAIN` per database and what the plan lines mean | [references/explain.md](references/explain.md) |
| N+1 and over-fetching in JPA and Hibernate: fetch join, entity graph, batch size, projections, open-in-view | [references/jpa.md](references/jpa.md) |
| `sql` statements over Spider Sense's own store that find candidates and measure a change | [references/store-queries.md](references/store-queries.md) |
| The loop, the commands, the findings | [../spider-sense/SKILL.md](../spider-sense/SKILL.md) |
