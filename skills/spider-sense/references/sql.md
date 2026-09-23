# Read-only SQL over the store

```bash
java -jar spider-sense.jar sql "SELECT endpoint, COUNT(*) FROM span WHERE entry GROUP BY endpoint"
```

This is the escape hatch, not the front door.
`findings`, `compare` and `check` answer what Spider Sense knows to look for, and they answer it the same way every time; reach for `sql` when the question is one of yours and none of them has a column for it.

The statement runs as an H2 user that has `SELECT` and nothing else, so nothing here can change or delete anything, whatever it says.
Only a single `SELECT`, `WITH`, `TABLE`, `VALUES`, `EXPLAIN` or `SHOW` is accepted; a second statement after a `;` is refused, and so is anything that writes.
At most 200 rows come back unless `--limit=<n>` says otherwise, and the cap is 5000; the heading says `(truncated at <n>)` when it cut the answer off, and a truncated answer must never be quoted as if it were the whole one.
`--json` prints `{ "columns", "rows", "rowCount", "truncated", "elapsedMs" }` instead of the table, and `--full` keeps long cells whole instead of cutting them at 200 characters.

Over HTTP it is `POST /api/sql` with `{ "sql": "…", "limit": 200 }` and `?format=text` for the same table.
The column headers are H2's own labels, so an unquoted name comes back upper-cased (`SERVICE`, `SPANS`); alias a column when the heading matters.

## The schema, in the columns that matter

Every instant is **epoch milliseconds** in a `BIGINT`, and every duration is **nanoseconds** (`duration_ns`); there is no `TIMESTAMP` column anywhere, so arithmetic on times is plain integer arithmetic.
`docs/storage.md` in the Spider Sense repository has the tables in full; this is what a query normally needs.

### `span` — one row per span

| Column | What it holds |
|---|---|
| `trace_id`, `span_id`, `parent_span_id` | 32 and 16 hex characters; `parent_span_id` is null on a root |
| `service`, `name`, `kind` | the service name, the span name, `SERVER`/`CLIENT`/`INTERNAL`/`PRODUCER`/`CONSUMER` |
| `start_ms`, `start_ns`, `duration_ns` | when it started (epoch ms), its start and length in nanoseconds |
| `entry` | true for the span that *is* a request: a `SERVER`/`CONSUMER` span, or a root |
| `error` | true when the span failed |
| `slow` | true for an entry span over `slow.request.ms` or a database span over `slow.query.ms` |
| `category` | `http`, `db`, `messaging`, `rpc` or `internal` |
| `endpoint`, `endpoint_id` | on entry spans: `GET /orders/{id}` and its 12-character id |
| `http_method`, `http_route`, `http_status` | the HTTP triple, where there is one |
| `db_statement`, `db_operation`, `db_table`, `db_system`, `query_id` | on database spans; `query_id` is the 12-character id of the statement's group |
| `error_type`, `error_message`, `error_id` | the exception, and the 12-character id of its group |
| `status`, `status_message`, `scope` | `UNSET`/`OK`/`ERROR`, the status message, the instrumentation scope |
| `attributes`, `events` | the rest, as JSON text |

The three flags are the shortcuts worth remembering: **`entry` counts requests, `category = 'db'` counts statements, `error` counts failures.**

### The other tables

| Table | One row per | Columns worth knowing |
|---|---|---|
| `trace` | trace | `trace_id`, `start_ms`, `end_ms`, `duration_ns`, `root_service`, `root_name`, `span_count`, `db_count`, `error_count`, `http_status`, `slow`, `error`, `services` (JSON array) |
| `log` | log record | `at_ms`, `service`, `severity`, `severity_number` (9 INFO, 13 WARN, 17 ERROR), `body`, `logger`, `trace_id`, `span_id` |
| `service` | service | `name`, `language`, `pid`, `first_seen`, `last_seen`, `resource` (JSON) |
| `mark` | named moment | `at_ms`, `name`, `service`, `note`; `start` marks are written on every restart |
| `ack` | acknowledged or resolved finding | `finding_id`, `at_ms`, `note`, `resolved` (true for a resolution); never swept by the retention |
| `db_table` | table of a service the extension read the index catalog of | `service`, `schema_name`, `table_name`, `product`, `indexes` (JSON array of `{name, unique, columns}`), `seen_ms` |
| `metric_series` | series | `id`, `service`, `name`, `attributes` (JSON, keys sorted) |
| `metric_point` | point | `series_id`, `at_ms`, `value`, `count`, `sum`, `min`, `max`, `buckets` |
| `tingle` | live event | `at_ms`, `kind`, `service`, `title`, `detail`, `trace_id`, `duration_ms` |

### Bounding the window

There is no `--since` here; the window is a predicate you write.
Two idioms cover almost everything:

```sql
-- the last 15 minutes of data, whenever the data ends
WHERE s.start_ms >= (SELECT MAX(start_ms) FROM span) - 15 * 60 * 1000

-- since the mark you set before the change
WHERE s.start_ms >= (SELECT MAX(at_ms) FROM mark WHERE name = 'before')
```

`(SELECT MAX(at_ms) FROM mark WHERE name = 'start')` is the last restart, and an epoch millisecond typed out is always allowed.

## Worked queries

### Which endpoint issues the most distinct statements

The one findings cannot rank: an endpoint may run few statements often, or many statements once, and the second is a design question rather than an N+1.

```sql
SELECT e.endpoint,
       COUNT(DISTINCT d.query_id) AS statements,
       COUNT(d.id)                AS db_calls,
       COUNT(DISTINCT e.id)       AS requests
FROM span e
JOIN span d ON d.trace_id = e.trace_id AND d.service = e.service AND d.category = 'db'
WHERE e.entry
  AND e.start_ms >= (SELECT MAX(start_ms) FROM span) - 15 * 60 * 1000
GROUP BY e.endpoint
ORDER BY statements DESC, db_calls DESC
```

### p95 per minute for one route

Findings give one p95 over the window; this says whether it was the whole window or one bad minute.

```sql
SELECT s.start_ms / 60000 * 60000 AS minute_ms,
       COUNT(*) AS requests,
       ROUND(PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY s.duration_ns) / 1000000.0, 1) AS p95_ms,
       ROUND(MAX(s.duration_ns) / 1000000.0, 1) AS max_ms
FROM span s
WHERE s.entry AND s.endpoint = 'GET /orders/{id}'
GROUP BY s.start_ms / 60000 * 60000
ORDER BY minute_ms
```

### The statements of one trace, in order

`trace <id>` collapses repeated siblings; this lists every one of them with its offset, which is what to read when the repeats are not identical.

```sql
SELECT s.start_ns - (SELECT MIN(start_ns) FROM span WHERE trace_id = s.trace_id) AS offset_ns,
       ROUND(s.duration_ns / 1000000.0, 1) AS ms,
       s.service, s.db_operation, s.db_table, s.db_statement
FROM span s
WHERE s.trace_id = '4bf92f3577b34da6a3ce929d0e0e4736' AND s.category = 'db'
ORDER BY s.start_ns
```

### Errors per endpoint per hour

Findings rank error groups over one window; this says whether a group is growing.

```sql
SELECT s.start_ms / 3600000 * 3600000 AS hour_ms,
       s.service, s.endpoint,
       COUNT(*) AS requests,
       SUM(CASE WHEN s.error THEN 1 ELSE 0 END) AS errors
FROM span s
WHERE s.entry
GROUP BY s.start_ms / 3600000 * 3600000, s.service, s.endpoint
HAVING SUM(CASE WHEN s.error THEN 1 ELSE 0 END) > 0
ORDER BY hour_ms DESC, errors DESC
```

### The pools' worst pending value per five minutes

The `pool-exhausted` finding names the single worst point; this is the shape of the pressure over time.

```sql
SELECT p.at_ms / 300000 * 300000 AS five_minutes_ms,
       ms.service, ms.attributes AS pool,
       MAX(p.value) AS pending_max
FROM metric_point p
JOIN metric_series ms ON ms.id = p.series_id
WHERE ms.name IN ('db.client.connections.pending_requests', 'db.client.connection.pending_requests')
GROUP BY p.at_ms / 300000 * 300000, ms.service, ms.attributes
ORDER BY five_minutes_ms DESC
```

### The requests that ran the most statements

The N+1 rule fires at 5 repeats of *one* statement; this finds a request that ran 40 different ones, which is not an N+1 and is still worth seeing.

```sql
SELECT e.endpoint, e.trace_id, COUNT(*) AS db_calls,
       ROUND(SUM(d.duration_ns) / 1000000.0, 1) AS db_ms
FROM span e
JOIN span d ON d.trace_id = e.trace_id AND d.category = 'db'
WHERE e.entry
GROUP BY e.endpoint, e.trace_id
HAVING COUNT(*) >= 10
ORDER BY db_calls DESC
```

### The indexes a service's tables carry

A finding's schema block answers this for one statement; this lists every table the extension has read the catalog of, which says what an index proposal is adding to.

```sql
SELECT table_name, indexes
FROM db_table
WHERE service = 'servlet-warehouse'
ORDER BY table_name
```

### What was logged inside the failed traces

```sql
SELECT l.at_ms, l.severity, l.service, l.logger, l.body, l.trace_id
FROM log l
JOIN trace t ON t.trace_id = l.trace_id
WHERE t.error AND l.severity_number >= 13
ORDER BY l.at_ms DESC
```

## Rules

- **Quote what it printed.** A cell is the value as the store holds it, with no thousands separator and nothing rounded on its way out; a nanosecond count is not a millisecond count.
- **Check `truncated`.** The heading says so, and a capped answer is a sample, not a total.
- **Bound the window.** Without a predicate on `start_ms` or `at_ms` the query reads the whole retention, which is up to 24 hours of everything.
- **Prefer the named commands when one fits.** `findings`, `endpoints`, `queries`, `errors`, `compare` and `check` are the same data with the thresholds, the ranking and the rendering already applied, and they stay right when the schema moves.
- **`ORDER BY` deterministically.** Add the id or the name as the last key, so two runs over the same data print the same rows in the same order.
