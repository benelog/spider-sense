# Reading the plan

An index that the planner does not use changes nothing, so every proposal is checked against the database's own plan before it is measured.
Run the statement Spider Sense printed, with the sanitised `?` replaced by a value of the same shape the application sends (a common word for a search, an id that exists, the real date range), against the database the application uses.
The connection details are in the application's configuration; the example applications use H2 files under `~/db/spider-sense/`, opened with `AUTO_SERVER=TRUE`, so a second process may open them while the application runs.

## H2

```sql
explain analyze select * from items where lower(name) like '%hinge%' order by name limit 50;
```

| Line | Meaning |
|---|---|
| `/* PUBLIC.ITEMS.tableScan */` | every row is read: the predicate has no usable index |
| `/* PUBLIC.IDX_ITEMS_NAME: NAME LIKE 'hinge%' */` | a seek on that index; the condition after the colon is what the index answered |
| `/* PUBLIC.PRIMARY_KEY_2: ID = ?1 */` | a primary-key lookup, the best case |
| `/* scanCount: 400001 */` after `explain analyze` | rows actually visited; a tuned query visits about as many rows as it returns |
| `/* reads: … */` | pages read from disk |
| `ORDER BY` still present after an index on the order column | the index did not serve the sort; check the column order or the direction |

H2 uses an index for `like 'word%'`, never for `like '%word%'`, and an expression index (`create index … on items(lower(name))`) only when the query uses exactly `lower(name)`.
A session-level result cache (`QUERY_CACHE_SIZE`) can make the second run of an identical statement cost 0 ms; measure with different parameters, or with the cache off in the JDBC URL as `servlet-warehouse` does.

An H2 file can be opened from the shell with the jar the application already has:

```bash
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/*/*/h2-*.jar org.h2.tools.Shell \
     -url "jdbc:h2:~/db/spider-sense/warehouse;AUTO_SERVER=TRUE" -user sa -password ""
```

## PostgreSQL

```sql
explain (analyze, buffers) select * from orders where customer_id = 42 and status = 'NEW' order by created_at desc limit 20;
```

| Line | Meaning |
|---|---|
| `Seq Scan on orders` | every row is read |
| `Index Scan using idx_… on orders` | a seek on the index, then the table rows |
| `Index Only Scan using idx_…` | the index alone answered; the covering columns paid off (`Heap Fetches: 0` confirms it) |
| `Bitmap Heap Scan` + `Bitmap Index Scan` | the index found many rows and the table is visited in page order; fine for a large share, a sign of low selectivity |
| `Sort` above the scan | the sort was done in memory or on disk; an index in the query's order removes it and lets `Limit` stop early |
| `rows=… actual rows=…` | the planner's estimate against reality; a large gap means stale statistics (`analyze orders`) |
| `Buffers: shared hit=… read=…` | pages touched; the number to watch when timing is noisy |
| `Nested Loop` with the inner side a `Seq Scan` | a join without an index on the inner join column |

`create index concurrently` builds without locking writes.
A partial index (`where status = 'NEW'`) is used only when the query repeats the same condition literally.
`explain (analyze)` runs the statement, so wrap a write in a transaction and roll it back.

## MySQL and MariaDB

```sql
explain format=json select * from orders where customer_id = 42 order by created_at desc limit 20;
explain analyze select …;      -- MySQL 8.0.18+ and MariaDB 10.1+: actual times and rows
```

| Column or line | Meaning |
|---|---|
| `type: ALL` | a full table scan |
| `type: index` | a full index scan, still every entry |
| `type: range`, `ref`, `eq_ref`, `const` | a seek; `const` and `eq_ref` are single-row lookups |
| `key: idx_…` | the index used; `possible_keys` lists what was considered |
| `Extra: Using index` | covering; the table was not visited |
| `Extra: Using filesort` | a sort outside the index |
| `Extra: Using temporary` | a temporary table, usually a `group by` or `distinct` the index did not serve |
| `rows` | the estimate; `explain analyze` prints the actual |

MySQL uses a B-tree index for `like 'word%'` and not for `like '%word%'`; a functional index (`create index … on customers((lower(email)))`) needs 8.0.13, and MariaDB uses a generated column plus an index on it instead.

## What has to change

Before the change the plan reads the table; after it the plan seeks the new index, and the visited row count (`scanCount`, `actual rows`, `rows`) is close to the returned count.
When the plan does not change, the reasons are, in the order to check: the leading column of the index is not in the predicate, the predicate wraps the column in a function the index does not, the types differ (a `varchar` column compared with a number), the `like` starts with a wildcard, the table is small enough that a scan is cheaper, or statistics are stale.
