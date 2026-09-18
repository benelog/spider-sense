# Spider Sense: the agent interface

Spider Sense is built for the local development loop, and today that loop is often driven by an AI coding agent: change the code, run the application, hit a few endpoints, read what happened, change the code again.
This document specifies what Spider Sense offers that loop.
[design.md](design.md) is still the architecture, [api.md](api.md) the wire contract and [storage.md](storage.md) the tables; this document says what the agent-facing pieces mean and why they have the shape they have.

## What an agent needs, and what a dashboard does not give it

A person reads a scatter and sees the cluster.
An agent reads text, pays for every token, and needs a verdict it can act on.
Four things follow, and every interface below is built on them:

1. **Findings, not charts.** The primary answer is a ranked, bounded list of things worth fixing, each with the numbers that justify it, the trace ids that prove it, and the code location when one is known. The UI's series and buckets stay for people.
2. **Text first.** Every agent-facing answer has a Markdown rendering beside the JSON one. A trace is an indented tree, a list is a table, and repeated siblings are collapsed. The text is deterministic: the same data renders to the same bytes, so two answers can be diffed.
3. **Before and after.** The agent's question after a change is "did it help". Marks name a moment; compare puts the window before a mark beside the window after it, endpoint by endpoint, query by query.
4. **A verdict with an exit code.** `check` turns thresholds into pass or fail, so the agent can use Spider Sense the way it uses a test.

Everything is served by the same `Queries` the UI uses, so the numbers an agent quotes are the numbers a person sees on screen.

## Interfaces

| Interface | Status | When it is the right one |
|---|---|---|
| HTTP API, `format=text` | this version | anything that can run `curl` |
| CLI, `java -jar spider-sense.jar <command>` | this version | Claude Code and every agent with a shell; also works when no Spider Sense is running, straight from the H2 file |
| Skill, `skills/spider-sense/` | this version | teaches an agent the loop itself: how to start the app under the agent, mark, exercise, read findings, fix, compare, check |
| Read-only SQL, `POST /api/sql` and `sql` | this version | the question nobody anticipated; the schema in storage.md is already the documentation |
| MCP, `POST /mcp` and `mcp` | this version | hosts without a shell; six tools over the same handlers, answering the same text ([MCP](#mcp)) |

### Choosing an interface

The CLI and MCP call the same handlers and print the same bytes, so the choice costs nothing in what is answered; it is decided by what the host can reach.

| Host | Use | Why |
|---|---|---|
| An agent with a shell (Claude Code, Codex CLI, Gemini CLI, Aider, a script) | the CLI and the skill | no setup beyond `init`; works with the application down; `check` is an exit code; output can be piped; the tool costs no context |
| A host without a shell (Claude Desktop, a browser-based agent, an IDE chat panel) | MCP | the only case the CLI cannot serve; `init --mcp` writes the host's server entry |
| CI, a build gate | the CLI's `check`, or the Gradle plugin's task | an exit code is what a build understands |
| The application has crashed | the CLI, or MCP over stdio | both open the H2 file in process; MCP over HTTP needs the server up |

Do not enable both in one host: two tools that give the same answer make the model choose between them and cost the schema twice.
The skill teaches the loop for the CLI; the MCP server's `instructions` field carries the same loop in one paragraph, so neither host is taught something the other is not.

## Time selectors

Every agent-facing endpoint takes `since` and `until` instead of `from` and `to`, because an agent thinks in "since I changed the code", not in epoch milliseconds.
A selector is one of:

| Form | Example | Meaning |
|---|---|---|
| duration | `30s`, `5m`, `2h`, `1d` | that long before `until` (for `since`) or before now (for `until`) |
| epoch milliseconds | `1758000000000` | the instant, 13 or more digits |
| mark name | `before`, `after-fix` | the newest mark with that name |
| `start` | `start` | the newest automatic start mark, of `service` when one is given |
| `now` | `now` | now; the default for `until` |

The default `since` is `15m`.
A `since` that resolves to a moment after `until` is a `400`.
A mark name that matches no mark is a `404` naming it.

The `from`/`to` parameters of the UI endpoints still work everywhere and win when both are given.

## Marks

A mark is a named moment: `before`, `after-fix`, `v2`.
It is a row in the `mark` table (storage.md), shared like everything else, swept with the retention.

`POST /api/marks` with `{ "name": "before", "note": "…", "service": "…", "at": … }` creates one; `name` is required and matches `[A-Za-z0-9._-]{1,64}`, the rest is optional and `at` defaults to now.
`GET /api/marks` lists the newest 50.

**Automatic start marks.** When the writer sees a service with a `process.pid` it has not stored for that service, it inserts a mark named `start` with that service and the note `pid <pid>`.
So `since=start` means "since the application was last restarted", which is what an agent that just rebuilt the application wants, and it needs no cooperation from anyone.

## Findings

`GET /api/findings?since&until&service&limit=20&format=text|json`

A finding is one thing worth fixing, found by rules over the window.
Findings are ranked by severity (`high` before `medium` before `low`; no rule produces `low` today, the value is reserved for gentler kinds), then by kind in the order of the table below, then by impact within a kind, then by id, so the list is stable between two calls over the same data.
`limit` defaults to 20 and is at most 100.

| Kind | Rule | Severity | Impact |
|---|---|---|---|
| `error` | an error group (api.md) with at least one occurrence in the window | `high` | count |
| `n-plus-one` | in one trace, the same query group runs 5 or more times under the same entry span; aggregated per (endpoint, query group) over the window | `high` when the repeats reach 20 or their summed time exceeds `slow.request.ms`, else `medium` | affected requests × median repeats |
| `slow-query` | a query group whose p95 exceeds `slow.query.ms` | `high` when p95 exceeds ten times the threshold, else `medium` | total time |
| `slow-endpoint` | an endpoint whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold (the "frustrated" bound of the Apdex), else `medium` | total time |
| `slow-job` | a job (a root `INTERNAL` span, design.md: a scheduled method, an `@Async` call, a batch step), grouped by `(service, span name)`, whose p95 exceeds `slow.request.ms` | as `slow-endpoint` | total time |
| `slow-external` | an outbound HTTP call: `CLIENT` spans of category `http`, grouped by `(service, target, span name)` where `target` is the dependency target of api.md (`localhost:8081`), whose p95 exceeds `slow.request.ms` | as `slow-endpoint` | total time |
| `log-error` | log records of severity `ERROR` or above, grouped by `(service, logger, message normalised as an error message is)`, counting only the records that are **uncovered**: without a trace id, or with a trace id whose trace has no error span; a group with at least one uncovered record in the window | `high` | uncovered count |
| `pool-exhausted` | a JDBC pool with a point in the window where pending requests are above zero, or used equals max | `high` | pending, then used |
| `gc-pause` | one collector (`jvm.gc.duration` per `jvm.gc.name` and `jvm.gc.action`) with a point in the window whose longest single collection (`max`) is at least `slow.request.ms`, or whose collections summed over the export interval take at least 10% of that interval | `high` for a single collection over the threshold, else `medium` | the longest collection |
| `heap-pressure` | heap `jvm.memory.used` (summed over the heap pools) at 90% or more of the heap `jvm.memory.limit` at any point in the window | `high` | the highest ratio |
| `thread-growth` | `jvm.thread.count` at the last point of the window is at least 50 above the first point, or at least twice it when the first point is 20 or more | `medium` | last minus first |

A `log-error` is what `catch (Exception e) { log.error(…, e); return fallback; }` leaves behind: no span error, no exception event, one line in the log.
A record whose trace has an error span is already reported by an `error` finding and is not counted twice.
The three JVM kinds read the same `metric_point` series the JVM page draws, so a run that is slow because it is collecting or swapping is named for what it is instead of producing `slow-endpoint` findings that point at the wrong thing.

Each finding carries:

```json
{
  "id": "n-plus-one:1a2b3c4d5e6f",          // kind + 12 hex of SHA-256 over (kind, service, subject); stable across windows
  "kind": "n-plus-one",
  "severity": "high",
  "service": "spring-orders",
  "title": "GET /orders/{id} runs SELECT order_line 42 times per request",   // one line, no numbers a person would not say aloud
  "why": "3 of 3 requests repeated it; 42, 42 and 41 times; 38.2 ms per request in that statement",
  "subject": { "endpointId": "…" | null, "queryId": "…" | null, "errorId": "…" | null, "pool": "…" | null, "job": "…" | null,
               "target": "…" | null, "logger": "…" | null, "jvm": "gc:G1 Young Generation" | "heap" | "threads" | null },
  "numbers": { … },                         // kind-specific, listed below
  "statement": "SELECT … FROM order_line WHERE order_id = ?" | null,
  "code": [ "orders.OrderService.load(OrderService.java:41)" ],   // application frames, most specific first, at most 5; empty when none is known
  "traces": [ "4bf92f3577b34da6a3ce929d0e0e4736", … ]              // at most 3, the evidence
}
```

`numbers` per kind:

- `error`: `count`, `firstSeen`, `lastSeen`, `type`, `message` (normalised), `endpoints` (name and count, as api.md's `ErrorGroup.endpoints`).
- `n-plus-one`: `requests` (entry spans of the endpoint in the window, as design.md defines an entry span), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).
- `slow-query`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as api.md's `QueryStats.callers`).
- `slow-endpoint`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1: the part of the endpoint's total time spent in database spans of the same trace and service), `hotSpan`.
- `slow-job`: `runs`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `dbCallsPerRun`, `dbMsPerRun`, `dbShare`, the same measures as `slow-endpoint` over the job's runs, `hotSpan`; `subject.job` is the span name and the title is `<job> is slow`.
- `slow-external`: `calls`, `errors`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as `slow-query`'s: the entry spans up the chain); `subject.target` is the target and the title is `GET localhost:8081 is slow` (the span name, then the target).
- `log-error`: `count` (uncovered records), `firstSeen`, `lastSeen`, `logger`, `message` (normalised), `endpoints` (the entry span of each record's trace, as `error`'s `endpoints`; `(no endpoint)` for a record without one); `subject.logger` is the logger and the title is `ERROR in <logger short name>: <message cut at 80>`.
- `pool-exhausted`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).
- `gc-pause`: `gc`, `action`, `worstMs` (the longest single collection), `shareMax` (0..1, the worst interval's collection time over its length), `collections` (over the window), `at`; `subject.jvm` is `gc:<name>` and the title is `<gc> paused for <worstMs>`.
- `heap-pressure`: `usedMax`, `limit`, `ratioMax` (0..1), `at`; `subject.jvm` is `heap` and the title is `heap at <ratio>% of its limit`.
- `thread-growth`: `first`, `last`, `max`, `at` (the last point); `subject.jvm` is `threads` and the title is `threads grew from <first> to <last>`.

`hotSpan` is where the time went in the finding's first evidence trace: `{ "name": "<summary>", "category": "db" | "http" | "internal" | …, "selfMs": 312.4, "share": 0.62 }`, the span with the largest self time (its duration minus the durations of its direct children, never below zero, as the Profile view computes it) and that self time's share of the trace's duration; `null` when the finding has no trace.
It is the first clue when `dbShare` is low, and in the text rendering it is one line, `hot span: <name> · <selfMs> self · <share>`.

`traces` are the three slowest traces for `slow-*`, the three newest for `error` and `log-error` (records with a trace id), the three most recent affected for `n-plus-one`, none for `pool-exhausted` and the JVM kinds.
A job is never a request: it is not in `requests`, not in the Apdex and not in `check`; `slow-job` is the one place a slow scheduler tick or batch step is reported.
An endpoint that `spidersense.ignore.endpoints` excludes (design.md) is not an entry span and produces no finding of any kind.

**Code locations.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes from what it does record: the `exception.stacktrace` of an error, and the `code.function`/`code.namespace` attributes of the few instrumentations that set them.
A stack trace is reduced to its application frames: frames whose package is not one of the framework prefixes below, at most 5, innermost first.
`spidersense.app.packages=com.acme,org.acme` (a comma-separated list) replaces the heuristic with an allowlist.

Framework prefixes dropped by default: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.eclipse.jetty.`, `org.apache.`, `io.opentelemetry.`, `com.zaxxer.`, `org.h2.`, `net.benelog.spidersilk.`, `kotlin.`, `scala.`, `reactor.`, `io.netty.`, `ch.qos.logback.`, `org.slf4j.`, `org.junit.`, `gg.jte.`.

Slow queries, repeated queries and slow outbound calls have a code location too, and it is the one thing Spider Sense collects itself: its OpenTelemetry extension ([design.md](design.md#the-extension)) sets `code.stacktrace` on every database span that ran at least `slow.query.ms`, on the fifth repeat of a statement within one trace, and on every non-database `CLIENT` span that ran at least `slow.request.ms`, so `slow-query`, `n-plus-one` and `slow-external` findings carry `code` just as an error does.
A `log-error` finding takes its `code` from the `exception.stacktrace` attribute of the group's newest record, when the logging bridge exported one.
Those frames are the truest of the three, because they are the span's own thread at the moment the statement finished, not a guess from an attribute; they are reduced by the same rules as `exception.stacktrace` above.
An `n-plus-one` finding takes its `code` from the span of the repeated group that carries `code.stacktrace`, which is the fifth repeat, and falls back to the group's newest span when none does (an application run without the extension).
A `slow-job` finding takes its `code` from the `code.function` and `code.namespace` attributes the scheduling instrumentations set on the job's span.

## Acknowledgements

A finding that is known and accepted — a report endpoint that is slow by design, a query that will stay slow until the schema changes — sits at the top of every `findings` answer and hides the new problem under it.
An acknowledgement takes it out of the way without hiding it: finding ids are stable across windows ([Findings](#findings)), so it is one row, `(finding_id, at, note)` in the `ack` table (storage.md), kept until it is withdrawn or the data is cleared.

- `POST /api/findings/{id}/ack` with `{ "note": "…" | null }` → `201` `{ "findingId": "…", "at": …, "note": … }`; acknowledging again replaces the row.
- `DELETE /api/findings/{id}/ack` → `204`, or `404` when there is no such acknowledgement.
- `GET /api/acks` → `{ "acks": [ … ] }`, newest first.

Every finding carries `"ack": { "at": …, "note": "…" | null } | null`.
Acknowledged findings are ranked after every other finding, in the same order among themselves; `hideAcked=true` on `/api/findings` (and on the MCP `findings` tool) leaves them out.
In the text rendering the severity column reads `acked` for an acknowledged finding, and the heading counts them: `(… 12 requests, 2 acked)`.
`check` does not look at acknowledgements: its rules are explicit thresholds, and an acknowledged `n-plus-one` is still an N+1 to `maxNPlusOne`.

The CLI commands are `ack <finding id> [--note=…]`, `unack <finding id>`, and `findings --hide-acked`; the id is what `findings` printed.
Both write in the direct-file path as `mark` does.

## Trace diff

`GET /api/traces/{a}?diff={b}` and `trace <a> --diff=<b> [--full]`: the two span trees aligned by structure and rendered as one text with a gutter, so the question after a fix — which span went away, which one got slower — is answered without reading two trees.

The alignment works on the lines of the single-trace rendering above, minus their timing: each span becomes the key `(depth, category, summary with every run of digits replaced by one ?)`, so `/api/books/155` and `/api/books/87` are the same line, a collapsed group keeps its summary and drops its count, and the two key sequences are aligned by their longest common subsequence (each side is cut at 2,000 lines).
A matched line is `=`, a line only in `b` is `+`, a line only in `a` is `-`.
Statement, exception and log lines are not diffed: a matched span shows its statement or exception when either side has one, as the single rendering would.

```
# trace diff 4bf92f3577b34da6a3ce929d0e0e4736 → 09e96c4c6db157e690716c2615ffd146  312.4 ms → 41.2 ms  (−271.2 ms, −86.8%)  48 → 12 spans

   a          b          span
=  312.4 ms   41.2 ms    SERVER spring-orders GET /api/orders/{id}/enriched → 200
=  0.5 ms     0.4 ms       INTERNAL OrderRepository.findById
-  38.2 ms    —            db SELECT product  × 42
+  —          2.1 ms       db SELECT product  × 1
=  9.1 ms     8.7 ms       CLIENT GET http://localhost:8081/api/books/155 → 200
```

- The heading carries both ids, both durations, the delta in ms and in percent of `a`, and both span counts.
- Columns are the gutter, `a`'s duration, `b`'s duration (`—` where the side has no such span), then the span line indented by depth as in the single rendering; a collapsed group prints `× n` per side on its own line as above, so a count that changed is two lines, `-` and `+`.
- `--full` expands collapsed groups on both sides before aligning.
- JSON: `{ "a": "<id>", "b": "<id>", "durationMs": { "a": …, "b": … }, "spans": { "a": 48, "b": 12 }, "lines": [ { "op": "=" | "+" | "-", "depth": 1, "summary": "…", "category": "…", "aMs": … | null, "bMs": … | null, "count": { "a": 42, "b": null } | null } ] }`.
- Either id unknown is a `404` naming it, exit code `4` in the CLI.

## Tail

`tail [--kind=slow-request|slow-query|error] [--service=<name>] [--until-traces=<n>] [--timeout=<duration>] [--json]`

The CLI's window on `GET /api/events`: one line per `tingle` event as it arrives, so an agent that has just sent a request can watch it land instead of sleeping and asking `findings` again.

```
12:37:28.565  slow-query    spring-orders   SELECT orders  1,532 ms  2519b548daad800090e8f56de6a5a62a
12:37:29.077  error         silk-bookstore  GET /api/books/stats  ArithmeticException: / by zero  09e96c4c6db157e690716c2615ffd146
```

- Columns: local time with milliseconds, kind, service, title, detail, trace id; the tingle's own fields, in that order.
- `--kind` and `--service` filter, one value each; no filter means everything.
- `--until-traces=<n>` ends with exit `0` once the `stats` events have shown `n` new traces since the command started (`traces` on the `stats` event is the store's count); `--timeout=<duration>` (a selector duration, `30s`, `5m`) ends with exit `0` when it elapses; without either it runs until interrupted.
- `--json` prints the event's JSON object per line, with `"event": "tingle"` (or `"stats"`) added.
- It needs a running Spider Sense: there is no file to tail. Nothing at `--url` is a message on stderr and exit `2`.

## Export and import

`GET /api/export?since&until&service` (and `from`/`to`) without `traceId` exports the window: every service, span, log record, metric, metric series, metric point, tingle and mark, as one JSON document, streamed as it is read, with `Content-Disposition: attachment; filename="spider-sense-<from>-<to>.json"`.
With `traceId` the answer is the single-trace export of api.md as before.

```json
{ "spiderSense": { "version": "0.1.0", "schema": 4, "exportedAt": …, "window": { "from": …, "to": … }, "service": "…" | null },
  "services": [ { "name": "…", "language": "…", "pid": …, "firstSeen": …, "lastSeen": …, "resource": { … } } ],
  "spans": [ { …every column of the span table except id, attributes and events as the objects they are… } ],
  "logs": [ { …every column of log except id… } ],
  "metrics": [ { "name", "type", "unit", "description", "monotonic", "temporality" } ],
  "metricSeries": [ { "id": 7, "service", "name", "attributes": { … } } ],
  "metricPoints": [ { "seriesId": 7, "atMs", "value", "count", "sum", "min", "max", "buckets": { … } | null } ],
  "tingles": [ { …every column except id… } ],
  "marks": [ { "atMs", "name", "service", "note" } ] }
```

Every key is its column's name in camelCase, so a section is the table it came from and an import binds it straight back: `at_ms` is `atMs`, `start_ms` is `startMs`, `parent_span_id` is `parentSpanId`.
Nothing is derived on the way out or recomputed on the way in — `entry`, `slow`, `queryId` and the rest travel as they were stored, because a document exported from one session must not change meaning under another machine's thresholds.

`POST /api/import` takes that document (`Content-Encoding: gzip` accepted) and answers `200` `{ "spans": n, "logs": n, "metricPoints": n, "tingles": n, "marks": n, "skippedTraces": n, "window": { "from": …, "to": … } }`.
Import keeps every timestamp as exported, so the reader sets the time range to the answer's `window` (or `all`).
It is idempotent enough for a file imported twice: a trace whose id already has rows in the store is skipped whole (its spans, logs and tingles; `skippedTraces` counts it), a metric point is merged on its `(series, at)` key with the series looked up or created by `(service, name, attributes)`, a service row is merged, and a mark is skipped when one with the same name and instant exists.
A document whose `schema` is not this version's is a `400` naming both versions.
Acknowledgements are not exported: they are the reader's, not the session's.

The CLI commands are `export [--since=… --until=… --service=…] [--out=<file>]` and `import <file> [--url=… | --db=…]`: `export` writes to stdout or to `--out`, gzipped when the name ends in `.gz`; `import` posts to the running Spider Sense, or, when none answers or `--db` names a file, writes into the file in process through the same code as the server, and prints one line, `imported 12,345 spans, 456 logs, 7,890 metric points, 12 tingles, 3 marks (2 traces already present) from 2026-09-18T12:37:06+09:00 → 12:41:08`.

## Compare

`GET /api/compare?before=<selector>&after=<selector>&until=<selector>&service=&format=`

Two windows: **before** is `[before, after)` and **after** is `[after, until)`, `until` defaulting to now.
The usual use is two marks: `mark before`, exercise, change the code, `mark after`, exercise, `compare --before=before --after=after`.

```json
{
  "before": { "from": …, "to": … }, "after": { "from": …, "to": … },
  "totals": { "before": <Totals>, "after": <Totals> },          // api.md's Totals
  "endpoints": [ { "endpointId": "…", "service": "…", "name": "GET /orders/{id}",
                   "before": <Side> | null, "after": <Side> | null, "verdict": "better" | "worse" | "same" | "new" | "gone" } ],
  "queries":   [ { "queryId": "…", "service": "…", "statement": "…",
                   "before": <QuerySide> | null, "after": <QuerySide> | null, "verdict": … } ],
  "errors":    [ { "errorId": "…", "service": "…", "type": "…", "message": "…", "before": 0, "after": 3, "verdict": … } ]
}
```

`Side` is `{ "calls", "errors", "p50Ms", "p95Ms", "maxMs", "dbCallsPerRequest", "dbMsPerRequest" }`; `QuerySide` is `{ "calls", "callsPerRequest", "p95Ms", "totalMs" }` where `callsPerRequest` divides by the entry spans of the window (of the service when one is given), as design.md defines an entry span.

Verdicts, in this order:

- `new` when only `after` has data; `gone` when only `before` has.
- `worse` when errors appear or grow, or when p95 (calls per request for a query) grows by more than 20% and by at least 10 ms (0.5 calls).
- `better` when the same shrinks by that much, or errors disappear.
- `same` otherwise.

Endpoints are sorted worst first: `worse`, then `new`, `same`, `better`, `gone`, each by total time descending.
Queries and errors the same way.

## Check

`GET /api/check?since&until&service&endpoint&…rules…&format=`

Rules are query parameters; every rule given is evaluated, and when none is given the default set is `maxErrors=0`, `maxNPlusOne=0` and `maxP95Ms=<slow.request.ms>`.

| Rule | Actual value |
|---|---|
| `maxP95Ms` | the highest p95 of any endpoint in scope |
| `maxErrors` | error groups' occurrences summed |
| `maxErrorRate` | failed entry spans over entry spans |
| `maxQueriesPerRequest` | database spans per entry span, the highest of any endpoint |
| `maxSlowQueries` | query calls over `slow.query.ms` |
| `maxNPlusOne` | `n-plus-one` findings |
| `maxLogErrors` | `log-error` findings' uncovered records summed |
| `minApdex` | the Apdex over the scope |

`endpoint` narrows the scope to one endpoint, by `endpointId` or by name (`GET /orders/{id}`).
`requests` counts the entry spans in scope, as design.md defines an entry span, so a seeder's or a scheduler's root spans never make a verdict of their own.

```json
{
  "pass": true | false | null,               // null when there was no request in scope
  "requests": 12,
  "reason": "no requests in the window" | null,
  "checks": [ { "rule": "maxP95Ms", "limit": 500, "actual": 812.4, "pass": false,
                "detail": "GET /orders/report p95 812.4 ms over 3 calls" } ]
}
```

## SQL

`POST /api/sql` with `{ "sql": "SELECT …", "limit": 200 }`

The escape hatch, for the question nobody anticipated.
Findings, compare and check answer what Spider Sense knows to look for; everything else it holds is already documented by the schema in [storage.md](storage.md), and this runs one statement over it.
`format=text` as a query parameter, or an `Accept` whose first type is `text/markdown` or `text/plain`, selects the Markdown rendering, exactly as on the endpoints above.

```json
{
  "columns": ["ENDPOINT", "STATEMENTS"],
  "rows": [ ["GET /orders/{id}", 42], ["GET /orders", 7] ],
  "rowCount": 2,
  "truncated": false,
  "elapsedMs": 3
}
```

`columns` are the labels H2 gives them, which upper-cases an unquoted name (`SERVICE`, `SPANS`), and a value is a JSON number, string, boolean or null, as the store holds it: every instant in the schema is epoch milliseconds and stays a number, and an H2 `TIMESTAMP` a statement computes itself is an ISO string.
`limit` defaults to 200 and is capped at 5000; a `limit` below 1 is a `400`.
One row more than the limit is fetched, so `truncated` reports whether the cap cut the answer off rather than guessing at it.

**Read-only, three layers.**
Each catches what the one before it cannot, and the second is the one that holds:

1. **A statement allowlist.** After the leading whitespace and the `--` and `/* */` comments are stripped, the first keyword must be `SELECT`, `WITH`, `TABLE`, `VALUES`, `EXPLAIN` or `SHOW`, and there must be one statement: a `;` followed by anything but whitespace is refused. String literals and comments make this a scanner over SQL text rather than a parser, which is exactly why it is not the layer that is trusted.
2. **A user with `SELECT` and nothing else.** The schema creates `spider_sense_reader`, idempotently, on every open by a server of this version, and grants it `SELECT` on schema `PUBLIC` (storage.md); the statement runs on a connection opened as that user, with the same JDBC URL. So H2 itself refuses `INSERT`, `UPDATE`, `DELETE`, `DROP` and `ALTER` with "Not enough rights for object", and the administrator-only functions `FILE_WRITE`, `CSVWRITE`, `FILE_READ`, `LINK_SCHEMA` and `RUNSCRIPT` with "Admin rights are required for this operation", whatever the layer above thought it read.
3. **The limits of the connection.** `setMaxRows(limit + 1)`, a query timeout of 10 seconds, `setReadOnly(true)`, autocommit off, and a `rollback()` whatever happens.

**Errors.** A statement that is not allowed is a `400` with `{ "error": "…" }` naming the reason, and a statement H2 refuses or cannot parse is a `400` carrying H2's own message.
In the text rendering an error is that message on one line, because an agent that asked for Markdown should not have to parse a JSON object it did not expect.

The text rendering is a heading and a table:

```
# sql  2 rows

| ENDPOINT | STATEMENTS |
| --- | --- |
| GET /orders/{id} | 42 |
| GET /orders | 7 |
```

The heading carries ` (truncated at 200)` when the cap cut the rows off.
The conventions below hold here too: nothing in the body depends on when it was rendered (`elapsedMs` is in the JSON only), a cell is cut at 200 characters with `…` unless `full=true`, a `|` in a cell is escaped and its newlines collapse to spaces, and a SQL `NULL` is `—`.
Numbers are printed as the store holds them, without a thousands separator, because a value an agent passes back into the next statement has to survive the round trip.

The CLI command is `sql "<statement>" [--limit=200]`.
Its direct-file path opens the reader connection the same way, and a database an older Spider Sense created has no reader user in it yet; that is `the database has no read-only user yet; start an application or the standalone server with this version first` on stderr, and exit code 2.

## MCP

For a host that has no shell.
The server speaks the Model Context Protocol in two transports, and both are the thinnest possible adapter over [Reports](api.md#agent-facing-endpoints): a tool call is one `Reports` call and its result is the text rendering below, so an MCP answer and a CLI answer over the same window are the same bytes.

**Transports.**

- **Streamable HTTP**: `POST /mcp` on the server's own port, one JSON-RPC 2.0 message per request, `Content-Type: application/json` both ways. A request is answered with `200` and the JSON-RPC response; a notification with `202` and no body. The server is stateless: no session id is issued or required, `GET /mcp` is `405`, and a JSON array (the batch of older revisions) is refused with `-32600`.
- **stdio**: `java -jar spider-sense.jar mcp [--url=<base>] [--db=<path>]`, newline-delimited JSON-RPC on stdin and stdout, nothing else on stdout, diagnostics on stderr; it ends at end of input. `initialize`, `ping` and `tools/list` are answered in process. A `tools/call` goes to the Spider Sense at `--url` (the CLI's default and `SPIDERSENSE_URL` apply) when one answers, else the H2 file is opened in process exactly as the CLI does, with the same stderr line saying so; `--db` reads the file without asking. This is the transport for a host on the same machine, and the one that still answers after the application has crashed.

**Methods.** `initialize` answers the client's `protocolVersion` when it is one the server knows (`2025-06-18`, `2025-03-26`, `2024-11-05`) and `2025-06-18` otherwise, `capabilities: { "tools": {} }`, `serverInfo: { "name": "spider-sense", "version": "<version>" }`, and `instructions`, the loop in one paragraph: start the application under the agent, `mark`, exercise, `findings`, fix, `mark`, `compare`, `check`. `notifications/initialized` is accepted and ignored. `ping` answers `{}`. `tools/list` is the six tools below; `tools/call` runs one. Anything else is `-32601`.

**Tools.** Every argument is the CLI option of the same meaning; a selector is a string as in [Time selectors](#time-selectors).

| Tool | Arguments | Answers |
|---|---|---|
| `findings` | `since`, `until`, `service`, `limit` (1–100), `full`, `hideAcked` | the findings text |
| `trace` | `traceId` (required), `full`, `diff` | the trace tree, or the two traces aligned when `diff` names a second one ([Trace diff](#trace-diff)) |
| `mark` | `name` (required, `[A-Za-z0-9._-]{1,64}`), `note`, `service` | the mark, as the CLI prints it |
| `compare` | `before`, `after` (both required), `until`, `service` | the compare text |
| `check` | `since`, `until`, `service`, `endpoint`, `maxP95Ms`, `maxErrors`, `maxErrorRate`, `maxQueriesPerRequest`, `maxSlowQueries`, `maxNPlusOne`, `maxLogErrors`, `minApdex` | the check text; `structuredContent` carries `{ "pass": true \| false \| null, "requests": n }` so a host need not read the heading for the verdict |
| `sql` | `sql` (required), `limit` (1–5000) | the sql text |

A result is `{ "content": [ { "type": "text", "text": "<the Markdown>" } ] }`.
What the CLI reports on stderr with exit code `4` (no such trace, no such mark) or as a `400` (a refused statement, a bad selector) is a tool result with `isError: true` whose text is that message on one line; a missing required argument or an unknown tool is a JSON-RPC `-32602`.
Tool descriptions are one sentence each and say when to use the tool, not how the output looks; the output is the text rendering and needs no description.

**`init --mcp`** writes the stdio server into the project's `.mcp.json` ([init](#init)) as `mcpServers.spider-sense = { "command": "java", "args": ["-jar", "<jar>", "mcp"] }`, keeping every other entry of an existing file.
A host that reaches the server over HTTP is configured by hand with `{ "type": "http", "url": "http://127.0.0.1:4000/mcp" }`.

## Text rendering

Any endpoint listed here answers Markdown when asked with `format=text` or with an `Accept` header whose first type is `text/markdown` or `text/plain`; the response is `text/markdown; charset=utf-8`.
JSON stays the default.

Endpoints with a text rendering: `/api/status`, `/api/findings`, `/api/marks`, `/api/compare`, `/api/check`, `/api/sql`, `/api/traces`, `/api/traces/{id}`, `/api/endpoints`, `/api/queries`, `/api/errors`, `/api/logs`, `/api/services`.

Every example below is output captured from `scripts/demo-shared.sh`, the two example applications running under the agent and forwarding to one standalone Spider Sense, with the home directory anonymised.

Conventions, so that the text is small and stable:

- The first line is a heading naming what it is and the window, in ISO-8601 with the local offset: `# findings  2026-09-18T12:37:06+09:00 → 12:41:08  (4m 1s, all services, 2456 requests)`; the range is rounded to the second and written in its largest units (`6s`, `2m 30s`, `15m`, `2h`).
- Lists are Markdown tables; ids are complete (a trace id is 32 hex characters, an endpoint, query or error id 12), because the agent will pass them back.
- Durations are milliseconds with one decimal and a thousands separator: `1,532.4 ms`; counts are integers; rates are percentages with one decimal.
- A statement is cut at 200 characters with `…`; `full=true` keeps it whole.
- Nothing in the body depends on when it was rendered, only on the window; `now` appears only in the heading.
- An empty result says what was looked for and where: `no findings since 2026-09-18T12:42:26+09:00 (20s, 16 requests)`, and, when there was no request at all, how to send some (the OTLP endpoint).

**A trace** is an indented tree, one span per line.
This one crosses both example applications, and the repeated `SELECT product` spans under the entry span are collapsed into one row that carries the statement:

```
# trace 09e96c4c6db157e690716c2615ffd146  2026-09-18T12:37:29.077+09:00  12.2 ms  spring-orders → silk-bookstore  20 spans, 10 db, 0 errors

offset     duration  span
0.0 ms     12.2 ms   SERVER spring-orders GET /api/orders/{id}/enriched → 200
0.7 ms     0.6 ms      INTERNAL OrderRepository.findById
0.7 ms     0.5 ms        INTERNAL Session.find orders.domain.Order
0.9 ms     0.1 ms          db SELECT orders
1.4 ms     0.0 ms      db SELECT order_line
1.6 ms     0.0 ms      db SELECT product  × 4, 0.0 ms avg, 0.0 ms total
                         select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=?
1.7 ms     0.0 ms      db SELECT customer
1.8 ms     0.1 ms      INTERNAL Transaction.commit
2.2 ms     2.7 ms      CLIENT GET http://localhost:8081/api/books/155 → 200
3.3 ms     1.0 ms        SERVER silk-bookstore GET /api/books/{id} → 200
3.7 ms     0.1 ms          db SELECT books
5.6 ms     3.1 ms      CLIENT GET http://localhost:8081/api/books/87 → 200
6.5 ms     2.0 ms        SERVER silk-bookstore GET /api/books/{id} → 200
7.2 ms     0.1 ms          db SELECT books
9.0 ms     2.4 ms      CLIENT GET http://localhost:8081/api/books/27 → 200
9.9 ms     0.9 ms        SERVER silk-bookstore GET /api/books/{id} → 200
10.3 ms    0.1 ms          db SELECT books
```

This one failed, so the span carries its exception and the application frames under it, and the trace's log lines follow:

```
# trace 2519b548daad800090e8f56de6a5a62a  2026-09-18T12:37:28.565+09:00  3.7 ms  spring-orders  1 spans, 0 db, 1 error

offset     duration  span
0.0 ms     3.7 ms    SERVER spring-orders GET /api/flaky → 500  [error]
                       exception IllegalStateException: Payment gateway timeout
                       orders.web.MiscController.flaky(MiscController.java:24)

logs (2)
12:37:28.565  WARN   orders.web.MiscController  Flaky endpoint failing this time: payment gateway timeout
12:37:28.566  ERROR  org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]  Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: java.lang.IllegalStateException: Payment gateway timeout] with root cause
```

- Two spaces of indentation per depth; the service is named only where it changes from the parent.
- Consecutive sibling spans with the same summary and category collapse after the third into one line with `× n`, the average and the total; `full=true` lists them all.
- A database span that is slow, or the first of a collapsed group, shows its statement on the next line; an error span shows its exception and the application frames.
- Logs of the trace follow, oldest first.

## CLI

```
java -jar spider-sense.jar <command> [arguments] [options]
```

The launcher treats a first argument that does not start with `-` as a command and hands the whole line to the CLI inside the nested server jar, through the same `SenseClassLoader` the embedded server uses; `--port=…` and the other standalone flags keep starting the server.
The launcher stays dependency-free.

| Command | Does |
|---|---|
| `status` | what is running, where the database is, how much it holds |
| `findings [--hide-acked]` | the findings of the window |
| `ack <finding id> [--note=…]`, `unack <finding id>` | acknowledges a finding, or withdraws that ([Acknowledgements](#acknowledgements)) |
| `trace <traceId> [--full] [--diff=<traceId>]` | one trace as a tree, or two aligned ([Trace diff](#trace-diff)) |
| `tail [--kind=] [--until-traces=] [--timeout=]` | tingles as they arrive ([Tail](#tail)) |
| `export [--out=<file>]`, `import <file>` | the window as one JSON document, and back ([Export and import](#export-and-import)) |
| `traces [--status=error\|ok] [--min-ms=] [--q=] [--limit=20]` | the newest traces |
| `endpoints`, `queries`, `errors` | the tables of the window |
| `logs [--severity=WARN] [--q=] [--trace=<traceId>]` | log lines |
| `mark <name> [--note=…]` | records a mark now |
| `marks` | lists marks |
| `compare --before=<selector> --after=<selector> [--until=<selector>]` | the two windows side by side |
| `check [--max-p95-ms=] [--max-errors=] [--max-error-rate=] [--max-queries-per-request=] [--max-slow-queries=] [--max-n-plus-one=] [--max-log-errors=] [--min-apdex=] [--endpoint=]` | pass or fail, in the exit code |
| `sql "<statement>" [--limit=200]` | one read-only statement over the schema of storage.md |
| `init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]` | writes the Spider Sense block into the project's `CLAUDE.md` and installs the skill into its `.claude/skills/`; `--mcp` also writes the stdio MCP server into its `.mcp.json` |
| `mcp` | the MCP server over stdio ([MCP](#mcp)); takes `--url` and `--db` and nothing else |
| `help` | this table |

Common options: `--since=<selector>` (default `15m`), `--until=<selector>`, `--service=<name>`, `--limit=<n>` (the lists: findings, traces, queries, errors, logs, marks, and the rows of `sql`; `endpoints` always lists every endpoint of the window), `--url=<base url>` (default `http://127.0.0.1:4000`, or `SPIDERSENSE_URL`), `--db=<path or jdbc url>`, `--json`, `--full`.
`compare` takes no `--since`: its windows are the two selectors.
`init` takes none of them at all: it asks nothing and nobody, and its own options are `--dir`, `--jar`, `--no-skill` and `--mcp` ([init](#init)).

Output is the text rendering above; `--json` prints the JSON instead.
The CLI does not render anything itself: when a Spider Sense is running it fetches `format=text` and prints the body, and when none answers at `--url` it opens the database in process, runs the same queries and the same renderer, and says so on stderr:

```
(no Spider Sense at http://127.0.0.1:4000; reading /home/me/db/spider-sense/sense.mv.db directly)
```

That is what `AUTO_SERVER=TRUE` buys: the application has crashed, the UI went with it, and `findings --since=start` still answers.
In that path the thresholds are the defaults or `--slow.request.ms`/`--slow.query.ms`, and the application packages `--app.packages`, since no server is there to ask.
The file must exist and carry this version's schema: the CLI never creates a database and never upgrades one, because `AUTO_SERVER=TRUE` may have joined the database of an older Spider Sense that is still running, and the server's own open would drop its tables (storage.md).
A missing file or another schema version is a message on stderr and exit code 2.

Exit codes: `0` success (and `check` passed), `1` `check` failed, `2` usage or connection error, `3` `check` had no request to judge, `4` not found (a trace id, a mark name, a finding id to `unack`).

## Skill

`skills/spider-sense/SKILL.md`, with references beside it, in the same form as Spider Silk's skill.
`init` installs a copy of it into a project's `.claude/skills/spider-sense/` ([init](#init)).
It is what an agent reads to run the loop without being told how:

1. Start the application under the agent (`-javaagent`, or `JAVA_TOOL_OPTIONS` when the start command is not the agent's to change), and confirm with `status`.
2. `mark before`, exercise the endpoints in question (or run the tests, or the load generator).
3. `findings --since=before`; read the top finding, open its trace, locate the code.
4. Fix; restart if needed (`since=start` then covers the new run).
5. `mark after`, exercise the same way, `compare --before=before --after=after`, `check`.

The references list the finding kinds with the fix each usually wants (a fetch join or a batch for `n-plus-one`, an index or a rewrite for `slow-query`, and so on), the CLI table above, and how to start each kind of application under the agent (Gradle `run`, Spring Boot `bootRun`, a plain `java -jar`, a test task).

## init

```
java -jar spider-sense.jar init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]
```

`init` prepares a project to be worked on under Spider Sense, and it is the one command that reads nothing: no HTTP, no database, no running Spider Sense.
It writes a short block into the project's `CLAUDE.md` and copies the skill into the project's `.claude/skills/`.
`--dir` is the project directory and defaults to the working directory.

**The jar path.** `--jar` when it is given, otherwise the distributable jar the command was started from: the launcher sets the system property `spidersense.jar` to its own absolute path before it invokes the CLI, because the CLI itself runs out of the nested server jar extracted to a temporary directory and could never find the distributable on its own.
The path is written absolute, as given or as discovered, and never made relative to the project.
When neither is known — exploded classes in an IDE, and no `--jar` — `init` says which option it needs and exits `2`.

**The block.** It is delimited by `<!-- spider-sense:start -->` and `<!-- spider-sense:end -->`, each on a line of its own, and this is it:

````markdown
<!-- spider-sense:start -->
## Spider Sense

Spider Sense is a local-development APM for this project, and the jar is at `/home/me/tools/spider-sense.jar`.
Start the application under it with `java -javaagent:/home/me/tools/spider-sense.jar -jar <app jar>`, or, when the start command is not yours to change, with `JAVA_TOOL_OPTIONS="-javaagent:/home/me/tools/spider-sense.jar" ./gradlew bootRun` (or `./gradlew run`).
The UI is then at <http://127.0.0.1:4000> unless the port was changed.

Ask it from the terminal; every answer is Markdown made for an agent:

```bash
java -jar /home/me/tools/spider-sense.jar findings --since=start  # ranked: N+1, slow queries, slow endpoints, errors, exhausted pools
java -jar /home/me/tools/spider-sense.jar trace <id>  # one request as a tree
java -jar /home/me/tools/spider-sense.jar mark before  # name a moment, exercise, then compare
java -jar /home/me/tools/spider-sense.jar compare --before=before --after=after
java -jar /home/me/tools/spider-sense.jar check --max-p95-ms=300 --max-n-plus-one=0
java -jar /home/me/tools/spider-sense.jar help  # every command and every option
```

The loop — start, mark, exercise, findings, fix, compare, check — is in the skill at `.claude/skills/spider-sense/SKILL.md`.
<!-- spider-sense:end -->
````

`/home/me/tools/spider-sense.jar` above is the jar path; everything else is written as it stands, and the block is generated from one place in the code.
A second `init` replaces everything between the markers, including when the jar path has changed, and leaves the rest of the file byte for byte as it was; nothing else in the file is parsed or reformatted.
When `CLAUDE.md` does not exist it is created with the block alone; when it exists without the markers the block is appended after one blank line.
The last line names `skills/spider-sense/` of the Spider Sense repository (<https://github.com/benelog/spider-sense>) instead of `.claude/skills/spider-sense/SKILL.md` when `--no-skill` kept the skill from being installed.
No port of the project is written: the block names the Spider Sense UI's own default, `http://127.0.0.1:4000`, and nothing else.

**The skill.** `init` copies `skills/spider-sense/**` — `SKILL.md` and `references/*.md` — into `<dir>/.claude/skills/spider-sense/`, overwriting the files it owns and leaving anything else in that directory alone, unless `--no-skill` is given.
A copy rather than a pointer, because the jar is the distributable and the repository it was built from may not be on the machine at all.
The files travel inside the jar: the server module's build packages the repository's `skills/spider-sense/` directory into the resources under `spider-sense/skill/`, together with a generated `spider-sense/skill/index.txt` listing the relative paths, since a class loader cannot list a directory.
The repository's `skills/spider-sense/` stays the single source; nothing is duplicated under `src/main/resources`.

**What it prints**, one line each, on stdout, and then exit `0`:

```
wrote CLAUDE.md block (jar: /home/me/tools/spider-sense.jar)
installed skill to /home/me/project/.claude/skills/spider-sense (4 files)
```

The first line is `updated CLAUDE.md block (…)` when the markers were already in the file, and the second is `skipped skill (--no-skill)` when the skill was not installed.

**`--mcp`.** A third line, `wrote .mcp.json (spider-sense over stdio)` or `updated .mcp.json (…)`, when the option is given: `<dir>/.mcp.json` gets `mcpServers.spider-sense` set to `{ "command": "java", "args": ["-jar", "<jar path>", "mcp"] }`, the same absolute jar path as the block.
An existing file is parsed as JSON and every other entry is kept, though the file is rewritten in the server's own JSON formatting; a file that is not a JSON object is left alone with a message on stderr and exit code `2`.
Without `--mcp` nothing is written and nothing is printed about it: a host with a shell is meant to use the CLI ([Choosing an interface](#choosing-an-interface)), and `init` should not hand it a second tool for the same answers.

## Considered and deferred

- **MCP over HTTP only.** The first design served MCP on the existing port and nothing else, which is the least code. Rejected as the only transport because a host on the same machine then loses the one property that makes the CLI trustworthy after a crash: the H2 file can be read with the server gone. The stdio transport reuses the CLI's own decision (a server if one answers, the file otherwise) and adds no third way of answering.
