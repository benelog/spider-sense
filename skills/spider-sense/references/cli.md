# The Spider Sense CLI

```
java -jar spider-sense.jar <command> [arguments] [options]
```

The launcher treats a first argument that does not start with `-` as a command and hands the whole line to the CLI inside the nested server jar.
`--port=…` and the other standalone flags still start a server instead, so `java -jar spider-sense.jar` with no command is the standalone UI, not a command.

## Commands

| Command | Does |
|---|---|
| `status` | what is running, where the database is, how much it holds |
| `findings [--hide-acked]` | the findings of the window |
| `ack <finding id> [--note=…]` | accepts a known finding, which is then ranked after every other one, its severity reading `acked` |
| `unack <finding id>` | withdraws that acknowledgement; exit code `4` when there was none |
| `trace <traceId> [--full]` | one trace as a tree |
| `traces [--status=error\|ok] [--min-ms=] [--q=] [--limit=20]` | the newest traces |
| `endpoints`, `queries`, `errors` | the tables of the window |
| `logs [--severity=WARN] [--q=] [--trace=<traceId>]` | log lines |
| `mark <name> [--note=…]` | records a mark now |
| `marks` | lists marks |
| `compare --before=<selector> --after=<selector> [--until=<selector>]` | the two windows side by side |
| `check [--max-p95-ms=] [--max-errors=] [--max-error-rate=] [--max-queries-per-request=] [--max-slow-queries=] [--max-n-plus-one=] [--max-log-errors=] [--min-apdex=] [--endpoint=]` | pass or fail, in the exit code |
| `sql "<statement>" [--limit=200]` | one read-only statement over the store, for a question no other command answers ([sql.md](sql.md)) |
| `export [--out=<file>]` | the window as one JSON document, to the file or to stdout; a name ending in `.gz` is gzipped |
| `import <file>` | that document back into the store, and one line saying what arrived |
| `init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]` | writes the Spider Sense block into the project's `CLAUDE.md` and installs this skill into its `.claude/skills/`; `--mcp` also writes the stdio MCP server into its `.mcp.json` |
| `mcp` | the MCP server over stdio, for a host that has no shell; it takes `--url` and `--db` and nothing else ([MCP over stdio](#mcp-over-stdio)) |
| `help` | this table |

## Common options

| Option | Default | Meaning |
|---|---|---|
| `--since=<selector>` | `15m` | start of the window |
| `--until=<selector>` | `now` | end of the window |
| `--service=<name>` | every service | narrow to one service |
| `--limit=<n>` | per list | how many rows: findings 20, traces 20, queries 100, errors 100, logs 200, marks 50, sql 200 (at most 5000) |
| `--url=<base url>` | `http://127.0.0.1:4000`, or `SPIDERSENSE_URL` | which Spider Sense to ask |
| `--db=<path or jdbc url>` | `~/db/spider-sense/sense` | read that database directly, without asking any server |
| `--json` | off | print the JSON of api.md instead of the text |
| `--full` | off | keep statements whole and expand collapsed spans |
| `--hide-acked` | off | `findings` only: leave the acknowledged findings out instead of ranking them last |

`compare` takes no `--since`: its windows are the two selectors, and `--until` closes the second one.
`init` takes none of these: it reads nothing, and its own options are `--dir=<project dir>` (the working directory by default), `--jar=<path>` (the jar it was started from by default), `--no-skill` and `--mcp`.
`mcp` takes only `--url` and `--db`, because a window, a format and a service belong to each message of the session rather than to the command.
It is idempotent — the block it writes is delimited by `<!-- spider-sense:start -->` and `<!-- spider-sense:end -->`, and a second run replaces what is between them and leaves the rest of `CLAUDE.md` untouched.
`--slow.request.ms=`, `--slow.query.ms=` and `--app.packages=` set the thresholds and the application packages in the direct-file path, where no server is there to ask.
An option a command does not take is a usage error rather than a silently ignored word, so a mistyped `--sinse` is told rather than answered for the last 15 minutes.

## Time selectors

`since` and `until` take a selector rather than epoch milliseconds, because an agent thinks in "since I changed the code".

| Form | Example | Meaning |
|---|---|---|
| duration | `30s`, `5m`, `2h`, `1d` | that long before `until` (for `since`) or before now (for `until`) |
| epoch milliseconds | `1758000000000` | the instant, 13 or more digits |
| mark name | `before`, `after-fix` | the newest mark with that name |
| `start` | `start` | the newest automatic start mark, of `--service` when one is given |
| `now` | `now` | now; the default for `until` |

A `since` that resolves to a moment after `until` is an error, and a mark name that matches no mark is a not-found naming it.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | success, and `check` passed |
| `1` | `check` failed |
| `2` | usage or connection error |
| `3` | `check` had no request to judge |
| `4` | not found: a trace id, a mark name, a finding id to `unack` |

A trace id that matches nothing prints `spider-sense: No such trace: <id>` on stderr and exits `4`, whether the answer came over HTTP or from the file; a mark name that matches no mark does the same, naming the mark, and so does `unack` with `spider-sense: No such acknowledgement: <id>`.

## The direct-file fallback

When a Spider Sense answers at `--url`, the CLI fetches `format=text` and prints the body; it renders nothing itself, so the numbers are the numbers the UI shows.
When none answers and no `--url` was named, it opens the database in process, runs the same queries through the same renderer, and says so on stderr:

```
(no Spider Sense at http://127.0.0.1:4000; reading /home/me/db/spider-sense/sense.mv.db directly)
```

That is what `AUTO_SERVER=TRUE` buys: the application has crashed, the UI went with it, and `findings --since=start` still answers.
A `--url` that was named is a statement that there is a server there, so nothing answering it is `spider-sense: no Spider Sense at <url> (…)` and exit `2` rather than a silent fall back to a file that may hold a different application.
`--db=<path or jdbc url>` is the opposite statement, about where to read, and goes straight to the file without asking any server.

In that path the thresholds are the defaults or whatever `--slow.request.ms`, `--slow.query.ms` and `--app.packages` say.
The file must exist and carry this version's schema: the CLI never creates a database and never upgrades one, because `AUTO_SERVER=TRUE` may have joined the database of an older Spider Sense that is still running, and recreating the tables would empty it under that server.
A missing file is exit `2` and, on stderr:

```
spider-sense: no Spider Sense database at /home/me/db/spider-sense/sense.mv.db; start an application with -javaagent:spider-sense.jar first
```

A file of another schema version is refused the same way, with exit `2` and a message naming both versions.

## MCP over stdio

`mcp` is the same six answers spoken as the Model Context Protocol, for a host that cannot run a command at all.
You have a shell, so this is not your interface: use the commands above, and reach for `mcp` only when the user asks how to wire Spider Sense into Claude Desktop, an IDE chat panel or another host without one.

```
java -jar spider-sense.jar mcp [--url=<base url>] [--db=<path or jdbc url>]
```

It reads newline-delimited JSON-RPC on stdin and writes it on stdout, nothing else on stdout, and ends at end of input.
`initialize`, `ping` and `tools/list` are answered in the process; a tool call goes to the Spider Sense at `--url` when one answers and to the H2 file when none does, exactly as every command here decides it, so MCP still answers after the application has crashed.
The tools are `findings`, `trace`, `mark`, `compare`, `check` and `sql`, their arguments are the options of the same name, and each answers the same Markdown the matching command prints over the same window.

`java -jar spider-sense.jar init --mcp` writes that server into the project's `.mcp.json` as `mcpServers.spider-sense`, keeping every other entry, and prints a third line saying so; a host that reaches a running Spider Sense over HTTP instead is configured by hand with `{"type": "http", "url": "http://127.0.0.1:4000/mcp"}`.
Do not enable both the CLI and MCP in one host: two tools that give the same answer make the model choose between them and cost the schema twice.

## Text rendering conventions

Every answer follows the same rules, so the output is small, stable and diffable.

- The first line is a heading naming what it is and the window, in ISO-8601 with the local offset; the range is rounded to the second and written in its largest units (`6s`, `2m 30s`, `15m`, `2h`), never in milliseconds.
- Lists are Markdown tables, and ids are complete: a trace id is 32 hex characters, an endpoint, query or error id 12, because they are passed back.
- A trace and `logs` are lines rather than a table, and `status` and `mark` answer in their own shape.
- Durations are milliseconds with one decimal and a thousands separator (`1,532.4 ms`), counts are integers, rates are percentages with one decimal, an Apdex is a score with three decimals.
- A cell with nothing in it is `—`, and in `compare` the two sides of a row share one cell, `before → after`.
- A statement is cut at 200 characters with `…`; `--full` keeps it whole.
- Nothing in the body depends on when it was rendered, only on the window; `now` appears only in the heading.
- An empty result says what was looked for and where, and, when there was no request at all, how to send some.

## Examples

Everything below is the real output of one session against the `spring-orders` example application, in the order the commands were run.
That Spider Sense was on another port, so each command also carried `--url=http://127.0.0.1:4100`, which is left out here.

### `status`

```
$ java -jar spider-sense.jar status
# status

| field | value |
| --- | --- |
| name | Spider Sense 0.1.0 |
| mode | agent |
| endpoint | http://127.0.0.1:4100 |
| started | 2026-09-17T08:19:14.060+09:00 |
| embedded service | spring-orders |
| thresholds | slow request 500 ms, slow query 100 ms |
| retention | 24 hours |
| database | /home/me/db/spider-sense/sense.mv.db |
| database size | 110395392 bytes |
| spans | 51785 |
| traces | 51257 |
| logs | 66 |
| metric series | 67 |
| services | 1 |
| oldest span | 2026-09-17T08:14:07.250+09:00 |
```

### `mark`

```
$ java -jar spider-sense.jar mark before
mark before at 2026-09-17T08:19:28.122+09:00
```

A note is kept with the moment, and the mark names the service when one was given.

```
$ java -jar spider-sense.jar mark after --note="after the fix"
mark after at 2026-09-17T08:19:35.837+09:00 — after the fix
```

### `marks`

```
$ java -jar spider-sense.jar marks
# marks

| at | name | service | note |
| --- | --- | --- | --- |
| 2026-09-17T08:19:35.837+09:00 | after | — | after the fix |
| 2026-09-17T08:19:28.122+09:00 | before | — | — |
| 2026-09-17T08:19:16.727+09:00 | start | spring-orders | pid 178056 |
| 2026-09-17T08:16:42.507+09:00 | file-mode | — | — |
| 2026-09-17T08:15:38.928+09:00 | after | — | — |
| 2026-09-17T08:14:20.087+09:00 | before | — | — |
| 2026-09-17T08:14:06.284+09:00 | start | spring-orders | pid 170140 |
```

The newest 50, newest first; a `start` mark with a `pid` note is the one the writer records by itself whenever a service restarts, so `--since=start` needs no cooperation from anyone.

### `findings`

```
$ java -jar spider-sense.jar findings --since=before
# findings  2026-09-17T08:19:28+09:00 → 08:19:34  (6s, all services, 21 requests)

| # | severity | kind | id | service | title |
| --- | --- | --- | --- | --- | --- |
| 1 | high | error | error:eecf9878a68f | spring-orders | IllegalStateException in GET /api/flaky |
| 2 | medium | n-plus-one | n-plus-one:4c5f46be8bc5 | spring-orders | GET /api/orders/{id}/enriched runs SELECT product 5 times per request |
| 3 | medium | n-plus-one | n-plus-one:e6d97708ab26 | spring-orders | GET /api/orders/{id} runs SELECT product 5 times per request |
| 4 | medium | slow-query | slow-query:6b3aae6f9bef | spring-orders | SELECT p.id AS product_id, p.sku AS sku, p.name AS name, SUM… is slow |
| 5 | medium | slow-query | slow-query:aae3ff87821a | spring-orders | SELECT o.status AS status, CAST(o.created_at AS DATE) AS ord… is slow |
| 6 | medium | slow-endpoint | slow-endpoint:786b594455d0 | spring-orders | GET /api/reports/revenue is slow |

1. error:eecf9878a68f — 1 occurrence in GET /api/flaky; Payment gateway timeout
   count 1, firstSeen 2026-09-17T08:19:28.962+09:00, lastSeen 2026-09-17T08:19:28.962+09:00, type java.lang.IllegalStateException, message Payment gateway timeout, endpoints [name GET /api/flaky count 1]
   orders.web.MiscController.flaky(MiscController.java:24)
   traces: 5e6769395a77c8f192949af91cbdedf2

2. n-plus-one:4c5f46be8bc5 — 1 of 3 requests repeated it; 5 times; 0.4 ms per request in that statement
   requests 3, affected 1, medianRepeats 5, maxRepeats 5, msPerRequest 0.4
   select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=?
   traces: e7cfa77cb4c9135b623a04e470ef58d0

3. n-plus-one:e6d97708ab26 — 1 of 3 requests repeated it; 5 times; 0.8 ms per request in that statement
   requests 3, affected 1, medianRepeats 5, maxRepeats 5, msPerRequest 0.8
   select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=?
   traces: 0936e867b3ba9250964b7efa08029ee4

4. slow-query:6b3aae6f9bef — p95 269.7 ms over 3 calls, 3 of them over 100 ms; 782.8 ms in total
   calls 3, slowCalls 3, p50Ms 266.6, p95Ms 269.7, maxMs 269.7, totalMs 782.8, callers [endpoint GET /api/reports/revenue service spring-orders calls 3]
   SELECT p.id AS product_id, p.sku AS sku, p.name AS name, SUM(l.quantity) AS quantity, SUM(l.quantity * l.unit_price) AS revenue FROM order_line l JOIN product p ON p.id = l.product_id JOIN orders o ON…
   traces: 42800a1fc7ca5ee2dec08f804cef8be6 a86465f5a9242ac8496577740771fcb7 cbc8be4f6ec5a1c033e57cbb7f269bd5

5. slow-query:aae3ff87821a — p95 200.2 ms over 3 calls, 3 of them over 100 ms; 476.6 ms in total
   calls 3, slowCalls 3, p50Ms 141.3, p95Ms 200.2, maxMs 200.2, totalMs 476.6, callers [endpoint GET /api/reports/revenue service spring-orders calls 3]
   SELECT o.status AS status, CAST(o.created_at AS DATE) AS order_day, SUM(o.total) AS revenue, COUNT(DISTINCT o.id) AS order_count, SUM(l.quantity) AS item_count FROM orders o JOIN order_line l ON l.ord…
   traces: 42800a1fc7ca5ee2dec08f804cef8be6 a86465f5a9242ac8496577740771fcb7 cbc8be4f6ec5a1c033e57cbb7f269bd5

6. slow-endpoint:786b594455d0 — p95 549.8 ms over 3 calls; 2.0 database calls and 419.8 ms per request, 93.0% of the time
   calls 3, p50Ms 411.5, p95Ms 549.8, maxMs 549.8, totalMs 1,354.9, apdex 0.833, dbCallsPerRequest 2.0, dbMsPerRequest 419.8, dbShare 93.0%
   traces: 42800a1fc7ca5ee2dec08f804cef8be6 a86465f5a9242ac8496577740771fcb7 cbc8be4f6ec5a1c033e57cbb7f269bd5
```

The table is the answer and the blocks under it are the evidence, one per row in the same order: the `why` sentence after the id, then the `numbers`, then the statement when the finding has one, then the application frames when any are known, then the trace ids.

### `trace <traceId>`

```
$ java -jar spider-sense.jar trace 5e6769395a77c8f192949af91cbdedf2
# trace 5e6769395a77c8f192949af91cbdedf2  2026-09-17T08:19:28.962+09:00  22.0 ms  spring-orders  1 spans, 0 db, 1 error

offset     duration  span
0.0 ms     22.0 ms   SERVER spring-orders GET /api/flaky → 500  [error]
                       exception IllegalStateException: Payment gateway timeout
                       orders.web.MiscController.flaky(MiscController.java:24)

logs (2)
08:19:28.963  WARN   orders.web.MiscController  Flaky endpoint failing this time: payment gateway timeout
08:19:28.965  ERROR  org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]  Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: java.lang.IllegalStateException: Payment gateway timeout] with root cause
```

An error span carries its exception and the application frames underneath, and the trace's log lines follow, oldest first.

A trace with a collapsed group and calls out of the service:

```
$ java -jar spider-sense.jar trace e7cfa77cb4c9135b623a04e470ef58d0
# trace e7cfa77cb4c9135b623a04e470ef58d0  2026-09-17T08:19:28.782+09:00  174.5 ms  spring-orders  15 spans, 8 db, 0 errors

offset     duration  span
0.0 ms     174.5 ms  SERVER spring-orders GET /api/orders/{id}/enriched → 200
3.6 ms     2.0 ms      INTERNAL OrderRepository.findById
3.9 ms     1.5 ms        INTERNAL Session.find orders.domain.Order
4.5 ms     0.2 ms          db SELECT orders
6.0 ms     0.2 ms      db SELECT order_line
7.3 ms     0.4 ms      db SELECT product  × 5, 0.1 ms avg, 0.4 ms total
                         select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=?
9.0 ms     0.0 ms      db SELECT customer
9.3 ms     0.1 ms      INTERNAL Transaction.commit
42.4 ms    89.6 ms     CLIENT GET http://localhost:8081/api/books/106 → 200
162.5 ms   2.5 ms      CLIENT GET http://localhost:8081/api/books/49 → 200
165.9 ms   1.5 ms      CLIENT GET http://localhost:8081/api/books/64 → 200
```

Two spaces of indentation per depth, the service named only where it changes from the parent, consecutive identical siblings collapsed after the third into `× n` with the average and the total, and the statement of a slow or collapsed database span on the line below it.
`--full` expands the group and keeps the statements whole.

### `traces`

```
$ java -jar spider-sense.jar traces --since=before --limit=5
# traces  2026-09-17T08:19:28+09:00 → 08:19:35  (8s, all services, 21 requests)

5 of 21 traces, newest first

| start | duration | trace | root | service | spans | db | errors | status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 08:19:29.969 | 3.5 ms | b02e7baec05adb0651ad0671066bb4e0 | POST /api/orders/{id}/pay | spring-orders | 4 | 1 | 0 | 404 |
| 08:19:29.960 | 3.0 ms | 3fde475ce08a20f45944dd769739de28 | GET /api/customers/search | spring-orders | 5 | 1 | 0 | 200 |
| 08:19:29.956 | 0.7 ms | ce13899741ff879e1c559a8f1fa3a947 | GET /api/flaky | spring-orders | 1 | 0 | 0 | 200 |
| 08:19:29.939 | 12.6 ms | 739be08737bae7de506fdb0a8da510f8 | GET /api/orders/{id}/enriched | spring-orders | 13 | 6 | 0 | 200 |
| 08:19:29.928 | 8.3 ms | e81b89edfc36cdee67000892660e3a18 | GET /api/orders/{id} | spring-orders | 10 | 6 | 0 | 200 |
```

### `endpoints`

```
$ java -jar spider-sense.jar endpoints --since=before
# endpoints  2026-09-17T08:19:28+09:00 → 08:19:34  (7s, all services, 21 requests)

| endpoint | id | service | calls | errors | p50 | p95 | max | total | apdex |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GET /api/reports/revenue | 7342d163d1ee | spring-orders | 3 | 0 | 411.5 ms | 549.8 ms | 549.8 ms | 1,354.9 ms | 0.833 |
| GET /api/orders/{id}/enriched | 8c62f8d5090d | spring-orders | 3 | 0 | 12.6 ms | 174.5 ms | 174.5 ms | 195.7 ms | 1.000 |
| GET /api/orders/{id} | 264871b2ef8c | spring-orders | 3 | 0 | 8.3 ms | 66.5 ms | 66.5 ms | 78.9 ms | 1.000 |
| GET /api/customers/search | cf03d98e70da | spring-orders | 3 | 0 | 6.9 ms | 29.6 ms | 29.6 ms | 39.5 ms | 1.000 |
| GET /api/flaky | 40e6cdb7cd69 | spring-orders | 3 | 1 | 2.8 ms | 22.0 ms | 22.0 ms | 25.5 ms | 0.667 |
| POST /api/orders/{id}/pay | 48e41c5bc06c | spring-orders | 3 | 0 | 3.6 ms | 8.6 ms | 8.6 ms | 15.7 ms | 1.000 |
| GET / | a239b7c70ac8 | spring-orders | 3 | 0 | 1.3 ms | 5.2 ms | 5.2 ms | 7.3 ms | 1.000 |
```

### `queries`

```
$ java -jar spider-sense.jar queries --since=before --limit=5
# queries  2026-09-17T08:19:28+09:00 → 08:19:35  (7s, all services, 21 requests)

| id | service | calls | slow | p50 | p95 | max | total | callers | statement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e0e233d8ad75 | spring-orders | 3 | 3 | 266.6 ms | 269.7 ms | 269.7 ms | 782.8 ms | GET /api/reports/revenue ×3 | SELECT p.id AS product_id, p.sku AS sku, p.name AS name, SUM(l.quantity) AS quantity, SUM(l.quantity * l.unit_price) AS revenue FROM order_line l JOIN product p ON p.id = l.product_id JOIN orders o ON… |
| 4080653c9caa | spring-orders | 3 | 3 | 141.3 ms | 200.2 ms | 200.2 ms | 476.6 ms | GET /api/reports/revenue ×3 | SELECT o.status AS status, CAST(o.created_at AS DATE) AS order_day, SUM(o.total) AS revenue, COUNT(DISTINCT o.id) AS order_count, SUM(l.quantity) AS item_count FROM orders o JOIN order_line l ON l.ord… |
| a2ef3cae1e22 | spring-orders | 3 | 0 | 1.0 ms | 1.4 ms | 1.4 ms | 2.8 ms | GET /api/customers/search ×3 | select c1_0.id,c1_0.email,c1_0.name from customer c1_0 where lower(c1_0.name) like (?\|\|?\|\|?) escape ? order by c1_0.id fetch first ? rows only |
| 14eb3eff12dc | spring-orders | 22 | 0 | 0.1 ms | 0.2 ms | 0.2 ms | 2.0 ms | GET /api/orders/{id} ×11; GET /api/orders/{id}/enriched ×11 | select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=? |
| 62fb3169834e | spring-orders | 9 | 0 | 0.2 ms | 0.3 ms | 0.3 ms | 1.7 ms | GET /api/orders/{id} ×3; GET /api/orders/{id}/enriched ×3; POST /api/orders/{id}/pay ×3 | select o1_0.id,o1_0.created_at,o1_0.customer_id,o1_0.status,o1_0.total from orders o1_0 where o1_0.id=? |
```

### `errors`

```
$ java -jar spider-sense.jar errors --since=before
# errors  2026-09-17T08:19:28+09:00 → 08:19:35  (7s, all services, 21 requests)

| id | service | type | message | count | first | last | endpoints |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 458522ccda9f | spring-orders | java.lang.IllegalStateException | Payment gateway timeout | 1 | 08:19:28.962 | 08:19:28.962 | GET /api/flaky ×1 |

458522ccda9f — trace 5e6769395a77c8f192949af91cbdedf2
   orders.web.MiscController.flaky(MiscController.java:24)
```

Each group whose sample stack trace has application frames gets a block naming one trace and those frames.

### `logs`

```
$ java -jar spider-sense.jar logs --since=before --limit=3
# logs  2026-09-17T08:19:28+09:00 → 08:19:35  (8s, all services)

3 of 5 lines, newest first

08:19:29.922  INFO   orders.service.OrderService  Revenue report over 90 days: 244 groups, 10 top products, 391 ms  trace cbc8be4f6ec5a1c033e57cbb7f269bd5
08:19:29.459  INFO   orders.service.OrderService  Revenue report over 90 days: 244 groups, 10 top products, 408 ms  trace a86465f5a9242ac8496577740771fcb7
08:19:28.965  ERROR  org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]  Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: java.lang.IllegalStateException: Payment gateway timeout] with root cause  trace 5e6769395a77c8f192949af91cbdedf2
```

The count line says how many were printed of how many matched the window (`3 of 5 lines`), newest first; `traces` says the same about its page.

### `compare`

```
$ java -jar spider-sense.jar compare --before=before --after=after
# compare  2026-09-17T08:19:28+09:00 → 08:19:35  vs  2026-09-17T08:19:35+09:00 → 08:19:45  (all services)

| totals | before | after |
| --- | --- | --- |
| requests | 21 | 12 |
| errors | 1 | 1 |
| p95 | 411.5 ms | 1,201.7 ms |
| apdex | 0.929 | 0.792 |

## endpoints

| verdict | endpoint | id | calls | errors | p95 | db/req | db ms/req |
| --- | --- | --- | --- | --- | --- | --- | --- |
| new | GET /api/slow | 7c7ff534a0ec | — → 3 | — → 0 | — → 1,202.5 ms | — → 0.0 | — → 0.0 ms |
| better | GET /api/reports/revenue | 7342d163d1ee | 3 → 3 | 0 → 0 | 549.8 ms → 417.1 ms | 2.0 → 2.0 | 419.8 ms → 383.6 ms |
| better | GET /api/orders/{id} | 264871b2ef8c | 3 → 3 | 0 → 0 | 66.5 ms → 7.6 ms | 6.7 → 6.7 | 1.0 ms → 0.5 ms |
| better | GET /api/flaky | 40e6cdb7cd69 | 3 → 3 | 1 → 1 | 22.0 ms → 2.1 ms | 0.0 → 0.0 | 0.0 ms → 0.0 ms |
| gone | GET /api/orders/{id}/enriched | 8c62f8d5090d | 3 → — | 0 → — | 174.5 ms → — | 6.7 → — | 0.5 ms → — |
| gone | GET /api/customers/search | cf03d98e70da | 3 → — | 0 → — | 29.6 ms → — | 1.0 → — | 0.9 ms → — |
| gone | POST /api/orders/{id}/pay | 48e41c5bc06c | 3 → — | 0 → — | 8.6 ms → — | 1.0 → — | 0.2 ms → — |
| gone | GET / | a239b7c70ac8 | 3 → — | 0 → — | 5.2 ms → — | 0.0 → — | 0.0 ms → — |

## queries

| verdict | id | calls | calls/req | p95 | total | statement |
| --- | --- | --- | --- | --- | --- | --- |
| same | e0e233d8ad75 | 3 → 3 | 0.1 → 0.3 | 269.7 ms → 247.1 ms | 782.8 ms → 698.9 ms | SELECT p.id AS product_id, p.sku AS sku, p.name AS name, SUM(l.quantity) AS quantity, SUM(l.quantity * l.unit_price) AS revenue FROM order_line l JOIN product p ON p.id = l.product_id JOIN orders o ON… |
| same | 4080653c9caa | 3 → 3 | 0.1 → 0.3 | 200.2 ms → 164.5 ms | 476.6 ms → 451.9 ms | SELECT o.status AS status, CAST(o.created_at AS DATE) AS order_day, SUM(o.total) AS revenue, COUNT(DISTINCT o.id) AS order_count, SUM(l.quantity) AS item_count FROM orders o JOIN order_line l ON l.ord… |
| same | 14eb3eff12dc | 22 → 11 | 1.0 → 0.9 | 0.2 ms → 0.1 ms | 2.0 ms → 0.5 ms | select p1_0.id,p1_0.name,p1_0.price,p1_0.sku from product p1_0 where p1_0.id=? |
| same | 62fb3169834e | 9 → 3 | 0.4 → 0.3 | 0.3 ms → 0.2 ms | 1.7 ms → 0.3 ms | select o1_0.id,o1_0.created_at,o1_0.customer_id,o1_0.status,o1_0.total from orders o1_0 where o1_0.id=? |
| same | 6ece3eeacddc | 6 → 3 | 0.3 → 0.3 | 0.4 ms → 0.2 ms | 1.0 ms → 0.4 ms | select l1_0.order_id,l1_0.id,l1_0.product_id,l1_0.quantity,l1_0.unit_price from order_line l1_0 where l1_0.order_id=? |
| same | a3b4c9a4fdef | 6 → 3 | 0.3 → 0.3 | 0.2 ms → 0.1 ms | 0.6 ms → 0.2 ms | select c1_0.id,c1_0.email,c1_0.name from customer c1_0 where c1_0.id=? |
| gone | a2ef3cae1e22 | 3 → — | 0.1 → — | 1.4 ms → — | 2.8 ms → — | select c1_0.id,c1_0.email,c1_0.name from customer c1_0 where lower(c1_0.name) like (?\|\|?\|\|?) escape ? order by c1_0.id fetch first ? rows only |

## errors

| verdict | id | type | message | before | after |
| --- | --- | --- | --- | --- | --- |
| same | 458522ccda9f | java.lang.IllegalStateException | Payment gateway timeout | 1 | 1 |
```

Each row's verdict comes first and the worst rows come first, so the top of each table is the answer.

### `check`

```
$ java -jar spider-sense.jar check --since=before
# check  fail  2026-09-17T08:19:28+09:00 → 08:19:45  (17s, all services, 33 requests)

| rule | limit | actual | verdict | detail |
| --- | --- | --- | --- | --- |
| maxP95Ms | 500 | 1,202.5 | fail | GET /api/slow p95 1,202.5 ms over 3 calls |
| maxErrors | 0 | 2 | fail | 2 occurrences over the window |
| maxNPlusOne | 0 | 2 | fail | 2 findings: GET /api/orders/{id} runs SELECT product 5 times per request |

$ echo $?
1
```

With no rule given the defaults are `--max-errors=0`, `--max-n-plus-one=0` and `--max-p95-ms=<slow.request.ms>`.
Named rules replace them, and every rule named is evaluated:

```
$ java -jar spider-sense.jar check --since=after --max-queries-per-request=3 --min-apdex=0.9
# check  fail  2026-09-17T08:19:35+09:00 → 08:19:45  (10s, all services, 12 requests)

| rule | limit | actual | verdict | detail |
| --- | --- | --- | --- | --- |
| maxQueriesPerRequest | 3 | 6.7 | fail | GET /api/orders/{id} runs 6.7 database calls per request |
| minApdex | 0.900 | 0.792 | fail | Apdex 0.792 over 12 requests |

$ echo $?
1
```

Rules and the value each one measures:

| Rule | Actual value |
|---|---|
| `--max-p95-ms` | the highest p95 of any endpoint in scope |
| `--max-errors` | error groups' occurrences summed |
| `--max-error-rate` | failed entry spans over entry spans |
| `--max-queries-per-request` | database spans per entry span, the highest of any endpoint |
| `--max-slow-queries` | query calls over `slow.query.ms` |
| `--max-n-plus-one` | `n-plus-one` findings |
| `--max-log-errors` | `log-error` findings' uncovered records summed |
| `--min-apdex` | the Apdex over the scope |

`--endpoint=` narrows the scope to one endpoint, by `endpointId` or by name (`GET /api/orders/{id}`), and the heading names it.

### `help`

```
$ java -jar spider-sense.jar help
Spider Sense: ask a running Spider Sense, or the database file, from the terminal.

  java -jar spider-sense.jar <command> [arguments] [options]

Commands:
  status                       what is running, where the database is, how much it holds
  findings [--hide-acked]      the findings of the window
  ack <finding id> [--note=<text>]
                               accepts a known finding, so it is ranked last
  unack <finding id>           withdraws that acknowledgement
  trace <traceId> [--full]     one trace as a tree
  traces [--status=error|ok] [--min-ms=<n>] [--q=<text>] [--limit=20]
                               the newest traces
  endpoints                    the endpoints of the window
  queries                      the database statements of the window
  errors                       the errors of the window
  logs [--severity=WARN] [--q=<text>] [--trace=<traceId>]
                               log lines
  mark <name> [--note=<text>]  records a mark now
  marks                        lists marks
  compare --before=<selector> --after=<selector> [--until=<selector>]
                               the two windows side by side
  check [--max-p95-ms=] [--max-errors=] [--max-error-rate=]
        [--max-queries-per-request=] [--max-slow-queries=]
        [--max-n-plus-one=] [--max-log-errors=] [--min-apdex=] [--endpoint=]
                               pass or fail, in the exit code
  sql "<statement>" [--limit=200]
                               read-only SQL over the store (SELECT only)
  init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]
                               writes the Spider Sense block into the project's
                               CLAUDE.md and installs the skill into .claude/skills/;
                               --mcp also writes the stdio MCP server into .mcp.json
  mcp                          the MCP server over stdio, for a host with no shell;
                               takes --url and --db and nothing else
  help                         this table

Common options:
  --since=<selector>   default 15m
  --until=<selector>   default now
  --service=<name>     one service
  --limit=<n>          the lists: findings, traces, queries, errors, logs, marks,
                       and the rows of sql (default 200, at most 5000)
  --url=<base url>     default http://127.0.0.1:4000, or SPIDERSENSE_URL
  --db=<path or jdbc url>   read the database directly, without asking any server
  --json               the JSON of api.md instead of the text
  --full               whole statements, every repeated span
  --hide-acked         findings only: leave acknowledged findings out

A selector is a duration (30s, 5m, 2h, 1d), epoch milliseconds, a mark name,
start (the newest automatic start mark) or now.

With no --url and nothing listening, the database file is read in process; the
thresholds are then --slow.request.ms, --slow.query.ms and --app.packages, since
no server is there to ask.

Exit codes: 0 success, 1 check failed, 2 usage or connection error,
3 check had no request to judge, 4 not found (a trace id, a mark name,
a finding id to unack).
```
