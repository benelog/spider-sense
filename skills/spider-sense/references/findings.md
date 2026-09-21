# Findings, and the fix each one wants

```
java -jar spider-sense.jar findings [--since=] [--until=] [--service=] [--limit=20] [--json] [--full]
```

A finding is one thing worth fixing, found by rules over the window.
Findings are ranked by severity (`high` before `medium` before `low`), then by impact within a kind, then by id, so the list is stable between two calls over the same data.
The list is bounded: 20 by default, 100 at most.

## The rules

| Kind | Rule | Severity | Impact |
|---|---|---|---|
| `error` | an error group with at least one occurrence in the window | `high` | count |
| `n-plus-one` | in one trace, the same query group runs 5 or more times under the same entry span; aggregated per (endpoint, query group) over the window | `high` when the repeats reach 20 or their summed time exceeds `slow.request.ms`, else `medium` | affected requests × median repeats |
| `n-plus-one-http` | in one trace, the same outbound HTTP call runs 5 or more times under the same entry span; aggregated per (endpoint, call) over the window | as `n-plus-one` | affected requests × median repeats |
| `slow-query` | a query group whose p95 exceeds `slow.query.ms` | `high` when p95 exceeds ten times the threshold, else `medium` | total time |
| `slow-endpoint` | an endpoint whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold, else `medium` | total time |
| `slow-job` | a job (a root `INTERNAL` span: a scheduled method, an `@Async` call, a batch step), grouped by (service, span name), whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold, else `medium` | total time |
| `slow-external` | an outbound HTTP call: `CLIENT` spans of category `http`, grouped by (service, target, span name), whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold, else `medium` | total time |
| `log-error` | log records of severity `ERROR` or above, grouped by (service, logger, normalised message), counting only the **uncovered** ones: without a trace id, or with a trace id whose trace has no error span | `high` | uncovered count |
| `pool-exhausted` | a JDBC pool with a point in the window where pending requests are above zero, or used equals max | `high` | pending, then used |
| `gc-pause` | one collector (`jvm.gc.duration` per name and action) with a point whose longest single collection is at least `slow.request.ms`, or whose collections take at least 10% of the export interval | `high` for a single collection over the threshold, else `medium` | the longest collection |
| `heap-pressure` | heap `jvm.memory.used` at 90% or more of the heap `jvm.memory.limit` at any point in the window | `high` | the highest ratio |
| `thread-growth` | `jvm.thread.count` at the last point at least 50 above the first, or at least twice it when the first is 20 or more | `medium` | last minus first |

The thresholds are the server's: `slow.request.ms` is 500 by default and `slow.query.ms` is 100.
`status` prints the ones in force.

## What every finding carries

| Field | How to read it |
|---|---|
| `id` | kind plus 12 hex characters over (kind, service, subject); stable across windows, so the same problem keeps its id between runs |
| `title` | one line, the claim |
| `why` | the numbers that justify it, in a sentence: quote this rather than restating it |
| `subject` | the `endpointId`, `queryId`, `errorId`, `pool`, `job`, `target`, `logger` or `jvm` to pass to `endpoints`, `queries`, `errors`, `logs` or the API |
| `numbers` | kind-specific, listed below |
| `statement` | the statement as the OpenTelemetry agent sanitised it, literals already `?`; cut at 200 characters unless `--full` |
| `code` | application frames, most specific first, at most 5; empty when none is known |
| `traces` | at most 3 trace ids: the evidence, and what `trace <id>` opens |
| `schema` | `slow-query` and `n-plus-one` only: the statement's tables with the indexes they carry, the columns it filters on, and the ones no index serves; `null` when there is no catalog to read |

`traces` are the three slowest traces for `slow-*`, the three newest for `error` and `log-error`, the three most recent affected for `n-plus-one` and `n-plus-one-http`, and none for `pool-exhausted` and the three JVM kinds.

In the text output the ranked table comes first and these fields follow as one numbered block per row, in that order and mostly without labels:
`<n>. <id> — <why>`, then the `numbers` on one line as `name value, name value`, then `hot span: <name> · <selfMs> self · <share>` when the kind has one, then the `hot spans:` lines and the `breakdown:` line when it has those, then the `statement` when there is one, then the schema lines when the finding has that block, then the `code` frames one per line, then `traces: <id> <id>`.

**About `code`.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes from the `exception.stacktrace` of an error, the `code.function` / `code.namespace` attributes that a few instrumentations set, and the `code.stacktrace` that Spider Sense's own agent extension captures.
The extension captures that stack on every database span slower than `slow.query.ms`, on the fifth repeat of a statement within one trace, on every non-database `CLIENT` span slower than `slow.request.ms`, and on the fifth repeat of an HTTP call within one trace, which is what gives `slow-query`, `n-plus-one`, `slow-external` and `n-plus-one-http` findings a line.
It is therefore reliable for `error`, `slow-query`, `n-plus-one`, `n-plus-one-http` and `slow-external` findings, often present for `log-error`, and often empty for the others; when it is empty, open a trace from `traces` and read the tree, which names the endpoint and the statement even when it cannot name the line.
A stack trace is reduced to its application frames by dropping known framework prefixes (`java.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.apache.`, `com.zaxxer.`, `org.h2.`, `io.opentelemetry.` and others).
When that heuristic guesses wrong, `-Dspidersense.app.packages=com.acme,org.acme` replaces it with an allowlist.

**About `schema`.** A `slow-query` or `n-plus-one` finding of a service that ran under the agent says which of the columns the statement filters on carry no index, read from the database rather than guessed from the statement.
The extension reads the index catalog of the tables a slow statement touches through JDBC metadata on the application's own connection, and the store keeps it per service and table.

```json
"schema": {
  "tables": [ { "table": "ITEMS", "schema": "PUBLIC",
                "indexes": [ { "name": "PRIMARY_KEY_8", "unique": true, "columns": [ "ID" ] },
                             { "name": "IDX_ITEMS_SUPPLIER", "unique": false, "columns": [ "SUPPLIER_ID", "NAME" ] } ] } ],
  "predicates": [ "items.name", "items.category" ],
  "unindexed":  [ "items.name", "items.category" ]
}
```

`tables` are the tables the statement names, in the database's own spelling (`ITEMS` on H2, `items` on PostgreSQL), each with its indexes in key order; `indexes` is empty for a table that has none.
`predicates` are the columns a `where` or `on` clause or an `order by` list refers to, as `table.column`.
`unindexed` is the subset of them that no index of their table has as its first column, which is the one an index can seek on, so it is the list of columns to consider indexing.
The block is `null` rather than wrong whenever the parse cannot vouch for it: the application ran in standalone mode or without the extension, no statement on that table has been slow yet, the scanner did not find the statement's tables, or a column cannot be attributed to one table.
It says nothing about selectivity, wildcards or the plan; `EXPLAIN` and the `spider-sense-sql-tuning` skill answer those.

In the text output it is one line per table, then one line for the columns:

```
   indexes ITEMS: PRIMARY_KEY_8 (ID) unique, IDX_ITEMS_SUPPLIER (SUPPLIER_ID, NAME)
   indexes MOVEMENTS: none
   predicates: items.name, items.category; unindexed: items.name, items.category
```

`predicates: none` stands alone when the statement has no predicate, `unindexed: none` when every predicate is served, and a finding without the block prints nothing for it.
The `queries` table carries the same block as an `unindexed` column between `callers` and `statement`.

---

## `n-plus-one`

`numbers`: `requests` (entry spans of the endpoint in the window), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).

Read it as: this endpoint ran that statement `medianRepeats` times in one request, and that cost `msPerRequest`.
`affected` well below `requests` means only some inputs trigger it, which usually points at a branch or a lazily loaded collection that is only touched sometimes.
Open a trace from `traces`: the repeated database spans collapse into one `× n` line under the entry span, and the span above them is the code path that loops.
`code` is the call site of the fifth repeat, captured by the agent extension on the thread that ran it, so it names the line that issues the repeated statement; it is empty only when the application ran without the extension, and then the trace tree is what names the loop.
`schema` names the repeated statement's tables, the indexes they carry and the predicate columns none of them serves, so a repeat that is also a scan is visible as both.

### What to do

**JPA / Hibernate.** A lazy association touched inside a loop is the usual cause; the fix is to load it with the parent.

```java
// Before: each order's lines are loaded on first access, one SELECT per order
List<Order> orders = repository.findByCustomerId(customerId);
for (Order order : orders) {
    total = total.add(order.lines().stream()...);   // N queries
}

// After: one query with a fetch join
@Query("select distinct o from Order o join fetch o.lines where o.customer.id = :customerId")
List<Order> findByCustomerIdWithLines(@Param("customerId") long customerId);
```

An entity graph (`@EntityGraph(attributePaths = "lines")` on the repository method) does the same without JPQL.
When a fetch join would multiply rows across two collections, set batch loading instead and let Hibernate fetch the collections in `IN` batches:

```java
@BatchSize(size = 50)                       // on the collection, or the entity
private List<OrderLine> lines = new ArrayList<>();
```

Or globally: `spring.jpa.properties.hibernate.default_batch_fetch_size=100`.
That turns 42 queries into one or two, which `compare` shows as the query's `calls/req` column falling.

**spring-jdbc or plain JDBC.** The loop is explicit, so replace it with one query over the whole key set.

```java
// Before
for (long orderId : orderIds) {
    lines.addAll(jdbc.query("select * from order_line where order_id = ?", mapper, orderId));
}

// After: one statement, grouped in memory
var params = new MapSqlParameterSource("ids", orderIds);
Map<Long, List<OrderLine>> byOrder = namedJdbc
        .query("select * from order_line where order_id in (:ids)", params, mapper)
        .stream().collect(groupingBy(OrderLine::orderId));
```

A very large key set is chunked (a thousand at a time) rather than sent as one enormous `IN`.
Note that an `IN` list with a varying number of parameters produces a new statement text per size, so Spider Sense will group those calls separately.

After the fix, `compare --before=before --after=after` should show the query's `calls/req` down to 1 and the endpoint's verdict `better`; `check --max-queries-per-request=` locks it in.

---

## `n-plus-one-http`

`numbers`: the same five as `n-plus-one`, over the repeated call rather than the repeated statement.
`statement` is `null` and `subject.target` is the host that was called, so the callee's own findings are one `--service=` away.

This is the N+1 an ORM cannot cause: a loop that fetches one remote resource per item.
Two calls are the same call when they agree on their name, their target and their path once every run of digits is replaced by `?`, which is what the title names:

```
GET /orders/{id} calls GET localhost:8081/api/books/? 6 times per request
```

Open a trace from `traces`: the repeated `CLIENT` spans collapse into one `× n` line under the entry span, and the span above them is the loop.
`code` is the call site of the fifth repeat, so it names the line that issues the call.

### What to do

**Ask for the whole set once.** When the callee is yours, a batch endpoint is usually the smaller change:

```java
// Before: one call per item
for (OrderLine line : order.lines()) {
    books.add(client.get("/api/books/" + line.bookId(), Book.class));   // N calls
}

// After: one call for the set
List<Book> books = client.get("/api/books?ids=" + join(bookIds), BookList.class).books();
```

**Cache what does not change per request.** A per-request cache removes the repeats inside one request; a short-lived shared cache (Caffeine, `@Cacheable` with a TTL) removes them across requests.
Cache the resource, not the response, so that a change on the callee is visible within the TTL.

**Move the join to the callee.** When every item needs the same enrichment, let the callee return it with the list rather than answering one item at a time.

**When neither is possible**, make the calls concurrently — a thread pool, `CompletableFuture.allOf`, or a reactive `flatMap` with a bounded concurrency — which shortens the request without removing the load on the callee.
Set a timeout while you are there: a loop of calls with no timeout is how a slow dependency becomes an exhausted thread pool.

After the fix, `compare` should show the endpoint's verdict `better` and the `slow-external` group's `calls` down; `check --max-n-plus-one=0` fails on this kind as it does on `n-plus-one`.

---

## `slow-query`

`numbers`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (endpoint, service and calls, the nearest entry span up the parent chain).

A p95 far above p50 is a query that is fast for most inputs and slow for some, which usually means a plan that degrades with the data rather than a statement that is always wrong.
`callers` says which endpoints pay for it, and the `queries` table carries the same under its own `callers` column, as `GET /api/reports/revenue ×3`; the trace ids in `traces` give the individual calls.
`schema` names the statement's tables with the indexes they already carry, and `schema.unindexed` is the list of predicate columns no index serves.

### What to do

**A missing index** is the first thing to test, and `schema.unindexed` says which columns want one; a leading wildcard defeats one.

```sql
-- A full scan over 200,000 rows
select b.* from book b where b.title like '%graph%';

-- With an index on the column and a prefix match, the index is usable
create index idx_book_title on book(title);
select b.* from book b where b.title like 'graph%';
```

Full-text search that genuinely needs an infix match belongs in a search index, not in a `LIKE`.

**A rewrite** when the shape is the problem: a correlated subquery per row becomes a join, `select count(*)` over everything becomes a bounded count, an `order by` over an unindexed column becomes an indexed one.

**Not selecting what is not needed.** A JPA repository returning entities loads every column and puts each row in the persistence context; a projection does not.

```java
public interface OrderSummary {              // a Spring Data projection
    long getId();
    BigDecimal getTotal();
}

@Query("select o.id as id, o.total as total from Order o where o.createdAt >= :since")
List<OrderSummary> summaries(@Param("since") Instant since);
```

The spring-jdbc equivalent is naming the columns instead of `select *` and mapping only those.

**Paging** when the endpoint does not need the whole result: `Pageable` in JPA, `limit`/`offset` or a keyset predicate in SQL.

Re-run the same exercise and check the query's `p95` and `total` columns in `compare`, not only the endpoint's.

---

## `slow-endpoint`

`numbers`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1 in the JSON, the part of the endpoint's total time spent in database spans of the same trace and service; the text prints it as a percentage, `93.0%`), `hotSpan`, `hotSpans`, `breakdown`.

`hotSpan` is where the time went in the first evidence trace: the span with the largest self time (its duration minus the durations of its direct children), that time, and its share of the trace.
It reads as one line, `hot span: SELECT order_line · 312.4 ms self · 62.0%`, and it is the first clue when `dbShare` is low, because it names the span instead of leaving the trace to be read.
It is `null` only when the finding has no trace.

`hotSpans` and `breakdown` answer the same question over many traces rather than one: the 20 slowest traces of the endpoint in the window, of which the first three are `traces`.
Only the spans of the endpoint's own service count, so the wait on an outbound call lands in `http` rather than in the callee's queries.

```
   hot spans: SELECT order_line · 4,210.5 ms · 44.0% · ×126
              GET localhost:8081/api/books/? · 1,980.0 ms · 21.0% · ×42
   breakdown: db 44.0% · http 21.0% · internal 7.0% · self 28.0%
```

`hotSpans` is the three summaries with the largest summed self time, each with that time, its share of the sample and how many spans went into it; a summary has its digits replaced by `?`, so one call to 40 items is one row.
`breakdown` is four shares that sum to 1: `db`, `http`, `internal` and `self`, where `self` is the request's own time outside any child span.
Read `breakdown` before opening a trace — it says whether to look at the queries, at the calls, or at the code.

`dbShare` is the fork in the road.

- **High `dbShare`** (most of the time in the database): the endpoint is not the problem, its queries are. Look for an `n-plus-one` or `slow-query` finding with this endpoint among its `callers`, or run `queries --service=` over the same window, and fix it there.
- **Low `dbShare`**: the time is elsewhere, and `breakdown` says where. High `http` is a call out, which `hotSpans` names; high `self` is the endpoint's own code. Then open the slowest trace from `traces` and read the tree: a `CLIENT` span that dominates the duration is the answer, and so is a long stretch with no child spans at all.

A `CLIENT` span whose child `SERVER` span is nearly as long moves the question to the other service; the same loop applies there, with `--service=` set to it.
A `CLIENT` span much longer than the `SERVER` span it wraps is connection setup, queueing or serialisation, not the callee.
For the endpoint's own code, the usual suspects are work done per request that could be done once (compiling a pattern, building a client, reading a file), an unbounded collection built in memory, or a sleep.

```java
// Before: a new RestClient per request, so a new connection pool per request
public Book fetch(long id) {
    return RestClient.create().get().uri(url, id).retrieve().body(Book.class);
}

// After: one client, built once
private final RestClient client;   // constructed in the configuration, reused
```

An endpoint that fans out to several independent calls can run them together rather than in sequence; the trace tree shows sequential `CLIENT` spans laid end to end when it does not.

`check --endpoint="GET /orders/report" --max-p95-ms=` scopes the verdict to the one endpoint being worked on.

---

## `slow-job`

`numbers`: `runs`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `dbCallsPerRun`, `dbMsPerRun`, `dbShare` (0..1 in the JSON, the part of the job's total time spent in database spans of the same trace and service; the text prints it as a percentage), `hotSpan`, `hotSpans` and `breakdown` (the same three `slow-endpoint` carries, over the job's 20 slowest runs).

A job is a root `INTERNAL` span: a scheduled method, an `@Async` call, a batch step.
It is work the application did to itself, so it is never an entry span: it is in no request count, in no Apdex and in no `check` verdict, and this finding is the one place a slow scheduler tick or batch step is reported.
The group is `(service, span name)` and `subject.job` is that name, so `runs` are the runs of one job rather than the requests of an endpoint.

`dbShare` forks the same way it does for `slow-endpoint`.

- **High `dbShare`**: the job's queries are the problem, and a job is where an N+1 hides best, because nobody is waiting for it. Look for an `n-plus-one` or `slow-query` finding over the same window, and fix it there.
- **Low `dbShare`**: the time is the job's own code. Open the slowest trace from `traces`: a long stretch with no child spans is the loop to look at, and a `CLIENT` span that dominates is a call that should be batched or moved out of the tick.

The usual fix is one query for the whole batch instead of one query per row.

```java
// Before: one SELECT per order, every minute
@Scheduled(fixedDelay = 60_000)
public void expireOrders() {
    for (Long id : orders.findPendingIds()) {
        Order order = orders.findById(id).orElseThrow();   // N queries
        expire(order);
    }
}

// After: one query loads them all
@Scheduled(fixedDelay = 60_000)
public void expireOrders() {
    for (Order order : orders.findAllPending()) {          // one query
        expire(order);
    }
}
```

A job that has grown slower than its own interval overlaps with itself; `runs` and `p95Ms` over a window of a few minutes say whether it has.
`check` says nothing about a job, so verify a fix with `compare` over the same exercise, or with `findings` again over a window that starts at the restart (`--since=start`).

---

## `slow-external`

`numbers`: `calls`, `errors`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (endpoint, service and calls, the nearest entry span up the parent chain).

The group is `(service, target, span name)`, where the target is what the service map calls the dependency (`localhost:8081`, a queue, an RPC service), so one row is one thing this service calls rather than every outbound call it makes.
`callers` says which endpoints pay for it; `code` is the line that made the call, captured by the agent extension on the thread that ended the span, so it is reliable here.

**First, decide whose problem it is.** Open the slowest trace from `traces`.
When the callee is traced too, its `SERVER` span is in the same tree: a `SERVER` span nearly as long as the `CLIENT` span moves the question to that service, and the same loop applies there with `--service=` set to it.
A `CLIENT` span much longer than the `SERVER` span it wraps is connection setup, queueing or serialisation on this side, not the callee.

### What to do

**Stop making the call per request** when the answer barely changes: cache it, or fetch it once at startup.

**Make one call instead of N.** A call inside a loop is the N+1 of the network, and it is worse than the database's because the latency is a round trip.

```java
// Before: one request per id
for (long id : ids) {
    books.add(client.get().uri("/api/books/{id}", id).retrieve().body(Book.class));
}

// After: one request for the whole set
List<Book> books = client.get().uri("/api/books?ids={ids}", join(ids)).retrieve().body(BOOK_LIST);
```

**Run independent calls together** rather than end to end; the trace tree shows sequential `CLIENT` spans laid in a line when they are not.

**Set timeouts and reuse the client.** A `RestClient` or `HttpClient` built per request builds a connection pool per request, which is the `CLIENT` span that is much longer than its `SERVER` span; build it once.
A call with no timeout turns the callee's bad minute into this service's bad minute.

`compare` over the same exercise should show the endpoint's p95 fall with the call's; `check --max-p95-ms=` locks the endpoint in.

---

## `error`

`numbers`: `count`, `firstSeen`, `lastSeen`, `type`, `message` (normalised, digits replaced by `?`), `endpoints` (name and count).

The group is `(service, exception type or `error.type`, normalised message)`, so one row is one failure mode, not one occurrence.
`code` is at its best here: the application frames of the sample stack trace, innermost first, so the **top frame is where to look**.
`traces` are the three newest occurrences; `trace <id>` shows the exception span with its message and frames, and the trace's log lines underneath, which usually carry the context the message leaves out.
`logs --trace=<traceId>` on its own gives the full log for that request.

### What to do

Read the top application frame, then decide which of the three it is.

- **A bug**: the code cannot handle an input it will receive. Fix the code, and add the test that reproduces it.
- **An expected condition answered as a failure**: a business rule violated, an entity not found. It should still be visible, but it should answer the client properly rather than escape as a 500.
- **A dependency failing**: the trace will show the `CLIENT` span that failed first.

```java
// The condition is real, the 500 is not
@ExceptionHandler(OrderAlreadyShippedException.class)
ResponseEntity<Problem> handle(OrderAlreadyShippedException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.of(e.getMessage()));
}
```

A message that carries the identifier (`Order 42 is already shipped`) groups correctly, because Spider Sense normalises the digits away, and it is what makes the log line useful.
`check --max-errors=0` after the fix, over a window that starts at the restart (`--since=start`).

---

## `log-error`

`numbers`: `count` (the uncovered records), `firstSeen`, `lastSeen`, `logger`, `message` (normalised, digits replaced by `?`), `endpoints` (the entry span of each record's trace, `(no endpoint)` for a record without one).

This is what `catch (Exception e) { log.error(…, e); return fallback; }` leaves behind: no span error, no exception event, one line in the log, and a request that answered 200 with the wrong answer.
A record whose trace has an error span is already reported by an `error` finding and is not counted here, so a `log-error` is by construction the failure nothing else tells you about.
`code` comes from the record's `exception.stacktrace` when the logging bridge exported a throwable, and is empty when the log line carried only a message.
`logs --severity=ERROR --q=<logger>` lists the records themselves, and `logs --trace=<traceId>` gives the whole log of one of them.

### What to do

Read the top application frame, then decide which of the three it is.

- **A real failure being swallowed.** The fallback hides it from the client and from the error rate. Let it fail, or answer the client properly, so the failure is visible where it is decided rather than only in a log.
- **An expected condition logged as an error.** A validation failure, a cache miss, a retry that then succeeded. Log it at `WARN` or `INFO`; `ERROR` should mean "somebody has to look".
- **A dependency failing.** The trace, when there is one, shows the `CLIENT` span that failed first; the fix belongs there.

```java
// Before: the caller cannot tell a price of zero from a gateway that is down
try {
    return gateway.charge(order);
} catch (GatewayException e) {
    log.error("Payment gateway timed out for order {}", order.id(), e);
    return Receipt.EMPTY;
}

// After: the failure reaches the client, and the error rate, as a failure
try {
    return gateway.charge(order);
} catch (GatewayException e) {
    throw new PaymentUnavailableException(order.id(), e);   // 503, one error group, one trace
}
```

When the fallback is the right behaviour, keep it and make it visible: record it as a counter or set the span's status, so the endpoint's numbers say how often the degraded path was taken.

`check --max-log-errors=0` over a window that starts at the restart (`--since=start`) locks the fix in.

---

## `pool-exhausted`

`numbers`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).
There are no `traces`: this comes from the pool's metrics, not from a span.

`pendingMax` above zero means threads waited for a connection, and `usedMax == max` means the pool was full.
A pool at its limit is a symptom, and there are only two causes worth considering.

**Connections not returned.** A connection taken outside a `try`-with-resources, a transaction left open, or a stream consumed after the transaction that produced it ended.

```java
// Before: a leak on any exception
Connection connection = dataSource.getConnection();
doWork(connection);
connection.close();

// After
try (Connection connection = dataSource.getConnection()) {
    doWork(connection);
}
```

In Spring, work that opens a transaction and then makes a slow HTTP call holds a connection for the length of that call; move the call outside the transactional method.
A lazily loaded association read inside a controller rather than a service does the same thing through `OpenEntityManagerInView`.

**A pool too small for the concurrency.** Real, but check the first cause before raising the ceiling, because a leak will exhaust any size.

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.leak-detection-threshold=20000
```

`leak-detection-threshold` is what proves which of the two it is: it logs the stack that took a connection and did not give it back, and those log lines are in `logs`.
A pool at its limit also shows as endpoints whose p95 rises while their `dbMsPerRequest` does not: the wait happens before any database span starts, so it lands in the endpoint's time and not in its `dbShare`.

---

## `gc-pause`

`numbers`: `gc`, `action`, `worstMs` (the longest single collection), `shareMax` (0..1, the worst export interval's collection time over its length), `collections` (over the window), `at`.
There are no `traces` and no `code`: this comes from `jvm.gc.duration`, not from a span.

A collection over `slow.request.ms` stops every application thread for that long, so a request unlucky enough to be running looks slow for no reason of its own.
`shareMax` is the other half of the question: many short collections that add up to a tenth of the wall clock are a throughput problem even when no single one is long.
Read this finding before `slow-endpoint` findings of the same window, because a run that is collecting will produce slow endpoints that have nothing wrong with them.

### What to do

**Allocate less per request** before touching any flag; the collector is doing what it was asked to do.
The usual sources are a whole result set loaded to return a page of it, a string built by concatenation in a loop, and a response serialised into memory rather than streamed.

```java
// Before: every row, every column, to answer one page
List<Order> all = orders.findAll();
return all.subList(from, to);

// After: the database does the paging
return orders.findAll(PageRequest.of(page, size));
```

**Then the heap.** A young generation too small for the allocation rate collects constantly; `-Xmx` and `-Xms` set to the same value avoid the resizing pauses of a growing heap.
On a development machine the honest fix is often that the JVM was given 256 MiB and the workload wants more.

```bash
JAVA_OPTS="-Xms1g -Xmx1g"
```

`findings --since=start` after the change says whether the pauses are gone; a `heap-pressure` finding beside this one says the heap is the cause rather than the allocation rate.

---

## `heap-pressure`

`numbers`: `usedMax`, `limit` (both bytes), `ratioMax` (0..1), `at` (the worst point).
There are no `traces`: this comes from `jvm.memory.used` and `jvm.memory.limit`, summed over the heap pools, as the JVM page draws them.

A heap at 90% of its limit is a run that is about to spend its time collecting, and it usually arrives with a `gc-pause` finding.
The question is what is being held.

### What to do

**Something unbounded.** A cache with no maximum, a list of everything read in a loop, a `ThreadLocal` never cleared, a collection on a long-lived object that only grows.

```java
// Before: every key ever seen, for the life of the process
private final Map<String, Rates> cache = new HashMap<>();

// After: a bound and an expiry
private final Cache<String, Rates> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build();
```

**A whole result set in memory.** Streaming, paging or a projection turns the peak into a plateau; the `slow-query` section has the shapes.

**A heap genuinely too small** for the workload, which is real, and is the last thing to conclude rather than the first.

The JVM page's memory chart over the same window says which it is: a sawtooth that returns to the same floor is allocation, a floor that climbs is something being retained.

---

## `thread-growth`

`numbers`: `first`, `last`, `max`, `at` (the last point).
There are no `traces`: this comes from `jvm.thread.count`.

Threads that only go up are threads nobody is stopping, and each one costs a stack.
The count settling at a new plateau after a burst is a pool that grew to its maximum, which is fine; a count that climbs for as long as the window does is a leak.

### What to do

**Something created per request that should be created once.** An executor, an HTTP client, a scheduler, a connection pool.

```java
// Before: a pool per call, never shut down
public List<Book> fetchAll(List<Long> ids) {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    ...
}

// After: one pool, built once and closed with the application
private final ExecutorService pool;   // a bean, or a field with a @PreDestroy
```

**A pool with no bound**, which grows until the machine says no: give it a maximum and a queue.

**Threads that are parked rather than finished**, waiting on a call with no timeout; the `slow-external` section covers the timeout.

The JVM page's thread chart over the same window shows whether the count plateaus or climbs, and `findings --since=start` after the fix says whether it still climbs.
