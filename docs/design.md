# Spider Sense: design

Spider Sense is an observability tool for the local development loop, in the spirit of [Glowroot](https://glowroot.org/): one jar, attached to a JVM with one option, a UI in the browser a second later.
What sets it apart from Glowroot is that everything it collects arrives as OpenTelemetry data over OTLP.
The instrumentation is the stock OpenTelemetry Java agent, not a bytecode engine of our own, and the collector accepts OTLP/HTTP from any SDK in any language.

The UI is a Spider Silk application (`net.benelog.spidersilk`), and the product is a sibling of Spider Silk: same author, same logo family, same "thin by design" attitude.

## Influences

| Product | What Spider Sense takes from it |
|---|---|
| [Glowroot](https://glowroot.org/) | The deployment model: one jar, `-javaagent`, the UI served from the monitored JVM. The transaction/slow-trace/error/JVM page structure. |
| [Scouter](https://github.com/scouter-project/scouter) | The scatter (Scouter calls it the XLog): every request is a dot on a time × response-time scatter, errors in red, and a drag over a cluster of dots lists those traces. The profile view of one transaction as a step list with elapsed and gap times. The habit of keeping the last N minutes always visible. |
| [Pinpoint](https://github.com/pinpoint-apm/pinpoint) | The server map: services, databases and external hosts as nodes, calls as edges, a node click opening that node's numbers. The response summary (requests bucketed by response time, errors apart) and the load chart stacked by the same buckets, with Apdex as the one-number health score. The success/failed filter and the heatmap alternative on the scatter. The call tree's self time and percentage per step. The inspector's data-source panel (connection pool used/idle/max/pending). |
| [SigNoz](https://github.com/signoz/signoz) | The OpenTelemetry-native data model: services derived from `service.name`, endpoints from `http.route`, RED metrics per service, trace waterfall with a span detail drawer, logs correlated by trace id. |
| [OpenObserve](https://github.com/openobserve/openobserve) | Single binary, no external dependencies, everything on one port, a query bar above every list, a one-line "how to send data here" snippet in the UI. |

## One jar, three ways to run it

| Mode | Command | What happens |
|---|---|---|
| **Agent** (Glowroot-style) | `java -javaagent:spider-sense.jar -jar app.jar` | The OpenTelemetry Java agent instruments the app. An embedded collector + UI starts inside the same JVM on port 4000 and receives the agent's OTLP export over loopback. |
| **Agent, forwarding** | `java -javaagent:spider-sense.jar -Dspidersense.collector=http://localhost:4000 -jar app.jar` | Same instrumentation, no embedded UI: the agent exports to a Spider Sense running elsewhere. Several apps share one UI this way. |
| **Standalone** (SigNoz/OpenObserve-style) | `java -jar spider-sense.jar` | Collector + UI only, on port 4000. Anything that speaks OTLP/HTTP can send to it: the modes above, another language's SDK, a Collector, or a Java application under the stock OpenTelemetry Java agent (or any compatible agent) in place of the Spider Sense jar. |
| **CLI** | `java -jar spider-sense.jar findings --since=start` | No server: a command that asks the running Spider Sense over HTTP, or reads the H2 file directly when none is running, and prints text. For people in a terminal and for AI agents; see [agent.md](agent.md). |

A Java application under the stock `opentelemetry-javaagent.jar` reaches a standalone Spider Sense with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4000` and `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, at an agent version of its own choosing; the agent is one jar on the project's [GitHub releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases) and on Maven Central as `io.opentelemetry.javaagent:opentelemetry-javaagent`.
It gets the pages, the findings and the CLI over the same data, and lacks what the Spider Sense jar adds around that agent: the defaults set in `premain` (below) and [the extension](#the-extension), so no `code.stacktrace` on a slow query and no index catalog.

Another language reaches it the same way: Python through `opentelemetry-instrument` and Node.js through `--require @opentelemetry/auto-instrumentations-node/register`, both zero-code, and Go through the SDK set up in `main` with the `otlptracehttp` exporter and `otelhttp` around the handler; the manual's modes chapter carries each as an example.

The jar is Java 21+ (Spider Silk's floor). The monitored application can be any JVM the OpenTelemetry agent supports, but the embedded UI needs 21+, so agent mode requires 21+.

## How the jar is put together

```
spider-sense.jar
├── META-INF/MANIFEST.MF          Premain-Class/Agent-Class: net.benelog.spidersense.launcher.SpiderSenseAgent
│                                 Main-Class: net.benelog.spidersense.launcher.SpiderSenseMain
│                                 Can-Redefine-Classes/Can-Retransform-Classes: true (copied from the OTel agent)
├── net/benelog/spidersense/launcher/**   a handful of classes, no dependencies (Java 21)
├── io/opentelemetry/javaagent/**         the OpenTelemetry Java agent, verbatim (bootstrap classes)
├── inst/**                               the OpenTelemetry Java agent, verbatim (.classdata, loaded by the agent's own class loader)
├── spider-sense/server.jar               the collector + UI as a nested fat jar (Spider Silk, Jetty, protobuf, our code)
└── spider-sense/extension.jar            the OpenTelemetry agent extension: a stack trace for a slow database span (below)
```

The launcher is deliberately tiny and dependency-free because the OpenTelemetry agent appends the whole jar to the bootstrap class path (`Instrumentation.appendToBootstrapClassLoaderSearch`), so everything at the top level becomes bootstrap-visible.
The server and its dependencies therefore live in a nested jar that the launcher extracts to `${java.io.tmpdir}/spider-sense-<version>/server.jar` (skipped when already present with the same size) and loads through a dedicated `SenseClassLoader extends URLClassLoader` whose parent is the platform class loader.
The server never sees the application's classes, and the application never sees Jetty or protobuf from the server.
The extension is extracted the same way, to `extension.jar` beside it, and is loaded by the OpenTelemetry agent's own `ExtensionClassLoader`, not by ours.

`SpiderSenseAgent.premain` does, in order:

1. Read configuration: the properties file, then the system properties `spidersense.*` (see below).
2. Unless `spidersense.collector` is set: extract the nested jar, create the `SenseClassLoader`, and invoke `net.benelog.spidersense.server.SpiderSenseServer.main(String[])` with `--port=<port> --mode=agent ...`, on the current thread with the context class loader set to the `SenseClassLoader`. `main` returns once the port is bound (Spider Silk's `start` returns after binding). A failure here is logged to stderr and swallowed: Spider Sense must never prevent the application from starting.
3. Set defaults for the OpenTelemetry agent, only where the user has not set the property or its environment variable already:
   - `otel.exporter.otlp.protocol=http/protobuf`
   - `otel.exporter.otlp.endpoint=http://127.0.0.1:<port>` (or `spidersense.collector`); the literal address rather than `localhost`, which may resolve to `::1` while the UI binds `127.0.0.1`
   - `otel.service.name` = `spidersense.service` if given, else the OTel agent's own default (`unknown_service:java`); the UI shows the jar/main class hint from resource attributes when the name is the default.
   - `otel.bsp.schedule.delay=1000`, `otel.blrp.schedule.delay=1000`, `otel.metric.export.interval=5000`: a local tool should show a request within a second or two.
   - `otel.metrics.exporter=otlp`, `otel.logs.exporter=otlp`, `otel.traces.exporter=otlp`
   - `otel.javaagent.exclude-class-loaders=net.benelog.spidersense.launcher.SenseClassLoader` (appended to the user's own list when one is set): the agent skips every class the UI server's loader defines, so the UI's own Jetty requests never become spans. (Verified in the agent source: `GlobalIgnoredTypesConfigurer` already ignores `ExtensionClassLoader` this way, and `otel.javaagent.exclude-class-loaders` feeds `IgnoredTypesBuilder.ignoreClassLoader`.)
   - `otel.instrumentation.runtime-telemetry.enabled=true` (JVM metrics; already the default, stated for clarity)
   - `otel.javaagent.extensions=${java.io.tmpdir}/spider-sense-<version>/extension.jar` (appended to the user's own comma-separated list when one is set), our own extension described under [The extension](#the-extension); failing to extract it or to point at it is a warning on stderr and nothing else, because a missing code location is not a reason to hold up the application.
4. Call `io.opentelemetry.javaagent.OpenTelemetryAgent.premain(agentArgs, inst)`. Its jar-location check only requires a `Premain-Class` attribute in the manifest of the jar that class came from (verified against 2.31.1's `verifyJarManifestMainClassIsThis`), so our manifest satisfies it.

`SpiderSenseMain.main` (standalone) does step 2 with `--mode=standalone` and then blocks (`join`).
When its first argument does not start with `-` it is a CLI command instead: the launcher loads the nested jar the same way and invokes `net.benelog.spidersense.cli.Cli.run(String[])`, exiting with what it returns ([agent.md](agent.md)).
It also sets the system property `spidersense.jar` to its own jar's absolute path first, because `init` has to write that path into a project's `CLAUDE.md` and the CLI, running out of the nested jar in a temporary directory, could not find it otherwise.

Even with the class-loader exclusion in place, the collector drops any `SERVER` span whose `server.port` attribute equals its own port and whose service is the one it is embedded in; belt and braces, so a misconfiguration never shows the UI monitoring itself. `CLIENT` spans are kept: an application that calls Spider Sense's port is doing something real, and that call belongs in its trace.

The UI server's Jetty thread pool is marked daemon in agent mode (`JettyServer.threadPool(...)` with `QueuedThreadPool.setDaemon(true)`) and `shutdownHook(false)`, so a short-lived command-line application still exits when its `main` returns.

### Configuration

Every option is a `spidersense.*` key, given as a system property, or as a line of a properties file; the standalone jar also takes them as `--key=value` arguments.
Highest first: the `--key=value` argument, the `-Dspidersense.key` system property, the `SPIDERSENSE_KEY` environment variable of the same name, the properties file, the default.

The properties file is the one `spidersense.config` (or `SPIDERSENSE_CONFIG`) names, else `spider-sense.properties` in the working directory when that exists; a named file that does not exist is a warning on stderr.
The launcher reads it first, in `premain`, in the standalone `main` and before it runs a CLI command, and applies every `spidersense.*` key as the system property of the same name unless that property or its environment variable is already set.
From then on the launcher, the server, the extension and the CLI read the system properties exactly as they do for `-D`, so the file adds no second reader anywhere; a value is trimmed, and an empty one is kept, because an empty `spidersense.ignore.endpoints` means "ignore nothing".
Keys without the prefix are left alone, so the same file can be handed to the OpenTelemetry agent as `otel.javaagent.configuration-file`; a `spidersense.*` key that is not in the table is applied with a warning, so a typo is visible.
The CLI's default `--url` is what the properties imply, `spidersense.collector` when set, else `http://<host>:<port>` with the defaults filled in ([agent.md](agent.md)), so a command run from the project's directory asks the Spider Sense that directory's file points at; `SPIDERSENSE_URL` still wins.
Under a build tool the file is read as well, because the launcher runs in the forked JVM and its working directory is the project's, and the `-D` properties the build tool adds win over it; the Gradle plugin's `configFile` names another file, as `-Dspidersense.config` ([build-tools.md](build-tools.md)).

| Property | Default | Meaning |
|---|---|---|
| `spidersense.port` | `4000` | UI + OTLP/HTTP port |
| `spidersense.host` | `127.0.0.1` | bind address; `0.0.0.0` to reach it from another machine |
| `spidersense.collector` | unset | agent mode: forward to this base URL instead of starting the embedded UI |
| `spidersense.service` | unset | agent mode: sets `otel.service.name` |
| `spidersense.db` | `~/db/spider-sense/sense` | H2 database path or `jdbc:h2:` URL (`AUTO_SERVER=TRUE` is appended to a path); see [storage.md](storage.md) |
| `spidersense.retention.hours` | `24` | rows older than this are deleted by the sweeper |
| `spidersense.retention.spans` | `1000000` | the most `span` rows kept; the sweeper deletes the oldest hour of everything until under it ([storage.md](storage.md#retention)); `0` means no cap |
| `spidersense.ingest.max-spans-per-second` | unset | above this many spans in one second the receiver drops the spans of traces it has not seen yet and counts them on `/api/status` ([storage.md](storage.md#how-it-is-written)) |
| `spidersense.slow.request.ms` | `500` | a server span slower than this is a "tingle" |
| `spidersense.slow.query.ms` | `100` | a DB span slower than this is a "tingle" |
| `spidersense.open` | `false` | agent mode: open the browser at startup (`java.awt.Desktop`), best effort |
| `spidersense.app.packages` | unset | comma-separated package prefixes that count as application code in a finding's `code` frames; unset means "everything that is not a known framework" ([agent.md](agent.md)) |
| `spidersense.ignore.endpoints` | `/actuator/**,/health,/healthz,/livez,/readyz` | comma-separated glob patterns; an entry span whose endpoint matches is not a request ([Ignored endpoints](#ignored-endpoints)); an empty value ignores nothing |
| `spidersense.source.dirs` | `src/main/java` and `src/main/kotlin` of the working directory and of each of its immediate subdirectories | comma-separated source roots, relative ones against the working directory; a code frame resolves to the first `<root>/<package as directories>/<file of the frame>` that exists, which gives the UI its source lines and editor links and the CLI its suspect change ([agent.md](agent.md#source-lines-and-the-suspect-change)); an empty value names no root and turns all three off |

Every `otel.*` property still works as documented by the OpenTelemetry agent; Spider Sense only fills in defaults.

### From a build tool

A build tool that forks the application's JVM (`bootRun`, `run`, `spring-boot:run`) needs the option handed to it.
The Gradle plugin `net.benelog.spidersense` (an included build, `spider-sense-gradle-plugin`) does that with a `jvmArgumentProvider` and a `spiderSense { }` block whose properties are the table above, and the Spring Boot Maven plugin's `agents` parameter does it for Maven; both are specified in [build-tools.md](build-tools.md).
The jar and the plugin are published together to Maven Central as `net.benelog.spidersense:spider-sense` and `net.benelog.spidersense:spider-sense-gradle-plugin`, one version for both.

## The server

Gradle module `spider-sense-server`. A Spider Silk `App` with three concerns:

1. **OTLP/HTTP receiver**: `POST /v1/traces`, `/v1/metrics`, `/v1/logs`. `Content-Type: application/x-protobuf` (the agent's format) and `application/json` (browser SDKs, `curl`), `Content-Encoding: gzip` accepted. Decoded with the `io.opentelemetry.proto:opentelemetry-proto` bindings, the same classes the OpenTelemetry Java SDK is generated from. The answer is an empty `Export*ServiceResponse` in the request's content type. There is no gRPC receiver: gRPC needs Netty or Armeria in the jar for a benefit no local setup has; senders set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`.
2. **Store**: an H2 file database under the user's home, `~/db/spider-sense/sense` with `AUTO_SERVER=TRUE`, shared by every Spider Sense process on the machine and kept after the monitored application stops, so the analysis screen is still there after a restart or a crash. Ingest goes through a write-behind queue and one writer thread; every API answer is SQL over indexed columns. The schema, the writer, the queries and the retention sweeper are specified in [storage.md](storage.md). One small in-memory piece remains:
   - `EventBus`: ingest notifications to SSE subscribers, coalesced to at most 4 messages/second. Tingles are rows like everything else; no in-memory mirror of them is kept ([api.md](api.md#tingles)).
3. **JSON API + static UI**: the contract in [api.md](api.md); the UI in `src/main/resources/public` per [ui.md](ui.md).
4. **The agent interface**: findings, marks, compare, check and a Markdown rendering of every list, over the same `Queries` as the UI, plus the CLI that fronts them and an MCP adapter (`POST /mcp`, and `mcp` over stdio) for hosts without a shell; specified in [agent.md](agent.md), together with which of the two a host should use.

Semantic conventions: the OpenTelemetry Java agent still emits the older database attributes by default (`db.system`, `db.statement`, `db.name`, `db.operation`, `db.sql.table`) and the stable HTTP ones (`http.request.method`, `http.route`, `url.path`, `http.response.status_code`, `server.port`); with `otel.semconv-stability.opt-in=database` it emits `db.system.name`, `db.query.text`, `db.namespace`, `db.operation.name`, `db.collection.name`. The decoder normalises both generations into `SpanRecord`'s accessors, and also the pre-stable HTTP names (`http.method`, `http.target`, `http.status_code`) for other SDKs.

Errors come from three places and are merged: span status `ERROR`, the `exception` span event (`exception.type`, `exception.message`, `exception.stacktrace`), and the `error.type` attribute.
An error group is `(service, root-cause type, innermost application frame)`: the root cause is the last `Caused by:` section of `exception.stacktrace` (the outer exception when there is none), and the frame is the first application frame met reading the chain from the root cause outwards, each cause's frames top first.
The same line throwing the same exception is then one error whatever wraps it: Spring's `DataAccessException` subclasses and `ServletException` carry one outer type for every cause and a message that embeds the SQL or the request, so grouping on the outer type and message spread one bug over several groups and put unrelated ones in the same.
The frame is keyed without its `(File.java:41)` and with every `$<digits>` of a synthetic name (`lambda$load$0`, `Orders$1`) made `$?`, so moving the line or adding a lambda above it keeps the group; it is judged by the default framework prefixes ([agent.md](agent.md)), never by `spidersense.app.packages`, so the id does not depend on a setting of whichever process wrote the span.
A trace without an application frame, and an error without a trace, fall back to `(service, exception type or error.type, message with digits and quoted strings replaced by `?`)`.
The normalised message stays in the group as its `message` either way ([api.md](api.md#errors)).

Endpoint identity is `HTTP method + http.route` when a route exists, else the span name; the aggregation keys on entry spans.
An entry span is a span of kind `SERVER` or `CONSUMER`, or a root span (no parent) of kind `CLIENT` or `PRODUCER` that is not a database span (it carries no `db.system`/`db.system.name`).
A client root span is a request someone made — that is how the load generator's `java.net.http` traffic shows up — while a root `INTERNAL` span and a root database span are work the application did to itself: a seeder's tens of thousands of `INSERT`s, or a scheduler's tick, are not requests, and counting them would drown the endpoint list, the request totals, Apdex and `check`.
Such spans are still stored, still have a `trace` row and still render in the trace tree; they are simply not endpoints, not requests and never `slow request` tingles.
A root `INTERNAL` span is a **job**: a scheduled method, an `@Async` call, a batch step; jobs have their own finding, `slow-job` ([agent.md](agent.md)), so a slow one is reported without ever being counted as a request.

### Ignored endpoints

A health check polled every few seconds is the most frequent request of a typical Spring Boot application and the least interesting one: it is fast, it never fails, and it dilutes the request count, the Apdex, `check` and every `slow-endpoint` judgement.
`spidersense.ignore.endpoints` is a comma-separated list of glob patterns; an entry span whose endpoint matches one of them is written with `entry` false, exactly as a root `INTERNAL` span is: stored, in its trace, in the trace list, but not an endpoint, not a request, not in the Apdex, never a `slow request` tingle, never a finding and never a `check` verdict.
The default is `/actuator/**,/health,/healthz,/livez,/readyz`; an empty value (`-Dspidersense.ignore.endpoints=`) ignores nothing.

A pattern is matched against the endpoint name as design.md defines it (`GET /actuator/health`, or the span name when there is no route).
A pattern that starts with `/` is matched against the name with its leading `METHOD ` removed, so `/actuator/**` covers every method; a pattern with a method (`GET /actuator/**`) is matched against the whole name.
When neither matches and the span carries `url.path`, the same patterns are tried against `METHOD url.path` and `url.path`, so a framework that reports no route is still covered.
`**` matches anything including `/`, `*` matches anything but `/`, `?` matches one character that is not `/`; the match is case-sensitive and covers the whole name.
The list is shown by `/api/status` as `ignore.endpoints` and by the status text rendering.

Query identity is `(service, db system, statement as the agent sanitised it)`; the agent replaces literals with `?` by default, which is exactly the grouping wanted. A statement is shown at most 2000 characters.

## The extension

Gradle module `spider-sense-extension`, packaged as `spider-sense/extension.jar` and the only piece of Spider Sense that is not the stock OpenTelemetry agent.
It exists for two things the agent cannot do: say where a slow query was issued from, and say which indexes the table it read carries.

It registers, through `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`, a span processor that implements `io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor` and does its work in `onEnding(ReadWriteSpan)`.
That callback runs on the thread that is ending the span, before the span becomes immutable: the duration is already known and an attribute can still be set, which no ordinary `SpanProcessor` callback allows.
When the span carries a `db.system` or `db.system.name` attribute and has taken at least `spidersense.slow.query.ms` (the environment variable `SPIDERSENSE_SLOW_QUERY_MS` also works; default 100, the same threshold the server calls a tingle, read once when the processor is built), it writes `Thread.currentThread().getStackTrace()` into the span attribute `code.stacktrace`.

It captures the same stack for one more case, the N+1: the individual queries of an N+1 are fast, so the threshold above would never fire on them, and an `n-plus-one` finding would name a statement and no line.
The processor counts, per trace, how many spans of it have started with the same statement (`db.query.text` or `db.statement`, else the span name).
When a statement reaches its fifth repeat — the same number that makes a query group an N+1 on the server (agent.md) — the stack is captured on that one span and on no later repeat, so the cost is one capture per repeated statement per trace, and the server's `n-plus-one` rule prefers the span of the group that carries `code.stacktrace` for the finding's `code`.

**At the start, not at the end.** This is the one thing the processor does in `onStart` rather than in `onEnding`, and it has to be: the stack at the end is the stack of whichever thread ends the span, and for an asynchronous client that is a `CompletableFuture` completion callback on a worker, every frame of which belongs to the JDK.
A finding would then carry a code location made entirely of framework frames, which the server reduces to nothing.
At the start the thread is still the one that made the call, so the stack is the call site.
The span already carries what the instrumentation sets on the request — the statement, the URL — so the key is the one the end would have computed, and the threshold cases stay in `onEnding` because a duration is the one thing the start does not know.

**Per trace, not per thread.** The counter is one `ConcurrentHashMap` from trace id to that trace's counts, and it is read on every database and outbound span that ends.
A thread-local counter would be cheaper and needs no lifecycle, and it is what this was: it is also wrong for anything asynchronous, because a client that completes its exchange off the calling thread ends every span of a run on a different worker and each of them counts one repeat.
That is most HTTP clients — Spring's `JdkClientHttpRequestFactory` over the JDK `HttpClient` calls `sendAsync` whether or not a timeout is set, and the reactive clients never touch the calling thread — so a thread-local counter gives `n-plus-one-http` no code location at all in the common case, which is the whole of what the capture is for.

The map is bounded twice, because a map keyed by trace id in a long-running process is a leak waiting to happen.
A trace is forgotten when its **local root** ends — a span with no parent, or with a parent in another process — because that is this process's work on the trace finished; a span of that trace that ends later simply starts the count again, which is what a thread-local did when a thread moved on.
And the map holds at most 1,000 traces: a trace whose local root never ends, which is a crash or a leak in the monitored application, would otherwise keep its counts for ever, so inserting past the cap drops other traces until it fits.
Which ones is not defined, and under that much load a missing code location is the least of it.
At most 256 distinct statements and calls are counted per trace; beyond that the counter stops and nothing else changes.

The third case is a slow outbound call: a `CLIENT` span that is not a database span (no `db.system`/`db.system.name`) and has taken at least `spidersense.slow.request.ms` (`SPIDERSENSE_SLOW_REQUEST_MS`; default 500, read once like the other threshold) gets the same `code.stacktrace`, so a `slow-external` finding (agent.md) names the line that made the call.
The fourth is that case's N+1, and it counts exactly as the statements do: an outbound `CLIENT` span that carries an HTTP method or a `url.full` is counted under the key `<method> <url.full with every run of digits replaced by `?`>`, in the same per-trace map, and the fifth repeat gets the stack, so an `n-plus-one-http` finding (agent.md) names the loop rather than only the host.
The key is prefixed so that a URL can never collide with a statement, and the 256 statements a trace counts are 256 keys of either kind.
The same frames are dropped, the same cap applies, and a span that already carries the attribute is left alone.
The lines are formatted like `Throwable.printStackTrace` writes them (`\tat package.Class.method(File.java:41)`, one per line, no header), so the server reduces them to application frames with exactly the code it already uses for `exception.stacktrace` ([agent.md](agent.md)).
The leading frames of `Thread.getStackTrace`, of the processor itself and of `io.opentelemetry.` (the SDK's own `end()` path) are dropped, and the trace is cut at 64 frames.
`isStartRequired()` and `isOnEndingRequired()` are true and `isEndRequired()` is false, because by `onEnd` the span is immutable and nothing can be set on it; anything thrown inside either callback is swallowed, since a missing code location is never worth a broken span.

Only one part of the extension needs a `Connection`, which no span processor has: it reads the index catalog of the tables a slow statement touches, so a `slow-query` or `n-plus-one` finding can say which of the columns the statement filters on no index serves ([agent.md](agent.md#the-schema-block)).
For that it carries one `InstrumentationModule` of its own, `spider-sense-schema`, registered through `META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule`: an advice on the `execute`, `executeQuery`, `executeUpdate` and `executeLargeUpdate` methods of every `java.sql.Statement` implementation, which measures the call and, when it took at least `spidersense.slow.query.ms` (the same threshold, read the same way) and threw nothing, hands the statement to `IndexCatalog`.
The fifth repeat of a statement within a trace, which the span processor captures a stack for, is deliberately not a trigger here: it would cost a map lookup on every statement the application runs, for the one N+1 whose predicate column has no index, and the typical N+1 filters on a primary key; an `n-plus-one` finding therefore carries the block only when its statement has also been slow on that table.
`IndexCatalog` is a helper class the agent injects beside the driver (`getAdditionalHelperClassNames`), because advice runs in the application's class loader and the extension's own classes are not visible there.
It takes the statement's SQL from the `execute(String …)` argument or, for a `PreparedStatement`, from the `VirtualField<PreparedStatement, String>` the agent's JDBC instrumentation fills at prepare time (a statement with neither is left alone), and scans it for the table names after `from`, `join`, `update`, `into` and `delete from`, a comma-separated list after `from`, a `schema.table` qualifier kept, a quoted name unquoted.
Once per table per process (keyed by the connection's JDBC URL and the table name; 200 tables at most, then it stops), on the thread that ran the statement and on the connection it ran on, it asks the standard `DatabaseMetaData` whether the table exists (`getTables`, so a name the scanner misread is silently nothing) and for its indexes (`getIndexInfo(catalog, schema, table, false, true)`, the statistic rows dropped, the columns in ordinal order), and emits one log record through the agent's own logger provider (`GlobalOpenTelemetry.get().getLogsBridge()`, scope `spider-sense`) with the body `index catalog of <table>` and the attributes `spidersense.schema.table` (the name as the database reports it), `spidersense.schema.schema` (`TABLE_SCHEM`, absent when the database reports none), `spidersense.schema.product` (`DatabaseMetaData.getDatabaseProductName()`) and `spidersense.schema.indexes`, a JSON array of `{ "name", "unique", "columns" }`, empty for a table without an index.
The server stores that record as a catalog row and never as a log line ([storage.md](storage.md), `db_table`).
An unquoted table name is folded the way the database stores identifiers (`storesUpperCaseIdentifiers`, `storesLowerCaseIdentifiers`) before it is looked up; a quoted one is kept as written.
H2 folds to upper case, PostgreSQL to lower case, and MySQL on Linux (`lower_case_table_names=0`) to neither, so there the name is looked up as the statement wrote it, which is the only spelling the table answers to; `DatabaseCatalogIT` runs the lookup against PostgreSQL and MySQL in containers.
The lookup stays on the request thread and on the borrowed connection by design: a second connection borrowed from the pool for it would be the pool exhaustion the finding is meant to diagnose, and the one the statement used cannot be handed to another thread, because the application returns it to the pool the moment the statement is done.
What that costs is the driver's catalog query, once per table, on a request that has already spent `slow.query.ms` in the database.
On PostgreSQL that catalog query is a statement of its own (`pg_catalog` queries through an ordinary `Statement`), and when the application itself calls `getIndexInfo` it is a database span of the application's trace; MySQL's Connector/J answers the same call without an instrumented statement, so it is no span there even then.
Under the packaged agent the lookup runs inside the slow statement's own `execute` call, where the JDBC instrumentation's guard against nested statements already keeps the catalog query from becoming a span.
The extension does not rely on that ordering: it marks the thread with the baggage entry `spidersense.schema.lookup` for the length of the lookup, and a sampler it wraps around the configured one (`addSamplerCustomizer`) drops every span started under that entry and delegates everything else, so the trace shows the application's statements and nothing of ours.
`DatabaseCatalogIT` checks the result on both databases: the request's trace holds the one slow statement and no span anywhere reads the catalog.
A thread already inside a lookup does nothing (a driver's catalog queries pass through the same advice), and every exception is swallowed: a missing catalog is never worth a slow or a broken statement.
The module is compiled `compileOnly` against `io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api` and `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api` at the packaged agent's version (`otelAgentVersion`) beside the SDK artifacts below, and still ships nothing: the agent's ByteBuddy and its shaded API are what the advice and the helper run on.
Standalone mode has none of this: it has no connection to the application's database, and the spans carry no credentials, so the catalog exists only for a service that ran under the agent.

The module is compiled against `io.opentelemetry:opentelemetry-sdk-trace` and `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi` at the SDK version the packaged agent bundles (`otelSdkVersion` in the root `build.gradle`), `compileOnly` and nothing else: the agent's `ExtensionClassLoader` rewrites the unshaded `io.opentelemetry` references to the agent's own shaded classes as it loads them, so the extension must not ship a copy of the SDK.

## The examples

Five programs under `examples/`, all sending to whichever Spider Sense they are pointed at, all with deliberately bad behaviour so there is something to see; three are web applications on three different stacks, one is a worker with no web server, and one is the traffic:

- `spring-orders` (8082): Spring Boot 4.1 with Spring Data JPA and H2, an orders/customers domain; a slow report endpoint (JPQL over a large table), a lazy-loading N+1 page, an endpoint that calls `silk-bookstore` over HTTP so a trace spans two services, a checkout that fails with a business exception, and a "flaky" endpoint that fails 20% of the time; Spring Boot Actuator is on, and its Micrometer meters (`http.server.requests`, HikariCP, Spring Data, Tomcat, Logback, and Micrometer's own `jvm.*`) reach Spider Sense through the agent's Micrometer bridge, which `-Dotel.instrumentation.micrometer.enabled=true` switches on, so the metrics explorer has about ninety metrics for the one service.
- `servlet-warehouse` (8083): Jakarta Servlets on embedded Tomcat 11, plain JDBC over Tomcat's own pool, H2, ~400k rows; the Servlet stack with nothing between the servlet and the agent, so endpoints are servlet mappings (`GET /items/*` for every item), a `Filter` runs inside the server span, and an unhandled exception reaches the span through Tomcat's error page; a full-scan search, an N+1 item page, a slow report, a flaky endpoint, and an async servlet (`AsyncContext`) whose span must last until `complete()` on another thread, or until the timeout turns it into a 503.
- `batch-worker` (no port): plain Java with HikariCP, Logback and H2, scheduled jobs and no HTTP server at all, for the cases the web applications cannot raise: a service with zero requests, and the findings `slow-job` (a report rebuild that streams ~300k rows into memory, and the archive job below; a reconciliation over the same unindexed rows plus one `UPDATE` per account is a `slow-query` inside a job), `log-error` (a gateway failure caught and logged at `ERROR`, so the span is fine and only the log knows), `pool-exhausted` (an archive job that holds a connection through a slow upload, eight tasks over a pool of three, whose last tasks fail on the connection timeout and become an `error` with no endpoint), and `thread-growth` (a thread per reminder that waits for an acknowledgement that never arrives, capped so the process lives). The jobs are `@WithSpan` methods called from a `ScheduledExecutorService`, so each run is a root `INTERNAL` span, which is what [a job](#the-server) is.
- `silk-bookstore` (8081): a Spider Silk app (jte pages + JSON API), spring-jdbc, H2 file database, ~200k rows seeded so an unindexed `LIKE '%…%'` is a real slow query; an `SLEEP` alias so one query is slow by decree; a page that runs N+1 queries; an endpoint that throws; an endpoint that sleeps.
- `load-gen`: a plain Java program that hits the three web apps at a randomised rate so the dashboards fill up without manual clicking. It is instrumented too (`java.net.http`), so some traces start at the client. The worker needs no traffic.

`scripts/demo-shared.sh` builds the jar and starts one standalone Spider Sense (4000) that the four apps and the load generator forward to, which is where a trace crossing two services and one `findings` over every service show up in one place; `scripts/demo.sh` is the other layout, each app in agent mode hosting its own Spider Sense UI (bookstore 4000, orders 4001, warehouse 4002, worker 4003) with no extra process. Both take `--no-build` and `RPS`, log every process under `build/demo-logs/`, and print the CLI line to run next. The README and the manual's examples chapter carry a prompt that hands the demo to an AI agent: read the skill, start the shared script, mark, wait, `findings`, and name the line under `examples/` behind each finding.

The demo is also published as a static page, <https://spider-sense.benelog.net/demo>: the real UI over a recording of the shared demo, with no server behind it.
The recording is a DoltHub database, <https://www.dolthub.com/repositories/benelog/spider-sense-demo>, with the tables of [storage.md](storage.md) as they are (`service`, `span`, `trace`, `log`, `metric`, `metric_series`, `metric_point`, `tingle`, `mark`, `ack`, `db_table`, `meta`) and one table more, `answer`: every answer the UI asks for, keyed by path and query without `from`/`to`, plus `/manifest` (the window, the services, the keys).
The page reads that database through DoltHub's SQL API as it is opened ([ui.md](ui.md)): the aggregations come from `answer`, and the lists that are the rows of one table (the traces with the API's filters, one trace with its spans and logs, the logs, the marks, the acknowledgements) are queried from the tables, so every trace of the recording opens and a filter is applied rather than substituted.
The answers exist because the aggregations are the server's `Queries` and `Findings`, which the browser cannot run over Dolt (its SQL has no `PERCENTILE_DISC`, and a second implementation of them would drift); they are computed once, when the recording is made, by the same jar that serves the UI.
`scripts/demo-site.sh record` runs `demo-shared.sh` for `MINUTES` (5), marks `before` and `after` a third and two thirds of the way through so the compare page has two windows, and then `scripts/demo-site.mjs export` copies every row since the launch out of the H2 file into one CSV per table (the spans by the trace they belong to, so a trace that straddles the end of the window is whole, and the index catalog `db_table` whole, since a table's indexes are read once per process and usually before the window) (H2's `CSVWRITE`, driven through `org.h2.tools.Shell` from the nested server jar, so the copy needs nothing but the jar), with the home directory anonymised as agent.md's examples are and the window the page shows written into `meta` as `recording.from` and `recording.to`.
It then starts a fresh standalone Spider Sense on `build/demo-data/sense` with retention switched off, `load` inserts the CSVs into it (`CSVREAD`, after checking that the recording's `schema_version` is the server's), `capture` asks it every question the page will not answer from the tables (the lists unfiltered and per service, every endpoint, query, error and metric series they mention, every pair of marks for `compare`, and the text rendering Copy as Markdown asks for of every finding, query and error in the unfiltered lists) into `answer.csv`, and `push` adds to DoltHub's tables any column the schema gained since they were created, uploads each CSV through DoltHub's import API, which lands it on a branch of its own, and merges that branch into `main`.
`scripts/demo-site.sh recapture` is the second half alone, from the rows already on DoltHub (`pull`, then load, capture and push of `answer`): what to run after a change to the server's queries, so the demo answers as the new server would without a new recording.
`scripts/demo-site.mjs assemble`, which `npm run docs` runs, copies the UI into `build/site/demo` and marks `index.html` with `data-dolthub="benelog/spider-sense-demo@main"`, which makes `app.js` load `assets/js/dev/replay.js` instead of talking to a server; the Docs workflow needs neither Java nor the recording for it, and nothing of the recording is committed to this repository.
Recording needs `DOLTHUB_TOKEN`, a writer's API token of the database, kept in `.envrc`; the page needs nothing, since the database is public.

## What was considered and rejected

- **An OpenTelemetry agent extension instead of our own premain.** The extension mechanism (`extensions/` inside the agent jar, `AgentListener`) would also work, and `ExtensionClassLoader` is already exclusion-listed. Rejected because the UI would then depend on the agent's SPI and lifecycle, and the standalone mode would still need a launcher of its own. A premain that wraps the agent's premain keeps the server a plain program that the agent happens to be pointed at over a standard protocol.
- **…but one small extension beside the premain is worth it.** The stock agent records where an exception was thrown and nothing at all about where a query was issued from, so `slow-query` and `n-plus-one` findings named a statement and left the reader to grep for it. A stack capture on the thread that is ending the span, taken only for database spans already over `slow.query.ms` and for the fifth repeat of a statement within a trace, costs nothing measurable — the slow spans are by definition slow, a few microseconds against a hundred milliseconds, and the repeat capture happens once per repeated statement per trace — and it turns those findings into a line to open. That is [the extension](#the-extension); it sets one attribute, and the server and the standalone mode stay unaware of it.
  The index catalog is the second thing it does, and the first that needs bytecode advice rather than a span processor, because only the application's own `Connection` can answer which indexes a table has; it is still confined to the extension, arrives as ordinary OTLP, and the server stays a consumer of what the agent exports.
- **In-process export (a custom `SpanExporter` handing spans straight to the store).** Faster, but it ties the store to the agent's shaded SDK classes and makes the standalone and embedded paths diverge. Loopback OTLP costs nothing measurable and exercises the same code path the standalone mode uses.
- **In-memory only storage.** The first design kept everything in bounded ring buffers and aggregated on demand: simplest, and free of any database inside the application's JVM. Rejected because the analysis screen has to survive the monitored application going down, and because two embedded instances on one machine should show one picture. H2 with `AUTO_SERVER` gives both, and the class-loader exclusion keeps the OpenTelemetry agent away from its JDBC.
- **A frontend build (React, Vue, TypeScript).** SigNoz and OpenObserve are built that way; Spider Sense is a single jar whose build must stay `./gradlew build` with no Node. The UI is plain ES modules, one CSS file, and uPlot for charts, served by Spider Silk's static files. A template engine was not used either: the UI is one page whose data all comes from the JSON API, and Spider Silk's JSON and SSE support is the part of the framework this application exercises.
- **gRPC receiver.** See above.
- **Profiling (Glowroot-style stack sampling).** Not part of OpenTelemetry's stable signals in Java; deferred until the profiling signal lands in the agent.
