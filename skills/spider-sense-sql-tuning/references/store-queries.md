# Finding candidates in Spider Sense's own store

`findings` and `queries` answer the usual questions; these `sql` statements answer the ones they have no column for.
The schema is [Storage](https://spider-sense.benelog.net/storage.html#schema) in the manual; what matters here: `span` has one row per span with `start_ms` (epoch milliseconds), `duration_ns`, `service`, `trace_id`, `parent_span_id`, `entry` (true for a request's entry span), `endpoint`, `query_id`, `db_statement`, `db_table`, `db_operation`, `error`, and `attributes` (the span's attributes as one JSON object).
Every answer is capped at 200 rows unless `--limit=<n>` says otherwise, and a truncated answer says so in its heading.

Every statement below takes a window; `$FROM` and `$TO` are epoch milliseconds, and `date +%s%3N` gives now.

```bash
TO=$(date +%s%3N); FROM=$((TO - 15*60*1000))
```

## Which statements ran most often per request

```sql
SELECT db_statement AS statement, service,
       COUNT(*) AS calls, COUNT(DISTINCT trace_id) AS traces,
       ROUND(COUNT(*) * 1.0 / COUNT(DISTINCT trace_id), 1) AS calls_per_trace
FROM span
WHERE start_ms BETWEEN $FROM AND $TO AND query_id IS NOT NULL
GROUP BY db_statement, service
ORDER BY calls_per_trace DESC
LIMIT 20
```

A `calls_per_trace` well above 1 is a loop, whether or not it reached the finding's threshold of 5.

## The slowest single executions of one statement, with their traces

```sql
SELECT trace_id, start_ms, duration_ns / 1000000.0 AS ms, endpoint
FROM span
WHERE start_ms BETWEEN $FROM AND $TO AND db_statement LIKE 'select%from items%'
ORDER BY duration_ns DESC
LIMIT 10
```

`trace <trace_id>` then shows the execution in its request; the same statement fast in one trace and slow in another is a plan that depends on the parameter.

## How much of an endpoint's time is the database

```sql
SELECT e.endpoint,
       COUNT(DISTINCT e.trace_id) AS requests,
       ROUND(AVG(e.duration_ns) / 1000000.0, 1) AS request_ms,
       ROUND(SUM(d.duration_ns) / COUNT(DISTINCT e.trace_id) / 1000000.0, 1) AS db_ms_per_request,
       ROUND(COUNT(d.span_id) * 1.0 / COUNT(DISTINCT e.trace_id), 1) AS db_calls_per_request
FROM span e
JOIN span d ON d.trace_id = e.trace_id AND d.query_id IS NOT NULL
WHERE e.start_ms BETWEEN $FROM AND $TO AND e.entry
GROUP BY e.endpoint
ORDER BY db_ms_per_request DESC
LIMIT 20
```

An endpoint whose `db_ms_per_request` is most of `request_ms` is tuned in the database; one where it is a small share is tuned elsewhere, whatever the queries look like.

## Statements by table, to see what a new index would serve

```sql
SELECT db_table AS "table", db_operation AS op, db_statement AS statement,
       COUNT(*) AS calls, ROUND(SUM(duration_ns) / 1000000.0) AS total_ms
FROM span
WHERE start_ms BETWEEN $FROM AND $TO AND db_table = 'items'
GROUP BY db_table, db_operation, db_statement
ORDER BY total_ms DESC
```

Every statement that filters or sorts on the table is listed, so an index can be shaped for all of them at once, and the writes to the same table (`INSERT`, `UPDATE`, `DELETE` rows) say what the index will cost.

## The same statement before and after a change

```sql
SELECT CASE WHEN start_ms < $CHANGE THEN 'before' ELSE 'after' END AS side,
       COUNT(*) AS calls,
       ROUND(AVG(duration_ns) / 1000000.0, 1) AS avg_ms,
       ROUND(MAX(duration_ns) / 1000000.0, 1) AS max_ms
FROM span
WHERE start_ms BETWEEN $FROM AND $TO AND db_statement = 'select name from suppliers where id = ?'
GROUP BY side
```

`$CHANGE` is the mark's time, which `marks` prints; `compare` gives the same per query with p95 and a verdict, and is the answer to quote when it has the row.

## Where a query is issued from

```sql
SELECT db_statement AS statement, duration_ns / 1000000.0 AS ms, attributes
FROM span
WHERE start_ms BETWEEN $FROM AND $TO AND attributes LIKE '%"code.stacktrace"%'
ORDER BY duration_ns DESC
LIMIT 5
```

The Spider Sense extension records `code.stacktrace` on a database span slower than the slow-query threshold and on the fifth repeat of a statement in one trace, and the store keeps it inside the span's `attributes` JSON, so the frames are there for exactly the statements worth tuning; `--full` keeps the cell whole, and the finding already reduces the frames to the application's own.
