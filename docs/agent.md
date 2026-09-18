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
| MCP | deferred | hosts without a shell; see [Considered and deferred](#considered-and-deferred) |

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
Findings are ranked by severity (`high` before `medium` before `low`; no rule produces `low` today, the value is reserved for gentler kinds), then by impact within a kind, then by id, so the list is stable between two calls over the same data.
`limit` defaults to 20 and is at most 100.

| Kind | Rule | Severity | Impact |
|---|---|---|---|
| `error` | an error group (api.md) with at least one occurrence in the window | `high` | count |
| `n-plus-one` | in one trace, the same query group runs 5 or more times under the same entry span; aggregated per (endpoint, query group) over the window | `high` when the repeats reach 20 or their summed time exceeds `slow.request.ms`, else `medium` | affected requests × median repeats |
| `slow-query` | a query group whose p95 exceeds `slow.query.ms` | `high` when p95 exceeds ten times the threshold, else `medium` | total time |
| `slow-endpoint` | an endpoint whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold (the "frustrated" bound of the Apdex), else `medium` | total time |
| `pool-exhausted` | a JDBC pool with a point in the window where pending requests are above zero, or used equals max | `high` | pending, then used |

Each finding carries:

```json
{
  "id": "n-plus-one:1a2b3c4d5e6f",          // kind + 12 hex of SHA-256 over (kind, service, subject); stable across windows
  "kind": "n-plus-one",
  "severity": "high",
  "service": "spring-orders",
  "title": "GET /orders/{id} runs SELECT order_line 42 times per request",   // one line, no numbers a person would not say aloud
  "why": "3 of 3 requests repeated it; 42, 42 and 41 times; 38.2 ms per request in that statement",
  "subject": { "endpointId": "…" | null, "queryId": "…" | null, "errorId": "…" | null, "pool": "…" | null },
  "numbers": { … },                         // kind-specific, listed below
  "statement": "SELECT … FROM order_line WHERE order_id = ?" | null,
  "code": [ "orders.OrderService.load(OrderService.java:41)" ],   // application frames, most specific first, at most 5; empty when none is known
  "traces": [ "4bf92f3577b34da6a3ce929d0e0e4736", … ]              // at most 3, the evidence
}
```

`numbers` per kind:

- `error`: `count`, `firstSeen`, `lastSeen`, `type`, `message` (normalised), `endpoints` (name and count, as api.md's `ErrorGroup.endpoints`).
- `n-plus-one`: `requests` (entry spans of the endpoint in the window), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).
- `slow-query`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as api.md's `QueryStats.callers`).
- `slow-endpoint`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1: the part of the endpoint's total time spent in database spans of the same trace and service).
- `pool-exhausted`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).

`traces` are the three slowest traces for `slow-*`, the three newest for `error`, the three most recent affected for `n-plus-one`, none for `pool-exhausted`.

**Code locations.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes from what it does record: the `exception.stacktrace` of an error, and the `code.function`/`code.namespace` attributes of the few instrumentations that set them.
A stack trace is reduced to its application frames: frames whose package is not one of the framework prefixes below, at most 5, innermost first.
`spidersense.app.packages=com.acme,org.acme` (a comma-separated list) replaces the heuristic with an allowlist.

Framework prefixes dropped by default: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.eclipse.jetty.`, `org.apache.`, `io.opentelemetry.`, `com.zaxxer.`, `org.h2.`, `net.benelog.spidersilk.`, `kotlin.`, `scala.`, `reactor.`, `io.netty.`, `ch.qos.logback.`, `org.slf4j.`, `org.junit.`, `gg.jte.`.

An extension that captures a stack trace for a database span above `slow.query.ms` would make `code` available for slow queries too; it is listed under deferred.

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

`Side` is `{ "calls", "errors", "p50Ms", "p95Ms", "maxMs", "dbCallsPerRequest", "dbMsPerRequest" }`; `QuerySide` is `{ "calls", "callsPerRequest", "p95Ms", "totalMs" }` where `callsPerRequest` divides by the entry spans of the window (of the service when one is given).

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
| `minApdex` | the Apdex over the scope |

`endpoint` narrows the scope to one endpoint, by `endpointId` or by name (`GET /orders/{id}`).

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

## Text rendering

Any endpoint listed here answers Markdown when asked with `format=text` or with an `Accept` header whose first type is `text/markdown` or `text/plain`; the response is `text/markdown; charset=utf-8`.
JSON stays the default.

Endpoints with a text rendering: `/api/status`, `/api/findings`, `/api/marks`, `/api/compare`, `/api/check`, `/api/sql`, `/api/traces`, `/api/traces/{id}`, `/api/endpoints`, `/api/queries`, `/api/errors`, `/api/logs`, `/api/services`.

Conventions, so that the text is small and stable:

- The first line is a heading naming what it is and the window, in ISO-8601 with the local offset: `# findings  2026-09-17T10:00:00+09:00 → 10:15:00  (15m, all services, 120 requests)`; the range is rounded to the second and written in its largest units (`6s`, `2m 30s`, `15m`, `2h`).
- Lists are Markdown tables; ids are complete (a trace id is 32 hex characters, an endpoint, query or error id 12), because the agent will pass them back.
- Durations are milliseconds with one decimal and a thousands separator: `1,532.4 ms`; counts are integers; rates are percentages with one decimal.
- A statement is cut at 200 characters with `…`; `full=true` keeps it whole.
- Nothing in the body depends on when it was rendered, only on the window; `now` appears only in the heading.
- An empty result says what was looked for and where: `no findings since 2026-09-17T10:00:00+09:00 (15m, 120 requests)`, and, when there was no request at all, how to send some (the OTLP endpoint).

**A trace** is an indented tree, one span per line:

```
# trace 4bf92f3577b34da6a3ce929d0e0e4736  2026-09-17T10:22:01.123+09:00  152.3 ms  spring-orders → silk-bookstore  14 spans, 6 db, 1 error

offset     duration  span
0.0 ms     152.3 ms  SERVER spring-orders GET /orders/{id} → 500  [slow] [error]
1.2 ms     3.4 ms      db SELECT orders
4.8 ms     38.2 ms     db SELECT order_line  × 42, 0.9 ms avg, 38.2 ms total
43.1 ms    104.0 ms    CLIENT GET http://localhost:8081/api/books/{id} → 200
43.9 ms    102.8 ms      SERVER silk-bookstore GET /api/books/{id} → 200
45.0 ms    101.1 ms        db SELECT book  [slow]
                             SELECT b.* FROM book b WHERE b.title LIKE ?
147.9 ms   0.1 ms       exception IllegalStateException: no such order 42
                             orders.OrderService.load(OrderService.java:41)
                             orders.OrderController.show(OrderController.java:23)

logs (2)
10:22:01.130  WARN   o.s.web.servlet.DispatcherServlet  Resolved [IllegalStateException: no such order 42]
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
| `findings` | the findings of the window |
| `trace <traceId> [--full]` | one trace as a tree |
| `traces [--status=error\|ok] [--min-ms=] [--q=] [--limit=20]` | the newest traces |
| `endpoints`, `queries`, `errors` | the tables of the window |
| `logs [--severity=WARN] [--q=] [--trace=<traceId>]` | log lines |
| `mark <name> [--note=…]` | records a mark now |
| `marks` | lists marks |
| `compare --before=<selector> --after=<selector> [--until=<selector>]` | the two windows side by side |
| `check [--max-p95-ms=] [--max-errors=] [--max-error-rate=] [--max-queries-per-request=] [--max-slow-queries=] [--max-n-plus-one=] [--min-apdex=] [--endpoint=]` | pass or fail, in the exit code |
| `sql "<statement>" [--limit=200]` | one read-only statement over the schema of storage.md |
| `help` | this table |

Common options: `--since=<selector>` (default `15m`), `--until=<selector>`, `--service=<name>`, `--limit=<n>` (the lists: findings, traces, queries, errors, logs, marks, and the rows of `sql`; `endpoints` always lists every endpoint of the window), `--url=<base url>` (default `http://127.0.0.1:4000`, or `SPIDERSENSE_URL`), `--db=<path or jdbc url>`, `--json`, `--full`.
`compare` takes no `--since`: its windows are the two selectors.

Output is the text rendering above; `--json` prints the JSON instead.
The CLI does not render anything itself: when a Spider Sense is running it fetches `format=text` and prints the body, and when none answers at `--url` it opens the database in process, runs the same queries and the same renderer, and says so on stderr:

```
(no Spider Sense at http://127.0.0.1:4000; reading /home/me/db/spider-sense/sense.mv.db directly)
```

That is what `AUTO_SERVER=TRUE` buys: the application has crashed, the UI went with it, and `findings --since=start` still answers.
In that path the thresholds are the defaults or `--slow.request.ms`/`--slow.query.ms`, and the application packages `--app.packages`, since no server is there to ask.
The file must exist and carry this version's schema: the CLI never creates a database and never upgrades one, because `AUTO_SERVER=TRUE` may have joined the database of an older Spider Sense that is still running, and the server's own open would drop its tables (storage.md).
A missing file or another schema version is a message on stderr and exit code 2.

Exit codes: `0` success (and `check` passed), `1` `check` failed, `2` usage or connection error, `3` `check` had no request to judge, `4` not found (a trace id, a mark name).

## Skill

`skills/spider-sense/SKILL.md`, with references beside it, in the same form as Spider Silk's skill.
It is what an agent reads to run the loop without being told how:

1. Start the application under the agent (`-javaagent`, or `JAVA_TOOL_OPTIONS` when the start command is not the agent's to change), and confirm with `status`.
2. `mark before`, exercise the endpoints in question (or run the tests, or the load generator).
3. `findings --since=before`; read the top finding, open its trace, locate the code.
4. Fix; restart if needed (`since=start` then covers the new run).
5. `mark after`, exercise the same way, `compare --before=before --after=after`, `check`.

The references list the finding kinds with the fix each usually wants (a fetch join or a batch for `n-plus-one`, an index or a rewrite for `slow-query`, and so on), the CLI table above, and how to start each kind of application under the agent (Gradle `run`, Spring Boot `bootRun`, a plain `java -jar`, a test task).

## Considered and deferred

- **MCP.** The Skill and the CLI cover Claude Code and every agent with a shell, and the text API covers every agent with `curl`. MCP adds a typed tool list for hosts that have neither, at the price of a second protocol to keep in step with the API. When one is wanted it is an adapter over the same handlers, served on the existing port as `POST /mcp` (Streamable HTTP), with six tools at most: `findings`, `trace`, `mark`, `compare`, `check`, `sql`. Nothing in this document needs to change for it.
- **Stack traces for slow spans.** The stock agent records none. A small OpenTelemetry extension (a `SpanProcessor` that captures the stack of a database span at its end when it ran longer than `slow.query.ms`, into `code.stacktrace`) would give `code` to `slow-query` and `n-plus-one` findings. Deferred: it is the first piece that is not the stock agent.
- **`init`.** A command that writes a few lines about Spider Sense into a project's `CLAUDE.md`. Cheap; deferred until the skill has settled.
