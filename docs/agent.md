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
| MCP | deferred | hosts without a shell; see [Considered and deferred](#considered-and-deferred) |
| Read-only SQL | deferred | questions nobody anticipated; the schema in storage.md is already the documentation |

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
- `n-plus-one`: `requests` (entry spans of the endpoint in the window, as design.md defines an entry span), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).
- `slow-query`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as api.md's `QueryStats.callers`).
- `slow-endpoint`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1: the part of the endpoint's total time spent in database spans of the same trace and service).
- `pool-exhausted`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).

`traces` are the three slowest traces for `slow-*`, the three newest for `error`, the three most recent affected for `n-plus-one`, none for `pool-exhausted`.

**Code locations.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes from what it does record: the `exception.stacktrace` of an error, and the `code.function`/`code.namespace` attributes of the few instrumentations that set them.
A stack trace is reduced to its application frames: frames whose package is not one of the framework prefixes below, at most 5, innermost first.
`spidersense.app.packages=com.acme,org.acme` (a comma-separated list) replaces the heuristic with an allowlist.

Framework prefixes dropped by default: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.eclipse.jetty.`, `org.apache.`, `io.opentelemetry.`, `com.zaxxer.`, `org.h2.`, `net.benelog.spidersilk.`, `kotlin.`, `scala.`, `reactor.`, `io.netty.`, `ch.qos.logback.`, `org.slf4j.`, `org.junit.`, `gg.jte.`.

Slow queries have a code location too, and it is the one thing Spider Sense collects itself: its OpenTelemetry extension ([design.md](design.md#the-extension)) sets `code.stacktrace` on every database span that ran at least `slow.query.ms`, so `slow-query` and `n-plus-one` findings carry `code` just as an error does.
Those frames are the truest of the three, because they are the span's own thread at the moment the statement finished, not a guess from an attribute; they are reduced by the same rules as `exception.stacktrace` above.

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

## Text rendering

Any endpoint listed here answers Markdown when asked with `format=text` or with an `Accept` header whose first type is `text/markdown` or `text/plain`; the response is `text/markdown; charset=utf-8`.
JSON stays the default.

Endpoints with a text rendering: `/api/status`, `/api/findings`, `/api/marks`, `/api/compare`, `/api/check`, `/api/traces`, `/api/traces/{id}`, `/api/endpoints`, `/api/queries`, `/api/errors`, `/api/logs`, `/api/services`.

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
| `findings` | the findings of the window |
| `trace <traceId> [--full]` | one trace as a tree |
| `traces [--status=error\|ok] [--min-ms=] [--q=] [--limit=20]` | the newest traces |
| `endpoints`, `queries`, `errors` | the tables of the window |
| `logs [--severity=WARN] [--q=] [--trace=<traceId>]` | log lines |
| `mark <name> [--note=…]` | records a mark now |
| `marks` | lists marks |
| `compare --before=<selector> --after=<selector> [--until=<selector>]` | the two windows side by side |
| `check [--max-p95-ms=] [--max-errors=] [--max-error-rate=] [--max-queries-per-request=] [--max-slow-queries=] [--max-n-plus-one=] [--min-apdex=] [--endpoint=]` | pass or fail, in the exit code |
| `init [--dir=<project dir>] [--jar=<path>] [--no-skill]` | writes the Spider Sense block into the project's `CLAUDE.md` and installs the skill into its `.claude/skills/` |
| `help` | this table |

Common options: `--since=<selector>` (default `15m`), `--until=<selector>`, `--service=<name>`, `--limit=<n>` (the lists: findings, traces, queries, errors, logs, marks; `endpoints` always lists every endpoint of the window), `--url=<base url>` (default `http://127.0.0.1:4000`, or `SPIDERSENSE_URL`), `--db=<path or jdbc url>`, `--json`, `--full`.
`compare` takes no `--since`: its windows are the two selectors.
`init` takes none of them at all: it asks nothing and nobody, and its own options are `--dir`, `--jar` and `--no-skill` ([init](#init)).

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
java -jar spider-sense.jar init [--dir=<project dir>] [--jar=<path>] [--no-skill]
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

## Considered and deferred

- **MCP.** The Skill and the CLI cover Claude Code and every agent with a shell, and the text API covers every agent with `curl`. MCP adds a typed tool list for hosts that have neither, at the price of a second protocol to keep in step with the API. When one is wanted it is an adapter over the same handlers, served on the existing port as `POST /mcp` (Streamable HTTP), with six tools at most: `findings`, `trace`, `mark`, `compare`, `check`, `sql`. Nothing in this document needs to change for it.
- **Read-only SQL** (`POST /api/sql`, and `sql` in the CLI and MCP). A read-only connection with a row limit over the schema in storage.md. Deferred until a question comes up that findings and the tables cannot answer.
