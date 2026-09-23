# Spider Sense: HTTP API contract

The contract between `spider-sense-server` and the UI in `src/main/resources/public`.
Both sides are built against this file; a change here is a change to both.

Conventions:

- All times are **epoch milliseconds** (integers) in JSON; durations are **milliseconds as doubles** (`12.34`) unless the field name ends in `Ns`.
- Every read endpoint takes an optional window `from` and `to` (epoch ms). Default `to` is now, default `from` is `to - 15 min`.
- `service` filters are exact matches on `service.name`.
- Lists are newest first unless stated.
- Unknown query parameters are ignored; a malformed number is a 400 (`{"error": "..."}`).
- Every JSON answer is `application/json; charset=utf-8`. Errors are `{"error": "<message>"}` with a 4xx/5xx status.
- IDs: `traceId` is 32 lowercase hex characters, `spanId` 16. Group ids (`queryId`, `errorId`, `endpointId`) are opaque strings (a hash of the group key) stable for the life of the process.

## Ingest (OTLP/HTTP)

```
POST /v1/traces
POST /v1/metrics
POST /v1/logs
```

Request `Content-Type`: `application/x-protobuf` or `application/json`; `Content-Encoding: gzip` accepted.
Answer: `200` with an empty `Export{Trace,Metrics,Logs}ServiceResponse` in the request's content type; `400` on an undecodable body; `415` on another content type.
Partial-success is never reported (everything decodable is stored).
A log record carrying the attribute `spidersense.schema.table` is the index catalog of one table, read by the extension (design.md, "The extension"), and is stored as a `db_table` row rather than as a log (storage.md).

## Status and control

`GET /api/status`

```json
{
  "name": "Spider Sense",
  "version": "0.1.0",
  "mode": "agent" | "standalone",
  "startedAt": 1758000000000,
  "now": 1758000900000,
  "endpoint": "http://127.0.0.1:4000",
  "otlp": { "traces": "http://127.0.0.1:4000/v1/traces", "metrics": "...", "logs": "..." },
  "embeddedService": "silk-bookstore" | null,
  "thresholds": { "slowRequestMs": 500, "slowQueryMs": 100, "responseBucketsMs": [125, 500, 2000] },
  "ignore": { "endpoints": ["/actuator/**", "/health", "/healthz", "/livez", "/readyz"] },   // design.md's spidersense.ignore.endpoints, as configured
  "retention": { "hours": 24, "spans": 1000000 },
  "ingest": { "maxSpansPerSecond": null | 5000 },
  "storage": { "url": "jdbc:h2:~/db/spider-sense/sense;AUTO_SERVER=TRUE", "path": "/home/me/db/spider-sense/sense.mv.db", "sizeBytes": 12345678, "fallback": false, "fallbackReason": null, "droppedBatches": 0, "droppedSpans": 0, "queued": 0 },
  "counts": { "spans": 12345, "traces": 2345, "logs": 456, "metricSeries": 78, "services": 2 },
  "oldest": { "span": 1758000000000, "log": 1758000000000 }
}
```

`DELETE /api/data` → `204`. Deletes every span, trace, log, metric point, tingle, mark, acknowledgement and resolution (services and metric metadata stay).

`GET /api/export?traceId` → `application/json` download (`Content-Disposition: attachment`) of `{ "traces": [<trace as in GET /api/traces/{id}>] }`, one trace.
`GET /api/export?from&to&since&until&service` without `traceId` → the session document of [agent.md](agent.md#export-and-import), streamed; `POST /api/import` takes it back and answers the counts (`Content-Encoding: gzip` accepted).

## Live events

`GET /api/events` → `text/event-stream`. Events:

```
event: tingle
data: {"kind":"slow-request"|"slow-query"|"error","at":1758000000000,"service":"spring-orders","title":"GET /orders/report","detail":"1,532 ms","traceId":"...","spanId":"...","durationMs":1532.4}

event: stats
data: {"at":1758000000000,"spans":12345,"traces":2345,"logs":456,"droppedSpans":0,"perSecond":{"spans":12.5,"logs":3.0}}

event: service
data: {"name":"spring-orders","firstSeen":1758000000000}
```

`stats` is sent at most every second while data arrives, and a `: keepalive` comment every 15 s.

## Overview

`GET /api/overview?from&to`

```json
{
  "window": { "from": 1758000000000, "to": 1758000900000, "bucketMs": 15000 },
  "totals": { "requests": 1200, "errors": 12, "errorRate": 0.01, "rps": 1.33, "p50Ms": 12.1, "p95Ms": 210.4, "p99Ms": 840.0, "maxMs": 1532.4,
              "apdex": 0.93, "histogram": [900, 200, 70, 18, 12] },
  "services": [ <ServiceSummary> ],
  "tingles": [ <Tingle> ],            // newest first, at most 50, within window
  "series": {                          // one point per bucket, aligned across arrays, oldest first
    "t": [1758000000000, ...],
    "requests": [12, ...],
    "errors": [0, ...],
    "p95Ms": [120.5, ...],             // null where the bucket is empty
    "histogram": [[9, ...], [2, ...], [1, ...], [0, ...]]   // non-error requests per response-time bucket, one array per bucket
  }
}
```

**Response-time buckets and Apdex** (a Pinpoint-style response summary, load chart and Apdex score).
`thresholds.responseBucketsMs` is `[T/4, T, 4T]` with `T = slowRequestMs`, so the default buckets are `≤125 ms`, `≤500 ms`, `≤2 s`, `>2 s`.
Everywhere a `histogram` appears it is five counts: the non-error requests in each of the four buckets, then the error count; the five sum to `requests`.
`apdex` is `(histogram[0] + histogram[1] + histogram[2] / 2) / requests` (satisfied up to `T`, tolerating up to `4T`, errors frustrated), rounded to three decimals, `null` when there is no request.
In a `series`, `histogram` is four aligned arrays, one per bucket; the errors of a time bucket are already in `series.errors`.

`ServiceSummary`:

```json
{
  "name": "spring-orders",
  "language": "java",              // telemetry.sdk.language or null
  "embedded": false,
  "firstSeen": 1758000000000,
  "lastSeen": 1758000900000,
  "requests": 800, "errors": 10, "errorRate": 0.0125, "rps": 0.9,
  "p50Ms": 10.0, "p95Ms": 300.0, "p99Ms": 900.0, "maxMs": 1532.4,
  "apdex": 0.91, "histogram": [600, 150, 30, 10, 10],
  "sparkline": [3, 5, 0, 8, ...],   // requests per bucket, oldest first, same buckets as overview.series.t
  "hasJvm": true                    // any jvm.* metric seen
}
```

`Tingle`:

```json
{ "kind": "slow-request" | "slow-query" | "error", "at": 1758000000000, "service": "…", "title": "GET /orders/report", "detail": "1,532 ms" | "SELECT … FROM …" | "IllegalStateException: …", "traceId": "…", "spanId": "…", "durationMs": 1532.4 }
```

## Services

`GET /api/services?from&to` → `{ "services": [ <ServiceSummary> ] }` (every service ever seen, zeros when nothing in window).

`GET /api/services/{name}?from&to`

```json
{
  "service": <ServiceSummary>,
  "resource": { "service.name": "…", "telemetry.sdk.name": "opentelemetry", "process.runtime.version": "21.0.4", ... },   // string values only, sorted by key
  "window": { "from": ..., "to": ..., "bucketMs": ... },
  "series": { "t": [...], "requests": [...], "errors": [...], "p50Ms": [...], "p95Ms": [...], "p99Ms": [...], "histogram": [[...], [...], [...], [...]] },
  "endpoints": [ <EndpointStats> ],   // sorted by total time desc
  "queries": [ <QueryStats> ],        // top 10 by total time
  "errors": [ <ErrorGroup> ],         // top 10 by count
  "dependencies": [ { "kind": "http" | "db" | "messaging" | "rpc", "target": "localhost:8081" | "h2:orders", "calls": 120, "errors": 0, "avgMs": 4.2, "p95Ms": 9.1 } ]
}
```

`EndpointStats`:

```json
{ "endpointId": "…", "service": "…", "method": "GET" | null, "route": "/orders/{id}", "name": "GET /orders/{id}", "kind": "SERVER",
  "calls": 120, "errors": 2, "errorRate": 0.0167, "rps": 0.13,
  "avgMs": 12.0, "p50Ms": 9.0, "p95Ms": 40.0, "p99Ms": 90.0, "maxMs": 300.0, "totalMs": 1440.0,
  "apdex": 0.98, "histogram": [110, 6, 2, 0, 2],
  "statusCodes": { "200": 118, "500": 2 } }
```

`GET /api/endpoints?from&to&service` → `{ "endpoints": [ <EndpointStats> ] }` across services, sorted by total time desc.

`GET /api/endpoints/{endpointId}?from&to` → `{ "endpoint": <EndpointStats>, "series": {...as service series...}, "queries": [<QueryStats>], "errors": [<ErrorGroup>], "traces": [<TraceSummary> x 20 slowest], "recent": [<TraceSummary> x 20 newest], "breakdown": { "db": 0.44, "http": 0.21, "internal": 0.07, "self": 0.28 } }`.

`breakdown` is where the endpoint's time went over the 20 slowest traces, the same four shares a `slow-endpoint` finding carries (agent.md, "Where the time went"); `{}` when those traces add up to nothing.

## Traces

`GET /api/traces?from&to&service&endpointId&minMs&maxMs&status=error|ok|all&q&limit=50&before=<epoch ms>`

- `q` is a case-insensitive substring match over the root span name, every span name, and every string attribute value of the trace.
- `before` pages backwards: traces whose start is strictly before it.

```json
{ "traces": [ <TraceSummary> ], "total": 2345, "window": {...} }
```

`TraceSummary`:

```json
{ "traceId": "…", "start": 1758000000000, "durationMs": 152.3,
  "rootName": "GET /orders/{id}", "rootService": "spring-orders", "rootKind": "SERVER",
  "services": ["spring-orders", "silk-bookstore"], "spanCount": 14, "errorCount": 0, "dbCount": 6, "httpStatus": 200,
  "slow": true, "error": false }
```

`GET /api/traces/{traceId}` (with `diff=<traceId>` the two traces aligned instead, as [agent.md](agent.md#trace-diff) specifies, in JSON or `format=text`)

```json
{
  "traceId": "…", "start": ..., "end": ..., "durationMs": ...,
  "services": ["…"],
  "spans": [ <Span> ],       // sorted by start, parents before children; a parentSpanId not present in the trace is shown as a root
  "logs": [ <LogRecord> ]    // logs carrying this traceId, oldest first
}
```

`Span`:

```json
{ "spanId": "…", "parentSpanId": "…" | null, "service": "…", "name": "GET /orders/{id}", "kind": "SERVER",
  "start": 1758000000000, "startNs": 1758000000000123456, "durationMs": 152.3, "durationNs": 152300000,
  "status": "UNSET" | "OK" | "ERROR", "statusMessage": "…" | null,
  "attributes": { "http.request.method": "GET", "server.port": 8081, "db.statement": "…", ... },  // values: string | number | boolean | array of those
  "events": [ { "name": "exception", "time": 1758000000100, "attributes": { "exception.type": "…", "exception.message": "…", "exception.stacktrace": "…" } } ],
  "scope": "io.opentelemetry.spring-webmvc-6.0",
  "category": "http" | "db" | "messaging" | "rpc" | "internal",   // derived from attributes, for colouring
  "summary": "GET /orders/{id} → 200" | "SELECT orders" | "…",          // one line the waterfall shows
  "slow": false, "error": false }
```

## Service map

`GET /api/map?from&to`

The topology of the window, drawn Pinpoint-style: one node per service, per database, per external HTTP host, per messaging destination, plus one `user` node for the traffic that comes from outside every traced service.

```json
{
  "window": {...},
  "nodes": [
    { "id": "user", "kind": "user", "name": "Clients" },
    { "id": "svc:spring-orders", "kind": "service", "name": "spring-orders",
      "requests": 800, "errors": 10, "errorRate": 0.0125, "rps": 0.9,
      "p50Ms": 10.0, "p95Ms": 300.0, "p99Ms": 900.0, "maxMs": 1532.4,
      "apdex": 0.91, "histogram": [600, 150, 30, 10, 10], "hasJvm": true },
    { "id": "db:h2:orders", "kind": "db", "name": "h2:orders", "calls": 2400, "errors": 0, "avgMs": 4.2, "p95Ms": 9.1 },
    { "id": "http:localhost:8081", "kind": "http", "name": "localhost:8081", "calls": 12, "errors": 0, "avgMs": 30.0, "p95Ms": 80.0 }
  ],
  "edges": [
    { "from": "user", "to": "svc:spring-orders", "calls": 780, "errors": 10, "avgMs": 42.0, "p95Ms": 300.0 },
    { "from": "svc:spring-orders", "to": "svc:silk-bookstore", "calls": 120, "errors": 0, "avgMs": 25.0, "p95Ms": 60.0 },
    { "from": "svc:spring-orders", "to": "db:h2:orders", "calls": 2400, "errors": 0, "avgMs": 4.2, "p95Ms": 9.1 }
  ]
}
```

- A `service` node exists for every service with an entry span in the window; its numbers are the `ServiceSummary` numbers.
- A `service → service` edge is counted from the entry spans of the callee whose parent span, in the same trace, belongs to another service; `calls`, `errors`, `avgMs` and `p95Ms` are those entry spans'.
- A `user → service` edge is counted from `SERVER` and `CONSUMER` entry spans whose parent is absent or not stored: the request came from something that is not traced.
- Every other node is an outbound target, grouped as `GET /api/services/{name}.dependencies` groups them (`kind` is `db`, `http`, `messaging` or `rpc`; `name` is the dependency's `target`), and an edge from each service that calls it.
  An outbound span whose child is an entry span of another service is that `service → service` edge and not an external target, so a call to `localhost:8081` shows as a call to `silk-bookstore` when the bookstore is traced too.
- A node with no edge is not listed, so a service whose only traffic is calls to itself does not appear.
- "Not stored" means not stored at all, whatever the window: a parent that was traced but starts before the window still counts as a traced caller, not as a user.

## Scatter (response-time scatter)

`GET /api/scatter?from&to&service&endpointId&limit=5000`

One point per entry span in the window (newest first, cut at `limit`; the answer says when it was cut so the UI can shrink the window).

```json
{ "window": {...}, "truncated": false,
  "points": [ [1758000000123, 152.3, "spring-orders", "GET /orders/{id}", "<traceId>", 0], ... ] }
```

A point is `[start, durationMs, service, endpointName, traceId, flags]` with `flags` a bit set: `1` = error, `2` = slow, `4` = contains a slow query.
Arrays rather than objects: 5,000 points must stay small on the wire.

## Queries

`GET /api/queries?from&to&service&sort=total|avg|p95|max|calls&limit=100`

```json
{ "queries": [ <QueryStats> ] }
```

`QueryStats`:

```json
{ "queryId": "…", "service": "…", "system": "h2", "namespace": "orders" | null, "operation": "SELECT" | null, "table": "orders" | null,
  "statement": "select … from orders where … like ?",
  "calls": 240, "errors": 0, "avgMs": 45.0, "p50Ms": 40.0, "p95Ms": 120.0, "maxMs": 900.0, "totalMs": 10800.0,
  "slowCalls": 12,                       // calls over slowQueryMs
  "callers": [ { "endpoint": "GET /orders/report", "service": "spring-orders", "calls": 240 } ],
  "lastSeen": 1758000900000,
  "schema": { "tables": [...], "predicates": [...], "unindexed": [...] } | null   // agent.md, "The schema block"
}
```

`GET /api/queries/{queryId}?from&to` → `{ "query": <QueryStats>, "series": { "t": [...], "calls": [...], "p95Ms": [...] }, "traces": [<TraceSummary> x 20 slowest containing it] }`.

## Errors

`GET /api/errors?from&to&service&limit=100`

```json
{ "errors": [ <ErrorGroup> ] }
```

`ErrorGroup`:

```json
{ "errorId": "…", "service": "…", "type": "java.lang.IllegalStateException", "message": "Order ? is already shipped",
  "count": 12, "firstSeen": ..., "lastSeen": ...,
  "endpoints": [ { "name": "POST /orders/{id}/ship", "count": 12 } ],
  "sample": { "traceId": "…", "spanId": "…", "at": ..., "message": "Order 42 is already shipped", "stacktrace": "…" } | null }
```

`GET /api/errors/{errorId}?from&to` → `{ "error": <ErrorGroup>, "code": [ "orders.OrderService.load(OrderService.java:41)" ], "chain": [ <Cause> ], "series": { "t": [...], "count": [...] }, "traces": [<TraceSummary> x 20 newest] }`.
`code` is the sample stack trace's application frames, reduced by the rules a finding's `code` follows (agent.md, "Code locations"): the root cause's first, then those of each exception wrapping it; empty when there is no sample or no application frame.
`chain` is the sample stack trace read as its exceptions, innermost first: the root cause, then each exception that wraps it, out to the outer one; empty when there is no sample or no stack trace.

```json
{ "type": "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException", "message": "Unique index or primary key violation: …",
  "frames": [ "org.h2.message.DbException.get(DbException.java:223)", "orders.OrderRepository.save(OrderRepository.java:41)" ],
  "more": 42 }
```

`frames` are every frame the section prints, top first, written as `code` writes a frame (no module or class-loader prefix); `more` is the count of the `... n more` line, the frames the cause shares with the exception wrapping it, `0` when there is none.
A `Suppressed:` block is not a cause and is left out; a trace without a header (no `Type: message` line) is one cause whose `type` is `""`, and a message that spans lines keeps its line breaks.

## Source

`GET /api/source?frame=<code frame>` → the lines around a code frame's line, read from the file when asked and never stored:

```json
{ "frame": "orders.OrderService.load(OrderService.java:41)",
  "file": "/home/me/project/src/main/java/orders/OrderService.java",
  "line": 41, "start": 39,
  "lines": [ "…line 39…", "…line 40…", "…line 41…", "…line 42…", "…line 43…" ] }
```

`frame` is a frame as a finding's `code` carries it; it resolves to the first `<root>/<package as directories>/<file>` under the roots of `spidersense.source.dirs` (design.md) that exists.
`file` is that file's absolute path with symbolic links resolved, `line` the frame's line, and `lines` the lines from `start` up to two past `line`, cut at the start and the end of the file, so a file edited since the frame was recorded may answer fewer lines, or none.
A frame that does not resolve, one without a file and a line (`Unknown Source`, `Native Method`, a `code.function` frame), and one whose path would leave its root is `404`; a missing `frame` is `400`.
The path is built from the frame only when it parses as dotted Java identifiers, a method, and a plain file name with a `.java`, `.kt`, `.groovy` or `.scala` extension, and the resolved file, symbolic links followed, must lie under its root; nothing else is read.

## Logs

`GET /api/logs?from&to&service&severity=TRACE|DEBUG|INFO|WARN|ERROR&q&traceId&limit=200&before=<epoch ms>`

`severity` is a minimum (WARN shows WARN and ERROR).

```json
{ "logs": [ <LogRecord> ], "total": 456 }
```

`LogRecord`:

```json
{ "id": 12345, "at": 1758000000000, "service": "…", "severity": "INFO", "severityNumber": 9,
  "body": "Started OrdersApplication in 2.1 seconds", "logger": "o.s.boot.StartupInfoLogger",   // logger from the scope name
  "traceId": "…" | null, "spanId": "…" | null,
  "attributes": { "thread.name": "main", "exception.stacktrace": "…" } }
```

## Metrics

`GET /api/metrics?service` → catalog:

```json
{ "metrics": [ { "name": "jvm.memory.used", "type": "gauge" | "sum" | "histogram", "unit": "By", "description": "…", "services": ["…"], "series": 6 } ] }
```

`GET /api/metrics/series?name&service&from&to&attr.<key>=<value>&step=<ms>`

```json
{ "name": "jvm.memory.used", "type": "gauge", "unit": "By",
  "series": [ { "service": "…", "attributes": { "jvm.memory.type": "heap", "jvm.memory.pool.name": "G1 Eden Space" }, "t": [...], "v": [...] } ] }
```

For a histogram, `v` is the per-point `sum/count` mean and each series also carries `"count": [...]`, `"p95": [...]` (estimated from buckets), `"max": [...]`.
For a monotonic sum (`jvm.cpu.time`, `jvm.gc.duration`'s count), `v` is the rate per second between points when `rate=true`, else the cumulative value.

`GET /api/jvm?service&from&to` → the curated view, empty arrays when the service sent no JVM metrics:

```json
{
  "service": "…", "runtime": { "jvm": "OpenJDK 64-Bit Server VM 21.0.4", "pid": 12345, "host": "…", "cpuCount": 8 },
  "heap":    { "t": [...], "used": [...], "committed": [...], "limit": [...] },
  "nonHeap": { "t": [...], "used": [...], "committed": [...] },
  "pools":   [ { "name": "G1 Eden Space", "type": "heap", "t": [...], "used": [...] } ],
  "gc":      [ { "name": "G1 Young Generation", "action": "end of minor GC", "t": [...], "count": [...], "durationMs": [...] } ],   // per bucket
  "threads": { "t": [...], "count": [...], "daemon": [...] },
  "cpu":     { "t": [...], "utilization": [...], "systemLoad1m": [...] },
  "classes": { "t": [...], "loaded": [...] },
  "connectionPools": [ { "name": "HikariPool-1", "t": [...], "used": [...], "idle": [...], "max": [...], "pending": [...] } ]
}
```

`connectionPools` is a Pinpoint-style "data source" panel: one entry per JDBC pool the agent instruments (HikariCP, Tomcat JDBC, c3p0, ...), empty when the service reports none.
It is read from `db.client.connections.usage` (attribute `state` = `used` | `idle`, pool from `pool.name`), `db.client.connections.max` and `db.client.connections.pending_requests`, or from their stable-semconv names `db.client.connection.count` (`db.client.connection.state`, `db.client.connection.pool.name`), `db.client.connection.max` and `db.client.connection.pending_requests`.
`max` and `pending` are `null` where the pool reports no such series.

## Agent-facing endpoints

The semantics (time selectors, the finding rules, verdicts, the text renderings) are specified in [agent.md](agent.md); this section is the wire shape only.

### Time selectors

`since` and `until` are accepted by the endpoints below and by every windowed endpoint of this document, as an alternative to `from`/`to`; `from`/`to` win when both are given.
A selector is a duration (`30s`, `5m`, `2h`, `1d`), epoch milliseconds (13 or more digits), a mark name (the newest mark with that name), `start` (the newest automatic start mark, of `service` when given) or `now`.
`since` defaults to `15m`, `until` to `now`.
An unknown mark name is `404`; a `since` after `until` is `400`.

### Text rendering

`format=text`, or an `Accept` header whose first type is `text/markdown` or `text/plain`, answers `text/markdown; charset=utf-8` instead of JSON on: `/api/status`, `/api/findings`, `/api/marks`, `/api/compare`, `/api/check`, `/api/sql`, `/api/traces`, `/api/traces/{id}`, `/api/endpoints`, `/api/queries`, `/api/errors`, `/api/logs`, `/api/services`.
`full=true` keeps statements whole and expands collapsed spans.
The format of each rendering is in agent.md.

### Marks

`GET /api/marks?limit=50` → `{ "marks": [ <Mark> ] }`, newest first.

`POST /api/marks` with `{ "name": "before", "note": "…" | null, "service": "…" | null, "at": <epoch ms> | absent }` → `201` `<Mark>`.
`name` is required and matches `[A-Za-z0-9._-]{1,64}`, else `400`.

`Mark`:

```json
{ "id": 12, "at": 1758000000000, "name": "before", "service": "spring-orders" | null, "note": "pid 12345" | null }
```

A mark named `start` is inserted by the writer when a service reports a `process.pid` it has not stored for that service.

### Findings

`GET /api/findings?since&until&service&limit=20&hideAcked=false` → `{ "window": {...}, "requests": 120, "acked": 2, "resolved": 1, "findings": [ <Finding> ] }`, ranked as agent.md says, regressions first, acknowledged findings and resolved findings that have not come back last and left out with `hideAcked=true`; `limit` is at most 100.

`POST /api/findings/{id}/ack` with `{ "note": "…" | null }` → `201` `{ "findingId": "…", "at": …, "note": … }`; `DELETE /api/findings/{id}/ack` → `204` or `404`; `GET /api/acks` → `{ "acks": [ { "findingId", "at", "note" } ] }` newest first (agent.md, Acknowledgements).

`POST /api/findings/{id}/resolve` with `{ "note": "…" | null }` → `201` `{ "findingId": "…", "at": …, "note": … }`; `DELETE /api/findings/{id}/resolve` → `204` or `404` (agent.md, Resolutions).

`Finding`:

```json
{ "id": "n-plus-one:1a2b3c4d5e6f",
  "kind": "regression" | "error" | "log-error" | "n-plus-one" | "n-plus-one-http" | "slow-query" | "slow-endpoint" | "slow-job" | "slow-external" | "pool-exhausted" | "gc-pause" | "heap-pressure" | "thread-growth",
  "severity": "high" | "medium" | "low", "state": "new" | "ongoing" | "regressed", "service": "…", "title": "…", "why": "…",
  "subject": { "endpointId": "…" | null, "queryId": "…" | null, "errorId": "…" | null, "pool": "…" | null, "job": "…" | null,
               "target": "…" | null, "logger": "…" | null, "jvm": "…" | null },
  "numbers": { ...kind-specific, see agent.md... },
  "statement": "…" | null, "code": [ "orders.OrderService.load(OrderService.java:41)" ], "traces": [ "<traceId>" ],
  "schema": { "tables": [ { "table": "ITEMS", "schema": "PUBLIC" | null,
                            "indexes": [ { "name": "PRIMARY_KEY_8", "unique": true, "columns": [ "ID" ] } ] } ],
              "predicates": [ "items.name" ], "unindexed": [ "items.name" ] } | null,   // slow-query and n-plus-one; agent.md, "The schema block"
  "ack": { "at": …, "note": "…" | null } | null,
  "resolution": { "at": …, "note": "…" | null } | null }
```

### Compare

`GET /api/compare?before=<selector>&after=<selector>&until=<selector>&service` → the two windows `[before, after)` and `[after, until)`:

```json
{ "before": { "from": …, "to": … }, "after": { "from": …, "to": … },
  "totals": { "before": <Totals>, "after": <Totals> },
  "endpoints": [ { "endpointId": "…", "service": "…", "name": "…", "before": <Side> | null, "after": <Side> | null, "verdict": "better" | "worse" | "same" | "new" | "gone" } ],
  "queries":   [ { "queryId": "…", "service": "…", "statement": "…", "before": <QuerySide> | null, "after": <QuerySide> | null, "verdict": "…" } ],
  "errors":    [ { "errorId": "…", "service": "…", "type": "…", "message": "…", "before": 0, "after": 3, "verdict": "…" } ] }
```

`Side` is `{ "calls", "errors", "p50Ms", "p95Ms", "maxMs", "dbCallsPerRequest", "dbMsPerRequest" }`; `QuerySide` is `{ "calls", "callsPerRequest", "p95Ms", "totalMs" }`.
`before` and `after` are required; a missing one is `400`.

### Check

`GET /api/check?since&until&service&endpoint&maxP95Ms&maxErrors&maxErrorRate&maxQueriesPerRequest&maxSlowQueries&maxNPlusOne&maxLogErrors&maxRegressions&minApdex`

```json
{ "pass": true | false | null, "requests": 12, "reason": "no requests in the window" | null,
  "checks": [ { "rule": "maxP95Ms", "limit": 500, "actual": 812.4, "pass": false, "detail": "GET /orders/report p95 812.4 ms over 3 calls" } ] }
```

With no rule given the defaults are `maxErrors=0`, `maxNPlusOne=0`, `maxP95Ms=<slowRequestMs>`.
`endpoint` is an `endpointId` or an endpoint name.
The response also carries the verdict as the header `X-Spider-Sense-Pass: true|false|none`, so the CLI can ask once for the text rendering and still exit with a code rather than parse prose for a word.

### SQL

`POST /api/sql` with `{ "sql": "SELECT …", "limit": 200 }`, and `format=text` as a query parameter when the Markdown rendering is wanted:

```json
{ "columns": ["ENDPOINT", "STATEMENTS"], "rows": [ ["GET /orders/{id}", 42], ["GET /orders", 7] ],
  "rowCount": 2, "truncated": false, "elapsedMs": 3 }
```

`columns` are H2's own labels, which upper-case an unquoted name; a cell is a JSON number, string, boolean or null, as the store holds it, and an H2 `TIMESTAMP` is an ISO string.
`limit` defaults to 200 and is capped at 5000, and a `limit` below 1 is a `400`; `truncated` says whether the cap cut the rows off.
The statement must be a single `SELECT`, `WITH`, `TABLE`, `VALUES`, `EXPLAIN` or `SHOW`, and it runs as an H2 user that has `SELECT` and nothing else (agent.md, storage.md).
A refused statement and a statement H2 would not run are both `400`: `{ "error": "…" }`, or the message on one line when the text rendering was asked for.

### MCP

`POST /mcp` with one JSON-RPC 2.0 message as `application/json`; the semantics, the seven tools and their arguments are in [agent.md](agent.md#mcp).

- A request (`id` present) is answered `200` with the JSON-RPC response as `application/json`; a notification is answered `202` with no body.
- No session: no `Mcp-Session-Id` header is issued or read. `GET /mcp` and `DELETE /mcp` are `405`.
- A body that is not a JSON object, or is a JSON array, is `200` with a JSON-RPC error `-32600`; a method the server does not have is `-32601`; a missing required argument or an unknown tool name is `-32602`.
- A tool call that the CLI would report with exit code `4` or a `400` is a `200` whose result carries `isError: true` and the message as its one text content.
- `tools/call` for `check` also carries `structuredContent: { "pass": true | false | null, "requests": 12 }`.

## Static UI

`GET /` and every path without an `/api/` or `/v1/` prefix that has no file: the UI's `index.html` (the router is hash-based, so this is mostly `/`).
`GET /assets/**`: files under `public/assets`.

## Clarifications

Decisions the server made where this document left room, recorded so the UI can rely on them.

### Endpoint identity

An entry span's endpoint name is `METHOD route` when `http.route` is present **and is not a servlet-mapping wildcard** (`/`, `/*`, or anything ending in `/*`); otherwise it is the span name.
A wildcard mapping says "everything", so honouring it would collapse every endpoint of a Spring Boot application into `GET /*`, and OpenTelemetry already names a server span `METHOD route` or just `METHOD`.
A span whose endpoint name matches `spidersense.ignore.endpoints` (design.md) is stored with `entry` false and no endpoint: it is in its trace and in `/api/traces`, and in nothing that counts requests.
`endpointId` is the first 12 hex characters of the SHA-256 of `service + " " + name`; `queryId` hashes `service\0system\0statement` the same way, and `errorId` hashes `service\0rootCauseType\0frame` (the frame without its file position and with `$<digits>` made `$?`), or `service\0type\0normalisedMessage` when the trace has no application frame (design.md).

### Callers and error endpoints

`QueryStats.callers[].endpoint` and `ErrorGroup.endpoints[].name` are the nearest **entry span up the parent chain within the trace**, not the trace's root.
A query issued by the second service of a two-service trace is therefore attributed to that service's own endpoint.
A span whose chain leaves the window is reported as `(no endpoint)`.

### Nulls in series

Every duration that has no value is `null`, never `0`: a percentile in a bucket with no requests, the first point of a `rate=true` series, an estimated `p95` for a histogram point that carries no buckets, or whose one bucket has no bound (a Micrometer timer bridged by the agent).
`jvm.nonHeap` has no `limit` array and `cpu.systemLoad1m` is an empty array on a platform that reports no load average.

### Ordering and sorting

`GET /api/metrics` is sorted by metric name.
`GET /api/endpoints` and `GET /api/services/{name}.endpoints` are sorted by total time descending; `queries` defaults to `sort=total`; `errors` is sorted by count descending.
Lists paged with `before` are newest first, with the row id breaking a tie inside the same millisecond.

### Free-text search

`q` on `/api/traces` matches, case-insensitively, any span name or any part of the span's attribute JSON in that trace (storage.md's `LOWER(name) LIKE ? OR LOWER(attributes) LIKE ?`).
Span **event** attributes — notably `exception.message` — are not searched; search an error's endpoint or type instead, or use `/api/errors`.
`q` on `/api/logs` matches the body and the attribute JSON.

### Metrics

`step` on `/api/metrics/series` is accepted and ignored: the exporter's interval already sets the resolution, and resampling a second time blurs the spike the chart is being read for.
`rate=true` only changes `v` for a monotonic sum; other types are unaffected.
An exponential histogram is stored as a histogram with count, sum, min and max only, so its `p95` is `null`.
`runtime.jvm` is `process.runtime.name + " " + process.runtime.version`, falling back to `process.runtime.description`.
`GET /api/jvm` without `service` answers for the first service that sent any `jvm.*` metric, which locally is usually the only one.

### Errors

An error group is the root-cause type and the innermost application frame of the stack trace, or the type and the normalised message when there is no application frame (design.md).
`ErrorGroup.message` is the normalised message (digits become `?`, quoted strings become `'?'`); `sample.message` is one real message as received.
`ErrorGroup.type` is the exception type, else `error.type`, else the literal `error`.
Both are the outer exception's, as the span recorded it; the members of a group grouped by frame may differ in them, and the group shows the greatest of each.

### Status and ingest

`/api/status.storage.path` is `null` and `sizeBytes` is `0` for an in-memory database (the fallback, and the one the tests use).
`counts.spans` counts stored spans, so spans dropped by the self-monitoring rule are not in it.
That rule drops only `SERVER` spans on the server's own port; a `CLIENT` span calling that port is an application genuinely talking to Spider Sense and stays in its trace.
A request body that is neither `application/x-protobuf` (also accepted as `application/protobuf`) nor `application/json` is `415`; an undecodable body of an accepted type is `400`.
OTLP/JSON ids are accepted as hex (what the OTLP specification says) and as base64 (what protobuf's own JSON mapping produces); the two are told apart by length, since only a hex id is exactly 32 or 16 characters of `[0-9a-f]`.

### Apdex and the histogram

The bucket bounds come from the server's `slowRequestMs`, not from the client, so every page and every service share one scale and the legend can be built from `/api/status`.
An error request is counted in the fifth histogram slot only, whatever its duration, so a failing endpoint that answers fast still lowers the Apdex.
`apdex` is `null`, never `0`, when nothing was requested.

### Tingles

Tingles are rows in the `tingle` table, so `/api/overview.tingles` survives a restart and is visible to every Spider Sense process sharing the database; design.md's in-memory mirror of the last 500 is not kept, as it would be a second source of truth for the same list.
An error tingle is raised for an entry span that failed and for any other span carrying an exception event of its own, so a failure that propagated up a trace does not produce one tingle per frame.
`slow-query` uses the span's summary as its `title` and the statement as its `detail`.
