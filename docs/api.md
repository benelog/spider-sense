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
  "thresholds": { "slowRequestMs": 500, "slowQueryMs": 100 },
  "retention": { "spans": 200000, "logs": 50000, "metricPoints": 2000 },
  "counts": { "spans": 12345, "traces": 2345, "logs": 456, "metricSeries": 78, "services": 2 },
  "oldest": { "span": 1758000000000, "log": 1758000000000 }
}
```

`DELETE /api/data` → `204`. Clears every store (services stay registered).

`GET /api/export?from&to&service&traceId` → `application/json` download (`Content-Disposition: attachment`) of `{ "traces": [<trace as in GET /api/traces/{id}>...] }`. `traceId` alone exports one trace.

## Live events

`GET /api/events` → `text/event-stream`. Events:

```
event: tingle
data: {"kind":"slow-request"|"slow-query"|"error","at":1758000000000,"service":"spring-orders","title":"GET /orders/report","detail":"1,532 ms","traceId":"...","spanId":"...","durationMs":1532.4}

event: stats
data: {"at":1758000000000,"spans":12345,"traces":2345,"logs":456,"perSecond":{"spans":12.5,"logs":3.0}}

event: service
data: {"name":"spring-orders","firstSeen":1758000000000}
```

`stats` is sent at most every second while data arrives, and a `: keepalive` comment every 15 s.

## Overview

`GET /api/overview?from&to`

```json
{
  "window": { "from": 1758000000000, "to": 1758000900000, "bucketMs": 15000 },
  "totals": { "requests": 1200, "errors": 12, "errorRate": 0.01, "rps": 1.33, "p50Ms": 12.1, "p95Ms": 210.4, "p99Ms": 840.0, "maxMs": 1532.4 },
  "services": [ <ServiceSummary> ],
  "tingles": [ <Tingle> ],            // newest first, at most 50, within window
  "series": {                          // one point per bucket, aligned across arrays, oldest first
    "t": [1758000000000, ...],
    "requests": [12, ...],
    "errors": [0, ...],
    "p95Ms": [120.5, ...]              // null where the bucket is empty
  }
}
```

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
  "series": { "t": [...], "requests": [...], "errors": [...], "p50Ms": [...], "p95Ms": [...], "p99Ms": [...] },
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
  "statusCodes": { "200": 118, "500": 2 } }
```

`GET /api/endpoints?from&to&service` → `{ "endpoints": [ <EndpointStats> ] }` across services, sorted by total time desc.

`GET /api/endpoints/{endpointId}?from&to` → `{ "endpoint": <EndpointStats>, "series": {...as service series...}, "queries": [<QueryStats>], "errors": [<ErrorGroup>], "traces": [<TraceSummary> x 20 slowest], "recent": [<TraceSummary> x 20 newest] }`.

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

`GET /api/traces/{traceId}`

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

## XLog (Scouter-style scatter)

`GET /api/xlog?from&to&service&endpointId&limit=5000`

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
  "lastSeen": 1758000900000 }
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

`GET /api/errors/{errorId}?from&to` → `{ "error": <ErrorGroup>, "series": { "t": [...], "count": [...] }, "traces": [<TraceSummary> x 20 newest] }`.

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
  "classes": { "t": [...], "loaded": [...] }
}
```

## Static UI

`GET /` and every path without an `/api/` or `/v1/` prefix that has no file: the UI's `index.html` (the router is hash-based, so this is mostly `/`).
`GET /assets/**`: files under `public/assets`.
