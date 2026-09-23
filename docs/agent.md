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
| Skills, `skills/spider-sense/` and `skills/spider-sense-sql-tuning/` | this version | the first teaches an agent the loop itself: how to start the app under the agent, mark, exercise, read findings, fix, compare, check; the second teaches query tuning: indexes, rewrites, fetch joins, batching, verified with `compare` and `check` |
| Read-only SQL, `POST /api/sql` and `sql` | this version | the question nobody anticipated; the schema in storage.md is already the documentation |
| MCP, `POST /mcp` and `mcp` | this version | hosts without a shell; seven tools over the same handlers, answering the same text ([MCP](#mcp)) |

### Choosing an interface

The CLI and MCP call the same handlers and print the same bytes, so the choice costs nothing in what is answered; it is decided by what the host can reach.

| Host | Use | Why |
|---|---|---|
| An agent with a shell (Claude Code, Codex CLI, Gemini CLI, Aider, a script) | the CLI and the skills | no setup beyond `init`; works with the application down; `check` is an exit code; output can be piped; the tool costs no context |
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
Regressions among themselves are ranked by the order of their original kinds before their impact.
`limit` defaults to 20 and is at most 100.

| Kind | Rule | Severity | Impact |
|---|---|---|---|
| `regression` | a finding of any other kind whose id has a resolution older than its first occurrence in the window ([Resolutions](#resolutions)) | `high` | the underlying finding's |
| `error` | an error group (api.md) with at least one occurrence in the window | `high` | count |
| `n-plus-one` | in one trace, the same query group runs 5 or more times under the same entry span; aggregated per (endpoint, query group) over the window | `high` when the repeats reach 20 or their summed time exceeds `slow.request.ms`, else `medium` | affected requests × median repeats |
| `n-plus-one-http` | in one trace, the same outbound HTTP call runs 5 or more times under the same entry span; aggregated per (endpoint, call) over the window | as `n-plus-one` | affected requests × median repeats |
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
  "state": "new" | "ongoing" | "regressed", // below, "State"
  "service": "spring-orders",
  "title": "GET /orders/{id} runs SELECT order_line 42 times per request",   // one line, no numbers a person would not say aloud
  "why": "3 of 3 requests repeated it; 42, 42 and 41 times; 38.2 ms per request in that statement",
  "subject": { "endpointId": "…" | null, "queryId": "…" | null, "errorId": "…" | null, "pool": "…" | null, "job": "…" | null,
               "target": "…" | null, "logger": "…" | null, "jvm": "gc:G1 Young Generation" | "heap" | "threads" | null },
  "numbers": { … },                         // kind-specific, listed below
  "statement": "SELECT … FROM order_line WHERE order_id = ?" | null,
  "code": [ "orders.OrderService.load(OrderService.java:41)" ],   // application frames, most specific first, at most 5; empty when none is known
  "traces": [ "4bf92f3577b34da6a3ce929d0e0e4736", … ],             // at most 3, the evidence
  "schema": { "tables": [ … ], "predicates": [ … ], "unindexed": [ … ] } | null   // slow-query and n-plus-one only; below
}
```

`numbers` per kind:

- `regression`: `resolvedAt` (the resolution's instant), `note` (the resolution's note, or `null`), `originalKind`, then the underlying finding's own numbers; `subject`, `title`, `statement`, `code`, `traces` and `schema` are the underlying finding's, and `why` is `came back after it was resolved (<note>); ` followed by the underlying `why`.
- `error`: `count`, `firstSeen`, `lastSeen`, `type`, `message` (normalised), `endpoints` (name and count, as api.md's `ErrorGroup.endpoints`).
- `n-plus-one`: `requests` (entry spans of the endpoint in the window, as design.md defines an entry span), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).
- `n-plus-one-http`: the same five as `n-plus-one`, over the repeated call rather than the repeated statement; `subject.endpointId` is the endpoint, `subject.target` is the host it called and the title is `<endpoint> calls <call> <medianRepeats> times per request`.
- `slow-query`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as api.md's `QueryStats.callers`).
- `slow-endpoint`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1: the part of the endpoint's total time spent in database spans of the same trace and service), `hotSpan`, `hotSpans`, `breakdown`.
- `slow-job`: `runs`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `dbCallsPerRun`, `dbMsPerRun`, `dbShare`, the same measures as `slow-endpoint` over the job's runs, `hotSpan`, `hotSpans`, `breakdown`; `subject.job` is the span name and the title is `<job> is slow`.
- `slow-external`: `calls`, `errors`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (as `slow-query`'s: the entry spans up the chain); `subject.target` is the target and the title is `GET localhost:8081 is slow` (the span name, then the target).
- `log-error`: `count` (uncovered records), `firstSeen`, `lastSeen`, `logger`, `message` (normalised), `endpoints` (the entry span of each record's trace, as `error`'s `endpoints`; `(no endpoint)` for a record without one); `subject.logger` is the logger and the title is `ERROR in <logger short name>: <message cut at 80>`.
- `pool-exhausted`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).
- `gc-pause`: `gc`, `action`, `worstMs` (the longest single collection), `shareMax` (0..1, the worst interval's collection time over its length), `collections` (over the window), `at`; `subject.jvm` is `gc:<name>` and the title is `<gc> paused for <worstMs>`.
- `heap-pressure`: `usedMax`, `limit`, `ratioMax` (0..1), `at`; `subject.jvm` is `heap` and the title is `heap at <ratio>% of its limit`.
- `thread-growth`: `first`, `last`, `max`, `at` (the last point); `subject.jvm` is `threads` and the title is `threads grew from <first> to <last>`.

`hotSpan` is where the time went in the finding's first evidence trace: `{ "name": "<summary>", "category": "db" | "http" | "internal" | …, "selfMs": 312.4, "share": 0.62 }`, the span with the largest self time (its duration minus the durations of its direct children, never below zero, as the Profile view computes it) and that self time's share of the trace's duration; `null` when the finding has no trace.
It is the first clue when `dbShare` is low, and in the text rendering it is one line, `hot span: <name> · <selfMs> self · <share>`.

### Where the time went

One trace is an anecdote, so a `slow-endpoint` and a `slow-job` also answer the same question over many: `hotSpans` names the three summaries that took the most time, and `breakdown` says what kind of work the time was.

```json
"hotSpans": [ { "name": "SELECT order_line", "category": "db", "selfMs": 4210.5, "share": 0.44, "count": 126 },
              { "name": "GET localhost:8081/api/books/?", "category": "http", "selfMs": 1980.0, "share": 0.21, "count": 42 } ],
"breakdown": { "db": 0.44, "http": 0.21, "internal": 0.07, "self": 0.28 }
```

Both are computed over the finding's **sample**: the 20 slowest traces of the endpoint or the job in the window, of which the first three are the `traces` the finding carries.
Twenty rather than every trace because the answer is read out of the spans themselves rather than out of an aggregate, and the slowest twenty are where a slow endpoint's time is; a share is therefore a share of those traces' summed duration, and the finding's own `totalMs` stays the window's.
Only the spans of the finding's own service count, the rule `dbShare` already uses: the database work of a downstream service belongs to that service's own endpoint, and leaving its server span out is what makes the wait on an outbound call land in `http` where the caller can see it.

**Self time** is a span's duration less the durations of its direct children within that set, never below zero, as the Profile view computes it.
A span whose parent is not in the set is a **top span** — the entry span of a request, the root span of a job — and the top spans are what the shares are taken over.

- `hotSpans`: every span but the top ones, grouped by its category and its name, summed, the three largest by `selfMs` first. `count` is how many spans went into the row, and `share` is `selfMs` over the sample's total.
  The name is the span's summary with every run of digits replaced by `?`, and for an outbound call it is the call of [The repeated call](#the-repeated-call) — `GET localhost:8081/api/books/?` — so one call to 40 items is one row and reads the same here as it does in an `n-plus-one-http`.
- `breakdown`: `db`, `http`, `internal` and `self`, which sum to 1. `self` is the top spans' own self time — the request's own code, the framework, everything not in a child span — and `internal` absorbs the `messaging` and `rpc` categories, because the question the four answer is "waiting on the database, waiting on the network, or working".

`breakdown` is the number to read first when `dbShare` is low: it says whether to look at the queries, at the calls, or at the code, without opening a trace.
`GET /api/endpoints/{endpointId}` ([api.md](api.md)) carries the same `breakdown` over the same sample, which is what the endpoint page draws.
Both are absent (`hotSpans` empty, `breakdown` empty) when the finding has no trace, or when the sample's spans add up to nothing.

In the text rendering they come after the numbers and before the statement:

```
   hot spans: SELECT order_line · 4,210.5 ms · 44.0% · ×126
              GET localhost:8081/api/books/? · 1,980.0 ms · 21.0% · ×42
   breakdown: db 44.0% · http 21.0% · internal 7.0% · self 28.0%
```

`traces` are the three slowest traces for `slow-*`, the three newest for `error` and `log-error` (records with a trace id), the three most recent affected for `n-plus-one` and `n-plus-one-http`, none for `pool-exhausted` and the JVM kinds, and for a `regression` those of its original kind, taken after the resolution.
A job is never a request: it is not in `requests`, not in the Apdex and not in `check`; `slow-job` is the one place a slow scheduler tick or batch step is reported.
An endpoint that `spidersense.ignore.endpoints` excludes (design.md) is not an entry span and produces no finding of any kind.

**Code locations.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes from what it does record: the `exception.stacktrace` of an error, and the `code.function`/`code.namespace` attributes of the few instrumentations that set them.
A stack trace is reduced to its application frames: frames whose package is not one of the framework prefixes below, at most 5, innermost first.
Innermost means the root cause first: the frames of the last `Caused by:` section come before those of the exception wrapping it, and so on out to the outer exception, each section read top down, a frame that repeats counted once, and a `Suppressed:` block left out.
The line that went wrong is usually under `Caused by:`, and reading the trace top to bottom put the wrapper's frames first and cut the root cause's at the fifth.
The same order holds for `error`, for `log-error` and for the error page's **Code** list ([ui.md](ui.md#errors-errors-and-errorserrorid)), and its first frame is the one an error group is keyed on ([design.md](design.md)) when no `spidersense.app.packages` narrows it.
`spidersense.app.packages=com.acme,org.acme` (a comma-separated list) replaces the heuristic with an allowlist.

Framework prefixes dropped by default: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.eclipse.jetty.`, `org.apache.`, `io.opentelemetry.`, `com.zaxxer.`, `org.h2.`, `net.benelog.spidersilk.`, `kotlin.`, `scala.`, `reactor.`, `io.netty.`, `ch.qos.logback.`, `org.slf4j.`, `org.junit.`, `gg.jte.`.

Slow queries, repeated queries, repeated calls and slow outbound calls have a code location too, and it is the one thing Spider Sense collects itself: its OpenTelemetry extension ([design.md](design.md#the-extension)) sets `code.stacktrace` on every database span that ran at least `slow.query.ms`, on the fifth repeat of a statement within one trace, on every non-database `CLIENT` span that ran at least `slow.request.ms`, and on the fifth repeat of an HTTP call within one trace, so `slow-query`, `n-plus-one`, `slow-external` and `n-plus-one-http` findings carry `code` just as an error does.
A `log-error` finding takes its `code` from the `exception.stacktrace` attribute of the group's newest record, when the logging bridge exported one.
Those frames are the truest of the three, because they are the span's own thread at the moment the statement finished, not a guess from an attribute; they are reduced by the same rules as `exception.stacktrace` above.
An `n-plus-one` or `n-plus-one-http` finding takes its `code` from the span of the repeated group that carries `code.stacktrace`, which is the fifth repeat, and falls back to the group's newest span when none does (an application run without the extension).
A `slow-job` finding takes its `code` from the `code.function` and `code.namespace` attributes the scheduling instrumentations set on the job's span.

### Source lines and the suspect change

Spider Sense runs on the machine the source is on, so a frame is one file read away from the line it names, and two `git` commands away from the change that last touched it.
A frame `orders.OrderService.load(OrderService.java:41)` resolves to the first `<root>/orders/OrderService.java` that exists under the roots of `spidersense.source.dirs` ([design.md](design.md#configuration)): by default `src/main/java` and `src/main/kotlin` of the working directory and of each of its immediate subdirectories, which covers a single-module build and a multi-module one.
A frame without a file and a line (`Unknown Source`, `Native Method`, a `code.function` frame) does not resolve, and neither does one whose path would leave its root: the path is built only from dotted Java identifiers and a plain file name with a source extension, and the file, symbolic links followed, must lie under its root.

**In the UI.** The findings page and the error page show the five lines around the frame's line under each frame that resolves, read on request by `GET /api/source?frame=…` ([api.md](api.md#source)) and never stored, and make the frame a link that opens the file at that line in IntelliJ IDEA or VS Code ([ui.md](ui.md#code-frames)).
A frame that does not resolve shows nothing more than itself.
The text rendering does not carry the lines: an agent opens the file itself.

**Suspect change.** The question an agent has about a frame is whether its line is in the change it just made, so the CLI's `findings` adds one line under each `code` frame that resolves to a file of the repository the CLI runs in:

```
   orders.OrderService.load(OrderService.java:41)
     changed in 4743e1d (2 hours ago): Run Error Prone and NullAway in every javac
   orders.web.OrderController.show(OrderController.java:28)
     uncommitted
```

- `uncommitted` when the file is in `git diff --name-only` or `git diff --cached --name-only`, or is untracked (`git ls-files --others --exclude-standard`), because a file the agent has just created is the most likely suspect of all; also when `git blame` says the line itself is not committed yet.
- Otherwise `changed in <hash> (<age>): <subject>`: the first seven characters of the commit `git blame -L <n>,<n> --porcelain` names, its author date as an age in the largest whole unit (`45 seconds ago`, `2 hours ago`, `3 days ago`, `3 weeks ago`, `4 months ago`, `2 years ago`), and its subject.
- Nothing when the frame does not resolve, when the file lies outside the repository, or when `git blame` has nothing to say about the line (a file edited to be shorter than the frame's line).

It is indented two spaces past the frame, so a reader of the block sees it as the frame's.
It is off when the working directory is not in a repository or `git` cannot be run, and `--no-git` turns it off; `--json` prints the server's JSON, which never carries it.

This is the one thing the CLI adds to an answer rather than printing what the server or the in-process renderer produced ([CLI](#cli)), and it has to be: the CLI runs in the project directory, where the repository is, and the server may run somewhere else or not at all.
It is therefore also the one part of a findings answer that is not a function of the window alone: the age depends on when it was asked, and the verdict on the working tree.
The HTTP API and the MCP `findings` tool answer without it, since neither knows which repository the caller is working in.

### The schema block

A `slow-query` or `n-plus-one` finding of a service that ran under the agent also says, as a fact read from the database rather than a guess from the statement, which of the columns the statement filters on carry no index:

```json
"schema": {
  "tables": [ { "table": "ITEMS", "schema": "PUBLIC",
                "indexes": [ { "name": "PRIMARY_KEY_8", "unique": true, "columns": [ "ID" ] },
                             { "name": "IDX_ITEMS_SUPPLIER", "unique": false, "columns": [ "SUPPLIER_ID", "NAME" ] } ] } ],
  "predicates": [ "items.name", "items.category" ],
  "unindexed":  [ "items.name", "items.category" ]
}
```

`tables` are the tables the statement names, each with its indexes as the extension read them through `DatabaseMetaData.getIndexInfo` on the application's own connection ([design.md](design.md#the-extension)) and the store keeps them per service and table ([storage.md](storage.md), `db_table`); the names are the database's spelling (`ITEMS` on H2, `items` on PostgreSQL, the case the table was created with on MySQL), `schema` is `null` when the database reports none, and `indexes` is empty for a table that has none.
`predicates` are read from the sanitised statement by a light parse of the common shapes: every column a `where` or `on` clause or an `order by` list refers to, bare (`where name = ?`), qualified (`where i1_0.name like ?`, the alias resolved to its table) or wrapped in a function (`where lower(name) like ?`), written as `table.column` in lower case whatever case the statement used, in order of first appearance and once each; a column named without a qualifier belongs to the statement's only table.
A function in an `order by` list is skipped whole (`order by sum(l.quantity) desc` names no column), because no index sorts an aggregate.
`unindexed` is the subset of `predicates` that no index of their table has as its first column, the one an index can seek on: a column an index carries second is served no better by it than a column no index carries at all.
A function-wrapped column is matched by its name, so an index on `name` takes `lower(name)` out of `unindexed`; the statement says whether an expression index is what is really needed.
The block is `null` rather than wrong whenever the parse cannot vouch for it: a table the catalog has no row for (the application ran in standalone mode or without the extension, or no statement on that table has been slow yet), a statement whose tables the scanner does not find, a column it cannot attribute to one table (an unqualified column in a join, the alias of a subquery).
It says nothing about selectivity, wildcards or the plan; that is what the `spider-sense-sql-tuning` skill and `EXPLAIN` are for, and the block is what lets the skill write the index without a round trip to the database.

In the text rendering the block sits between the statement and the code frames, one line per table, then one line for the columns:

```
   indexes ITEMS: PRIMARY_KEY_8 (ID) unique, IDX_ITEMS_SUPPLIER (SUPPLIER_ID, NAME)
   indexes MOVEMENTS: none
   predicates: items.name, items.category; unindexed: items.name, items.category
```

`predicates: none` stands alone when the statement has no predicate, `unindexed: none` when every predicate is served, and a finding without the block prints nothing for it.
`GET /api/queries` ([api.md](api.md)) carries the same block on every query group, and its text rendering has an `unindexed` column between `callers` and `statement`: the unindexed columns joined by `, `, `none` when every predicate is served, `—` when there is no block.

### The repeated call

An `n-plus-one-http` is the N+1 an ORM cannot cause: a loop that fetches one remote resource per item.
The trace shows it as a run of `CLIENT` spans of category `http` under one entry span, and the example trace above is exactly that shape.

Two of those spans are **the same call** when they agree on their name, their target and their path once every run of digits is replaced by `?`.
The call is written the way it is grouped:

```
GET localhost:8081/api/books/?
```

The span's name, the dependency target of [api.md](api.md) (`localhost:8081`, the host and port the call went to), and the path and query of `url.full`, digits replaced.
The digits are what a loop varies, so replacing them is what makes three calls one call; it is the same key the trace diff aligns on.
The status is not part of it, because the same call can answer `200` to one item and `404` to the next, and the host is kept out of the digit rule so that the port stays readable.
A span with no `url.full` is named `<span name> <target>` and grouped by that.

The rule then is `n-plus-one`'s with the call in place of the statement: five or more of the same call under one entry span makes that request affected, the affected requests of one `(endpoint, call)` over the window make the finding, and `numbers`, the severity, the impact, the evidence traces and the text rendering are the ones `n-plus-one` has.
`statement` is `null`, and `subject.target` names the host so that the callee's own findings are one filter away.
`check` counts it under `maxNPlusOne`, with the DB kind: to a caller, a loop of queries and a loop of calls are one mistake.

## Acknowledgements

A finding that is known and accepted — a report endpoint that is slow by design, a query that will stay slow until the schema changes — sits at the top of every `findings` answer and hides the new problem under it.
An acknowledgement takes it out of the way without hiding it: finding ids are stable across windows ([Findings](#findings)), so it is one row, `(finding_id, at, note)` in the `ack` table (storage.md), kept until it is withdrawn or the data is cleared.

- `POST /api/findings/{id}/ack` with `{ "note": "…" | null }` → `201` `{ "findingId": "…", "at": …, "note": … }`; acknowledging again replaces the row, and so does acknowledging a resolved finding.
- `DELETE /api/findings/{id}/ack` → `204`, or `404` when there is no such acknowledgement (a resolution is not one).
- `GET /api/acks` → `{ "acks": [ … ] }`, newest first; resolutions are not listed.

Every finding carries `"ack": { "at": …, "note": "…" | null } | null`.
Acknowledged findings are ranked after every other finding, in the same order among themselves; `hideAcked=true` on `/api/findings` (and on the MCP `findings` tool) leaves them out.
An acknowledged finding that recurs stays acknowledged: acknowledging says "known, keep it out of the way", and a recurrence is exactly what was accepted.
In the text rendering the severity column reads `acked` for an acknowledged finding, and the heading counts them: `(… 12 requests, 2 acked)`.
`check` does not look at acknowledgements: its rules are explicit thresholds, and an acknowledged `n-plus-one` is still an N+1 to `maxNPlusOne`.

The CLI commands are `ack <finding id> [--note=…]`, `unack <finding id>`, and `findings --hide-acked`; the id is what `findings` printed.
Both write in the direct-file path as `mark` does.

## Resolutions

A resolution says "I fixed this; tell me if it comes back".
It differs from an acknowledgement in one thing only: an acknowledged finding that recurs stays acknowledged and dimmed, a resolved one that recurs becomes a `regression`, ranked above everything else.
It is the same row in the `ack` table with `resolved` set (storage.md), so a finding has at most one decision recorded, and the newer one replaces the older: resolving an acknowledged finding, or acknowledging a resolved one, replaces the row.

- `POST /api/findings/{id}/resolve` with `{ "note": "…" | null }` → `201` `{ "findingId": "…", "at": …, "note": … }`; resolving again replaces the row.
- `DELETE /api/findings/{id}/resolve` → `204`, or `404` when there is no such resolution (an acknowledgement is not one).

Every finding carries `"resolution": { "at": …, "note": "…" | null } | null`.
A finding the rules produce whose id has a resolution is one of two things:

- **Back.** When the resolution is older than the window's first occurrence of the finding, it is reported as kind `regression` instead of its own kind: severity `high`, first in the ranking, the same id, `numbers` carrying the resolution's `resolvedAt` and `note` and the `originalKind` before the finding's own numbers (Findings, above).
  A resolution older than the window makes every occurrence in it a recurrence; a resolution inside the window runs the rules again over the part of the window after it, for the finding's service, and the finding is back when they produce its id there.
  That run's finding is the one reported, so the `numbers` and `traces` of a regression are those of its return, and the traffic before the fix does not count.
- **Not back.** When every occurrence in the window precedes the resolution, the finding keeps its kind, carries its `resolution`, and is ranked with the acknowledged findings, after every other one; `hideAcked=true` leaves it out as well, and in the text rendering its severity column reads `resolved`.

The heading of the text rendering counts the second kind: `(… 12 requests, 2 acked, 1 resolved)`, and the JSON carries the count as `resolved` beside `acked`.
`check` counts regressions with `maxRegressions` ([Check](#check)); a regressed `n-plus-one` still counts to `maxNPlusOne` as well, and a regressed `log-error` to `maxLogErrors`.
Resolutions are not exported, as acknowledgements are not.

The CLI commands are `resolve <finding id> [--note=…]` and `unresolve <finding id>`, and the MCP tool is `resolve`; both CLI commands write in the direct-file path as `mark` does.
The loop resolves a finding once `check` over the run after the fix passes, with what the fix was as the note ([Skills](#skills)).

## State

Every finding carries `state`, which says on its own what the last restart changed, so `findings --since=start` names the findings the last change introduced without a second window; it is the automatic counterpart of [Compare](#compare), which needs two marks.

| State | When |
|---|---|
| `regressed` | the finding is a `regression` |
| `new` | the rules do not produce its id over the previous run of its service |
| `ongoing` | they do |

The **previous run** of a service is the time between its two newest `start` marks at or before the end of the window ([Marks](#marks)): from the older of the two, up to the newest one.
With one `start` mark it reaches back to the oldest data; with none (an imported session without marks, a sender that reports no `process.pid`) there is no previous run, and every finding of the service is `new`.
The rules run over the previous run once per service of the answered page, with the same thresholds as the window's.
`check` does not read the state.

In the text rendering `state` is a column between `severity` and `kind`.

## One finding

`GET /api/findings/{id}?since&until&service` answers one finding of the window, so a person who sees it in the UI can hand exactly it to an agent (ui.md, "Copy as Markdown").
The rules run over the window as for the list, with no `limit` and acknowledged and resolved findings included, and the answer is the finding with that id and its `rank` among them; an id the rules do not produce over the window is `404`.

Its text rendering is written by the code that writes the list, so apart from the heading its lines are the list's bytes:

```
# finding n-plus-one:1d41bc5a9b2c  2026-09-18T12:37:06+09:00 → 12:41:08  (4m 1s, all services, 2456 requests)

| # | severity | state | kind | id | service | title |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | medium | new | n-plus-one | n-plus-one:1d41bc5a9b2c | orders | GET /orders/{id} runs SELECT order_line 6 times per request |

2. n-plus-one:1d41bc5a9b2c — 1 of 1 request repeated it; 6 times; 12.0 ms per request in that statement
   requests 1, affected 1, medianRepeats 6, maxRepeats 6, msPerRequest 12.0
   select * from order_line where order_id = ?
   traces: 4bf92f3577b34da6a3ce929d0e0e4736
```

- The heading is the list's with `finding <id>` in place of `findings`, over the same window and service.
- The table has the list's columns and the finding's one row, numbered by its rank, and the evidence block under it is the one `findings` prints for that number; `findings --since=<from> --until=<to> --limit=100` over the same window prints both unchanged, bar the suspect-change lines it adds under the code frames, which no HTTP answer carries ([Source lines and the suspect change](#source-lines-and-the-suspect-change)).

`GET /api/errors/{errorId}` and `GET /api/queries/{queryId}` answer the same way with `format=text`: the heading `# error <errorId>` or `# query <queryId>` over the window, then the `errors` or `queries` table with the group's one row, and for an error the sample's application frames as the list prints them.
The JSON of those two stays the pages' own (api.md).

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

`GET /api/export?since&until&service` (and `from`/`to`) without `traceId` exports the window: every service, span, log record, metric, metric series, metric point, tingle and mark, and the index catalog (`db_table`, [storage.md](storage.md)) of its services, as one JSON document, streamed as it is read, with `Content-Disposition: attachment; filename="spider-sense-<from>-<to>.json"`.
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
  "marks": [ { "atMs", "name", "service", "note" } ],
  "dbTables": [ { "service", "schemaName", "tableName", "product", "indexes": [ { "name", "unique", "columns": [ … ] } ], "seenMs" } ] }
```

Every key is its column's name in camelCase, so a section is the table it came from and an import binds it straight back: `at_ms` is `atMs`, `start_ms` is `startMs`, `parent_span_id` is `parentSpanId`.
Nothing is derived on the way out or recomputed on the way in — `entry`, `slow`, `queryId` and the rest travel as they were stored, because a document exported from one session must not change meaning under another machine's thresholds.
`dbTables` is cut by service and not by time: the extension reads a table's indexes once per process, so the row behind the window's statements was usually written before the window began, and every catalog row of the exported services travels (all of them without `service`).
Its `indexes` is the stored array as an array, or the stored text as a string when that text is not JSON.
With it, a `slow-query` or `n-plus-one` finding computed over the imported session has the [schema block](#the-schema-block) it had where it was recorded.

`POST /api/import` takes that document (`Content-Encoding: gzip` accepted) and answers `200` `{ "spans": n, "logs": n, "metricPoints": n, "tingles": n, "marks": n, "dbTables": n, "skippedTraces": n, "window": { "from": …, "to": … } }`.
Import keeps every timestamp as exported, so the reader sets the time range to the answer's `window` (or `all`).
It is idempotent enough for a file imported twice: a trace whose id already has rows in the store is skipped whole (its spans, logs and tingles; `skippedTraces` counts it), a metric point is merged on its `(series, at)` key with the series looked up or created by `(service, name, attributes)`, a service row is merged, a mark is skipped when one with the same name and instant exists, and a catalog row is merged on its key `(service, schema_name, table_name)` as the writer merges it ([storage.md](storage.md)), so the table the file describes replaces the row the store had for it.
A document whose `schema` is not this version's is a `400` naming both versions.
Acknowledgements and resolutions are not exported: they are the reader's, not the session's.

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

Rules are query parameters; every rule given is evaluated, and when none is given the default set is `maxErrors=0`, `maxNPlusOne=0`, `maxRegressions=0` and `maxP95Ms=<slow.request.ms>`.

| Rule | Actual value |
|---|---|
| `maxP95Ms` | the highest p95 of any endpoint in scope |
| `maxErrors` | error groups' occurrences summed |
| `maxErrorRate` | failed entry spans over entry spans |
| `maxQueriesPerRequest` | database spans per entry span, the highest of any endpoint |
| `maxSlowQueries` | query calls over `slow.query.ms` |
| `maxNPlusOne` | `n-plus-one` and `n-plus-one-http` findings, regressed ones included |
| `maxLogErrors` | `log-error` findings' uncovered records summed, regressed ones included |
| `maxRegressions` | `regression` findings: resolved findings that came back ([Resolutions](#resolutions)) |
| `minApdex` | the Apdex over the scope |

`endpoint` narrows the scope to one endpoint, by `endpointId` or by name (`GET /orders/{id}`); `maxNPlusOne` and `maxRegressions` then count the findings whose `subject.endpointId` is that endpoint.
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
The index catalog the extension reads is there too, as `db_table`: `SELECT table_name, indexes FROM db_table WHERE service = 'servlet-warehouse'` lists every table a slow statement of that service has touched, with its indexes as JSON.
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

**Methods.** `initialize` answers the client's `protocolVersion` when it is one the server knows (`2025-06-18`, `2025-03-26`, `2024-11-05`) and `2025-06-18` otherwise, `capabilities: { "tools": {} }`, `serverInfo: { "name": "spider-sense", "version": "<version>" }`, and `instructions`, the loop in one paragraph: start the application under the agent, `mark`, exercise, `findings`, fix, `mark`, `compare`, `check`, and `resolve` once the fix holds. `notifications/initialized` is accepted and ignored. `ping` answers `{}`. `tools/list` is the seven tools below; `tools/call` runs one. Anything else is `-32601`.

**Tools.** Every argument is the CLI option of the same meaning; a selector is a string as in [Time selectors](#time-selectors).

| Tool | Arguments | Answers |
|---|---|---|
| `findings` | `since`, `until`, `service`, `limit` (1–100), `full`, `hideAcked` | the findings text |
| `trace` | `traceId` (required), `full`, `diff` | the trace tree, or the two traces aligned when `diff` names a second one ([Trace diff](#trace-diff)) |
| `mark` | `name` (required, `[A-Za-z0-9._-]{1,64}`), `note`, `service` | the mark, as the CLI prints it |
| `resolve` | `findingId` (required), `note` | the resolution, as the CLI prints it ([Resolutions](#resolutions)) |
| `compare` | `before`, `after` (both required), `until`, `service` | the compare text |
| `check` | `since`, `until`, `service`, `endpoint`, `maxP95Ms`, `maxErrors`, `maxErrorRate`, `maxQueriesPerRequest`, `maxSlowQueries`, `maxNPlusOne`, `maxLogErrors`, `maxRegressions`, `minApdex` | the check text; `structuredContent` carries `{ "pass": true \| false \| null, "requests": n }` so a host need not read the heading for the verdict |
| `sql` | `sql` (required), `limit` (1–5000) | the sql text |

A result is `{ "content": [ { "type": "text", "text": "<the Markdown>" } ] }`.
What the CLI reports on stderr with exit code `4` (no such trace, no such mark) or as a `400` (a refused statement, a bad selector) is a tool result with `isError: true` whose text is that message on one line; a missing required argument or an unknown tool is a JSON-RPC `-32602`.
Tool descriptions are one sentence each and say when to use the tool, not how the output looks; the output is the text rendering and needs no description.

**`init --mcp`** writes the stdio server into the project's `.mcp.json` ([init](#init)) as `mcpServers.spider-sense = { "command": "java", "args": ["-jar", "<jar>", "mcp"] }`, keeping every other entry of an existing file.
A host that reaches the server over HTTP is configured by hand with `{ "type": "http", "url": "http://127.0.0.1:4000/mcp" }`.

## Text rendering

Any endpoint listed here answers Markdown when asked with `format=text` or with an `Accept` header whose first type is `text/markdown` or `text/plain`; the response is `text/markdown; charset=utf-8`.
JSON stays the default.

Endpoints with a text rendering: `/api/status`, `/api/findings`, `/api/findings/{id}`, `/api/marks`, `/api/compare`, `/api/check`, `/api/sql`, `/api/traces`, `/api/traces/{id}`, `/api/endpoints`, `/api/queries`, `/api/queries/{queryId}`, `/api/errors`, `/api/errors/{errorId}`, `/api/logs`, `/api/services`.

Every example below is output captured from `scripts/demo-shared.sh` with `silk-bookstore` and `spring-orders` running under the agent and forwarding to one standalone Spider Sense, with the home directory anonymised.

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
| `findings [--hide-acked] [--no-git]` | the findings of the window, with the suspect change under each code frame ([Source lines and the suspect change](#source-lines-and-the-suspect-change)) unless `--no-git` |
| `ack <finding id> [--note=…]`, `unack <finding id>` | acknowledges a finding, or withdraws that ([Acknowledgements](#acknowledgements)) |
| `resolve <finding id> [--note=…]`, `unresolve <finding id>` | resolves a finding, so it is a `regression` if it comes back, or withdraws that ([Resolutions](#resolutions)) |
| `trace <traceId> [--full] [--diff=<traceId>]` | one trace as a tree, or two aligned ([Trace diff](#trace-diff)) |
| `tail [--kind=] [--until-traces=] [--timeout=]` | tingles as they arrive ([Tail](#tail)) |
| `export [--out=<file>]`, `import <file>` | the window as one JSON document, and back ([Export and import](#export-and-import)) |
| `traces [--status=error\|ok] [--min-ms=] [--q=] [--limit=20]` | the newest traces |
| `endpoints`, `queries`, `errors` | the tables of the window |
| `logs [--severity=WARN] [--q=] [--trace=<traceId>]` | log lines |
| `mark <name> [--note=…]` | records a mark now |
| `marks` | lists marks |
| `compare --before=<selector> --after=<selector> [--until=<selector>]` | the two windows side by side |
| `check [--max-p95-ms=] [--max-errors=] [--max-error-rate=] [--max-queries-per-request=] [--max-slow-queries=] [--max-n-plus-one=] [--max-log-errors=] [--max-regressions=] [--min-apdex=] [--endpoint=]` | pass or fail, in the exit code |
| `sql "<statement>" [--limit=200]` | one read-only statement over the schema of storage.md |
| `init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]` | writes the Spider Sense block into the project's `CLAUDE.md` and installs the skills into its `.claude/skills/`; `--mcp` also writes the stdio MCP server into its `.mcp.json` |
| `mcp` | the MCP server over stdio ([MCP](#mcp)); takes `--url` and `--db` and nothing else |
| `help` | this table |

Common options: `--since=<selector>` (default `15m`), `--until=<selector>`, `--service=<name>`, `--limit=<n>` (the lists: findings, traces, queries, errors, logs, marks, and the rows of `sql`; `endpoints` always lists every endpoint of the window), `--url=<base url>` (default `http://127.0.0.1:4000`, or `SPIDERSENSE_URL`, or the URL the `spidersense.*` properties imply: `spidersense.collector` when set, else host and port; the launcher fills those in from the properties file of the working directory, [design.md](design.md#configuration)), `--db=<path or jdbc url>`, `--json`, `--full`.
`compare` takes no `--since`: its windows are the two selectors.
`init` takes none of them at all: it asks nothing and nobody, and its own options are `--dir`, `--jar`, `--no-skill` and `--mcp` ([init](#init)).

Output is the text rendering above; `--json` prints the JSON instead.
The CLI does not render anything itself: when a Spider Sense is running it fetches `format=text` and prints the body, and when none answers at `--url` it opens the database in process, runs the same queries and the same renderer, and says so on stderr:

```
(no Spider Sense at http://127.0.0.1:4000; reading /home/me/db/spider-sense/sense.mv.db directly)
```

That is what `AUTO_SERVER=TRUE` buys: the application has crashed, the UI went with it, and `findings --since=start` still answers.
The one exception to printing what was rendered is the suspect-change line `findings` adds under each code frame ([Source lines and the suspect change](#source-lines-and-the-suspect-change)): it is read from the repository the CLI runs in, added to the text after the server or the in-process renderer produced it, and left out by `--no-git` and by `--json`.
In that path the thresholds are the defaults or `--slow.request.ms`/`--slow.query.ms`, and the application packages `--app.packages`, since no server is there to ask.
The file must exist and carry this version's schema: the CLI never creates a database and never upgrades one, because `AUTO_SERVER=TRUE` may have joined the database of an older Spider Sense that is still running, and the server's own open would drop its tables (storage.md).
A missing file or another schema version is a message on stderr and exit code 2.

Exit codes: `0` success (and `check` passed), `1` `check` failed, `2` usage or connection error, `3` `check` had no request to judge, `4` not found (a trace id, a mark name, a finding id to `unack` or `unresolve`).

## Skills

Two, each a directory under `skills/`, in the same form as Spider Silk's skill: a `SKILL.md` with references beside it.
`init` installs a copy of every one of them into a project's `.claude/skills/` ([init](#init)).

`skills/spider-sense/SKILL.md` is the loop.
It is what an agent reads to run it without being told how:

1. Start the application under the agent (`-javaagent`, or `JAVA_TOOL_OPTIONS` when the start command is not the agent's to change), and confirm with `status`.
2. `mark before`, exercise the endpoints in question (or run the tests, or the load generator).
3. `findings --since=before`; read the top finding, its suspect-change lines first (is the frame's line in the change just made?), then open its trace and the code.
4. Fix; restart if needed (`since=start` then covers the new run).
5. `mark after`, exercise the same way, `compare --before=before --after=after`, `check`.
6. Once `check` passes, `resolve <finding id> --note=<the fix>`, so the finding is a `regression`, ranked first, if a later change brings it back.

The references list the finding kinds with the fix each usually wants (a fetch join or a batch for `n-plus-one`, an index or a rewrite for `slow-query`, and so on), the CLI table above, and how to start each kind of application under the agent (Gradle `run`, Spring Boot `bootRun`, a plain `java -jar`, a test task).
The query-tuning skill beside it, `skills/spider-sense-sql-tuning`, reads the finding's schema block for the indexes a table already has and the predicate columns none leads with, and designs the index from that rather than asking the database.

`skills/spider-sense-sql-tuning/SKILL.md` is query tuning: the index to add, the rewrite, the fetch join, the batch, each verified with `compare` and `check`.
It is read when a finding names a statement rather than a request.

## init

```
java -jar spider-sense.jar init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]
```

`init` prepares a project to be worked on under Spider Sense, and it is the one command that reads nothing: no HTTP, no database, no running Spider Sense.
It writes a short block into the project's `CLAUDE.md` and copies the skills into the project's `.claude/skills/`.
`--dir` is the project directory and defaults to the working directory.

**The jar path.** `--jar` when it is given, otherwise the distributable jar the command was started from: the launcher sets the system property `spidersense.jar` to its own absolute path before it invokes the CLI, because the CLI itself runs out of the nested server jar extracted to a temporary directory and could never find the distributable on its own.
The path is written absolute, as given or as discovered, and never made relative to the project.
When neither is known — exploded classes in an IDE, and no `--jar` — `init` says which option it needs and exits `2`.

**The block.** It is delimited by `<!-- spider-sense:start -->` and `<!-- spider-sense:end -->`, each on a line of its own, and this is it:

````markdown
<!-- spider-sense:start -->
## Spider Sense

Spider Sense is a local-development observability tool for this project, and the jar is at `/home/me/tools/spider-sense.jar`.
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
Query tuning — an index to add, a rewrite, a fetch join, a batch — is in the skill at `.claude/skills/spider-sense-sql-tuning/SKILL.md`.
<!-- spider-sense:end -->
````

`/home/me/tools/spider-sense.jar` above is the jar path; everything else is written as it stands, and the block is generated from one place in the code.
A second `init` replaces everything between the markers, including when the jar path has changed, and leaves the rest of the file byte for byte as it was; nothing else in the file is parsed or reformatted.
When `CLAUDE.md` does not exist it is created with the block alone; when it exists without the markers the block is appended after one blank line.
The last two lines name `skills/spider-sense/` and `skills/spider-sense-sql-tuning/` of the Spider Sense repository (<https://github.com/benelog/spider-sense>) instead of the project's own copies when `--no-skill` kept the skills from being installed.
No port of the project is written: the block names the Spider Sense UI's own default, `http://127.0.0.1:4000`, and nothing else.

**The skills.** `init` copies every directory under `skills/` — each one's `SKILL.md` and `references/*.md` — into `<dir>/.claude/skills/<the same name>/`, overwriting the files it owns and leaving anything else in those directories alone, unless `--no-skill` is given, which skips all of them.
A copy rather than a pointer, because the jar is the distributable and the repository it was built from may not be on the machine at all.
The files travel inside the jar: the server module's build packages the repository's whole `skills/` directory into the resources under `spider-sense/skills/`, together with a generated `spider-sense/skills/index.txt` listing the paths relative to `skills/` (`spider-sense/SKILL.md`, `spider-sense/references/cli.md`, `spider-sense-sql-tuning/SKILL.md`, and so on), since a class loader cannot list a directory.
A new skill is therefore a new directory under `skills/` and nothing else.
The repository's `skills/` stays the single source; nothing is duplicated under `src/main/resources`.

**What it prints**, one line each, on stdout, and then exit `0`:

```
wrote CLAUDE.md block (jar: /home/me/tools/spider-sense.jar)
installed skill to /home/me/project/.claude/skills/spider-sense (5 files)
installed skill to /home/me/project/.claude/skills/spider-sense-sql-tuning (4 files)
```

One line per skill, in the index's order, each with the number of files it wrote.
The first line is `updated CLAUDE.md block (…)` when the markers were already in the file, and the skill lines are replaced by the single line `skipped skills (--no-skill)` when the skills were not installed.

**`--mcp`.** A last line, `wrote .mcp.json (spider-sense over stdio)` or `updated .mcp.json (…)`, when the option is given: `<dir>/.mcp.json` gets `mcpServers.spider-sense` set to `{ "command": "java", "args": ["-jar", "<jar path>", "mcp"] }`, the same absolute jar path as the block.
An existing file is parsed as JSON and every other entry is kept, though the file is rewritten in the server's own JSON formatting; a file that is not a JSON object is left alone with a message on stderr and exit code `2`.
Without `--mcp` nothing is written and nothing is printed about it: a host with a shell is meant to use the CLI ([Choosing an interface](#choosing-an-interface)), and `init` should not hand it a second tool for the same answers.

## Considered and deferred

- **MCP over HTTP only.** The first design served MCP on the existing port and nothing else, which is the least code. Rejected as the only transport because a host on the same machine then loses the one property that makes the CLI trustworthy after a crash: the H2 file can be read with the server gone. The stdio transport reuses the CLI's own decision (a server if one answers, the file otherwise) and adds no third way of answering.
