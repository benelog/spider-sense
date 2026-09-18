# Spider Sense: storage

Spider Sense keeps what it collects in an H2 file database under the user's home directory, so the UI still answers after the monitored application has stopped, and so every Spider Sense process on the machine, embedded or standalone, sees the same data.

## Where

Default JDBC URL: `jdbc:h2:~/db/spider-sense/sense;AUTO_SERVER=TRUE`, i.e. the files `~/db/spider-sense/sense.mv.db` (and `sense.trace.db` when H2 logs a problem).
`spidersense.db` overrides it: a path (`~/db/other/sense`) or a full `jdbc:h2:` URL.

`AUTO_SERVER=TRUE` is what makes the layouts work without ceremony:

- The first process to open the file becomes its server; a second process that opens the same URL connects to the first over a local TCP port, transparently.
  Two applications each embedding Spider Sense, plus a standalone `java -jar spider-sense.jar` opened to look at both, all share one database at once.
- When the process holding the file exits, the next opener takes over.
  Stop the application, run `java -jar spider-sense.jar`, and the traces of the session that just ended are on screen.

The H2 driver runs inside the `SenseClassLoader`, which the OpenTelemetry agent is told to leave alone, so the application's traces never contain Spider Sense's own SQL.
An application that uses H2 itself loads its own copy of the driver in its own class loader; the two copies never meet.

## What is stored

One row per span, log record, metric point, tingle and mark (a named moment, see [agent.md](agent.md)), plus a per-trace summary row that the writer maintains, so the lists and aggregations the API serves are SQL over indexed columns rather than a scan of blobs.
Attributes and events are kept as JSON text in the same row; the columns beside them are the values the queries need, extracted once at ingest.

```sql
CREATE TABLE IF NOT EXISTS service (
    name         VARCHAR(255) PRIMARY KEY,
    language     VARCHAR(64),
    pid          BIGINT,
    first_seen   BIGINT NOT NULL,          -- epoch ms
    last_seen    BIGINT NOT NULL,
    resource     VARCHAR(65535) NOT NULL   -- JSON object of the resource attributes
);

CREATE TABLE IF NOT EXISTS span (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id       CHAR(32) NOT NULL,
    span_id        CHAR(16) NOT NULL,
    parent_span_id CHAR(16),
    service        VARCHAR(255) NOT NULL,
    name           VARCHAR(1024) NOT NULL,
    kind           VARCHAR(8) NOT NULL,      -- SERVER CLIENT INTERNAL PRODUCER CONSUMER
    start_ms       BIGINT NOT NULL,
    start_ns       BIGINT NOT NULL,
    duration_ns    BIGINT NOT NULL,
    status         VARCHAR(5) NOT NULL,      -- UNSET OK ERROR
    status_message VARCHAR(4096),
    entry          BOOLEAN NOT NULL,         -- SERVER/CONSUMER, or a non-db root CLIENT/PRODUCER, unless the endpoint is ignored (design.md)
    error          BOOLEAN NOT NULL,
    slow           BOOLEAN NOT NULL,         -- entry over slow.request.ms, or db over slow.query.ms
    category       VARCHAR(10) NOT NULL,     -- http db messaging rpc internal
    endpoint       VARCHAR(1024),            -- entry spans: the endpoint name (see design.md)
    endpoint_id    CHAR(12),
    http_method    VARCHAR(16),
    http_route     VARCHAR(1024),
    http_status    INT,
    db_system      VARCHAR(64),
    db_statement   VARCHAR(4096),            -- cut at 2000 characters
    db_namespace   VARCHAR(255),
    db_operation   VARCHAR(64),
    db_table       VARCHAR(255),
    query_id       CHAR(12),
    error_type     VARCHAR(512),
    error_message  VARCHAR(4096),            -- as received
    error_id       CHAR(12),                 -- hash over the normalised message
    scope          VARCHAR(255),
    attributes     VARCHAR(65535) NOT NULL,  -- JSON object
    events         VARCHAR(65535) NOT NULL   -- JSON array
);
CREATE INDEX IF NOT EXISTS span_start   ON span (start_ms);
CREATE INDEX IF NOT EXISTS span_trace   ON span (trace_id);
CREATE INDEX IF NOT EXISTS span_service ON span (service, start_ms);
CREATE INDEX IF NOT EXISTS span_endpoint ON span (endpoint_id, start_ms);
CREATE INDEX IF NOT EXISTS span_query   ON span (query_id, start_ms);
CREATE INDEX IF NOT EXISTS span_error   ON span (error_id, start_ms);

CREATE TABLE IF NOT EXISTS trace (
    trace_id     CHAR(32) PRIMARY KEY,
    start_ms     BIGINT NOT NULL,
    end_ms       BIGINT NOT NULL,
    duration_ns  BIGINT NOT NULL,          -- last span end minus first span start
    root_span_id CHAR(16),
    root_name    VARCHAR(1024) NOT NULL,
    root_service VARCHAR(255) NOT NULL,
    root_kind    VARCHAR(8) NOT NULL,
    services     VARCHAR(4096) NOT NULL,     -- JSON array
    span_count   INT NOT NULL,
    error_count  INT NOT NULL,
    db_count     INT NOT NULL,
    http_status  INT,
    slow         BOOLEAN NOT NULL,
    error        BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS trace_start   ON trace (start_ms);
CREATE INDEX IF NOT EXISTS trace_service ON trace (root_service, start_ms);

CREATE TABLE IF NOT EXISTS log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    at_ms           BIGINT NOT NULL,
    service         VARCHAR(255) NOT NULL,
    severity_number INT NOT NULL,
    severity        VARCHAR(8) NOT NULL,
    body            VARCHAR(65535) NOT NULL,
    logger          VARCHAR(512),
    trace_id        CHAR(32),
    span_id         CHAR(16),
    attributes      VARCHAR(65535) NOT NULL
);
CREATE INDEX IF NOT EXISTS log_at      ON log (at_ms);
CREATE INDEX IF NOT EXISTS log_trace   ON log (trace_id);
CREATE INDEX IF NOT EXISTS log_service ON log (service, at_ms);

CREATE TABLE IF NOT EXISTS metric (
    name        VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(12) NOT NULL,        -- gauge sum histogram
    unit        VARCHAR(64),
    description VARCHAR(1024),
    monotonic   BOOLEAN NOT NULL,
    temporality VARCHAR(12)                  -- cumulative delta
);

CREATE TABLE IF NOT EXISTS metric_series (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    service    VARCHAR(255) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    attr_hash  CHAR(12) NOT NULL,
    attributes VARCHAR(4096) NOT NULL,       -- JSON object, keys sorted
    CONSTRAINT metric_series_key UNIQUE (service, name, attr_hash)
);

CREATE TABLE IF NOT EXISTS metric_point (
    series_id BIGINT NOT NULL,
    at_ms     BIGINT NOT NULL,
    value     DOUBLE,                        -- gauge/sum value, histogram mean
    count     BIGINT,                        -- histogram
    sum       DOUBLE,
    min       DOUBLE,
    max       DOUBLE,
    buckets   VARCHAR(8192),                 -- JSON {"bounds":[...],"counts":[...]}
    PRIMARY KEY (series_id, at_ms)
);

CREATE TABLE IF NOT EXISTS tingle (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    at_ms       BIGINT NOT NULL,
    kind        VARCHAR(16) NOT NULL,        -- slow-request slow-query error
    service     VARCHAR(255) NOT NULL,
    title       VARCHAR(1024) NOT NULL,
    detail      VARCHAR(4096) NOT NULL,
    trace_id    CHAR(32),
    span_id     CHAR(16),
    duration_ms DOUBLE
);
CREATE INDEX IF NOT EXISTS tingle_at ON tingle (at_ms);

CREATE TABLE IF NOT EXISTS mark (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    at_ms    BIGINT NOT NULL,
    name     VARCHAR(64) NOT NULL,         -- [A-Za-z0-9._-]; "start" is written by the writer
    service  VARCHAR(255),
    note     VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS mark_at ON mark (at_ms);

CREATE TABLE IF NOT EXISTS meta (
    key   VARCHAR(64) PRIMARY KEY,
    value VARCHAR(4096) NOT NULL             -- schema_version, created_at
);
```

The schema is created with `IF NOT EXISTS` at startup; `meta.schema_version` is `3` (the `mark` table arrived with it), and a version that changes a table drops and recreates every table (the data is a cache of a development session, not a record).
`entry` is decided once, when the row is written, so rows written by an older Spider Sense keep the flag they were written with — a root `INTERNAL` or database span from before the rule narrowed still counts as a request until the retention sweeper removes it.

## The read-only user

Beside the tables the schema creates one H2 user, `spider_sense_reader`, with `CREATE USER IF NOT EXISTS` and a constant password, and grants it `SELECT` on schema `PUBLIC`.
It is what `POST /api/sql` and the CLI's `sql` run on ([agent.md](agent.md)): a user with `SELECT` and nothing else, so H2 itself refuses every write, every piece of DDL and the administrator-only functions (`FILE_WRITE`, `CSVWRITE`, `FILE_READ`, `LINK_SCHEMA`, `RUNSCRIPT`) to whatever statement gets that far.
The grant is on the schema rather than on the tables, so it covers a table a later version adds without a `schema_version` bump, and creating the user is idempotent on every open.
Only a server's open creates it, never the CLI's: `AUTO_SERVER=TRUE` may have joined the database of an older Spider Sense, and adding a user to it is as much a change to somebody else's database as recreating its tables would be.
The reader's connection uses the same JDBC URL with the settings a non-administrator may not apply removed — H2 turns most of them into a `SET` at connect time, and `DB_CLOSE_DELAY`, which the in-memory fallback sets, is one an ordinary user is refused — keeping only `AUTO_SERVER` and `NON_KEYWORDS`.

## How it is written

Ingest never touches the database on the request thread.
The OTLP handler decodes the request into records and hands them to a `Writer`: a bounded queue (10,000 batches; when full, the oldest batch is dropped and a counter shown on `/api/status` increments) drained by one daemon thread that flushes every 200 ms or as soon as 500 records are waiting, in one transaction per flush with JDBC batch inserts.
After each flush the writer recomputes the `trace` rows of the trace ids the flush touched (`MERGE INTO trace ... SELECT ... FROM span WHERE trace_id IN (...) GROUP BY trace_id`), because a trace's spans arrive in several exports and from several services, and publishes the flush's tingles and counts to the SSE stream.
The `service` row is merged on every flush that carries the service; when the sighting carries a `process.pid` that differs from the stored one (or the row is new), the writer also inserts a `mark` named `start` for that service with the note `pid <pid>`, which is what `since=start` resolves to.
A JVM shutdown hook flushes what is queued.

Metric points are written on the same path; a series row is looked up by `(service, name, attr_hash)` through a small in-memory cache of ids.

## How it is read

Every API answer is one or a few SQL statements over the window:

- Lists (`trace`, `log`, `tingle`, scatter points): `WHERE start_ms BETWEEN ? AND ?` with the filters as further predicates, `ORDER BY start_ms DESC LIMIT ?`.
- Aggregations (services, endpoints, queries, errors): `GROUP BY` over `span` with `COUNT(*)`, `SUM(...)`, `MAX(...)`, and `PERCENTILE_DISC(0.5|0.95|0.99) WITHIN GROUP (ORDER BY duration_ns)` (H2 2.x supports it).
  The response-time histogram is four more `SUM(CASE WHEN NOT error AND duration_ns <= :bound ...)` columns in the same statements, with the bounds derived from `slow.request.ms`; Apdex is arithmetic over them in Java.
- Time series: `GROUP BY start_ms / :bucket` for counts, errors, histogram buckets and percentiles per bucket.
- The service map: `service → service` edges are one self-join, `span c JOIN span p ON p.trace_id = c.trace_id AND p.span_id = c.parent_span_id WHERE c.entry AND p.service <> c.service`, grouped by the two services; the external targets reuse the dependency scan (outbound spans of the window, capped at 20,000 rows) minus the spans that self-join found.
- A trace: `SELECT ... FROM span WHERE trace_id = ? ORDER BY start_ns`, plus its logs.
- Findings (agent.md): `n-plus-one` is `GROUP BY trace_id, query_id HAVING COUNT(*) >= 5` over the database spans of the window, attributed to the entry span by the same parent-chain walk the query callers use; `dbCallsPerRequest` and `dbMsPerRequest` of an endpoint join the endpoint's entry spans with the database spans of the same trace and service; `slow-job` is the same aggregation over `span WHERE parent_span_id IS NULL AND kind = 'INTERNAL'` grouped by `(service, name)`, with the database work joined the same way; the other kinds are the endpoint, query and error aggregations above, filtered by the thresholds.
- A time selector that names a mark: `SELECT at_ms FROM mark WHERE name = ? [AND service = ?] ORDER BY at_ms DESC LIMIT 1`.
- Free-text search (`q`): `LOWER(name) LIKE ? OR LOWER(attributes) LIKE ?` within the window; a scan of the window is acceptable at local-development volumes.
- JVM and metrics: `metric_point` joined with `metric_series`, resampled in Java where the API asks for it.

Connections come from H2's `JdbcConnectionPool` (max 8); the writer holds one of its own.
Reads are plain JDBC through one small helper (`Sql.query(sql, params, rowMapper)`); no ORM, no spring-jdbc, so the nested server jar carries only H2 beyond what it already has.

The CLI (agent.md) reads the same way when no server answers: it opens the same URL, so it joins a running auto-server or, when none is running, opens the file itself for the length of the command, and runs the same `Queries` without a writer or a sweeper.
Its open refuses a missing file and refuses another `schema_version` instead of dropping the tables, since the database it joined may belong to an older server that is still writing to it.

## Retention

`spidersense.retention.hours` (default `24`).
A daemon sweeper runs a minute after start and every five minutes after that: `DELETE FROM span|trace|log|metric_point|tingle|mark WHERE <time> < now - retention`, then `metric_series` rows with no points.
`DELETE /api/data` runs the same deletes without the time bound.
At 24 hours of a few requests per second the file stays in the low hundreds of megabytes; H2 reclaims space on the next compaction when the database closes.
`spidersense.retention.spans` and the other count caps from the earlier in-memory design are gone; time is the only retention.

## Failure

If the database cannot be opened (a corrupt file, a permission problem), the server logs one line, falls back to `jdbc:h2:mem:spidersense-<pid>` so the UI still works for the session, and marks `/api/status.storage.fallback = true` with the reason.
Nothing in this path throws out of `premain`.
