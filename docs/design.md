# Spider Sense: design

Spider Sense is an APM for the local development loop, in the spirit of [Glowroot](https://glowroot.org/): one jar, attached to a JVM with one option, a UI in the browser a second later.
What sets it apart from Glowroot is that everything it collects arrives as OpenTelemetry data over OTLP.
The instrumentation is the stock OpenTelemetry Java agent, not a bytecode engine of our own, and the collector accepts OTLP/HTTP from any SDK in any language.

The UI is a Spider Silk application (`net.benelog.spidersilk`), and the product is a sibling of Spider Silk: same author, same logo family, same "thin by design" attitude.

## Influences

| Product | What Spider Sense takes from it |
|---|---|
| [Glowroot](https://glowroot.org/) | The deployment model: one jar, `-javaagent`, the UI served from the monitored JVM. The transaction/slow-trace/error/JVM page structure. |
| [Scouter](https://github.com/scouter-project/scouter) | The XLog: every request is a dot on a time × response-time scatter, errors in red, and a drag over a cluster of dots lists those traces. The profile view of one transaction as a step list with elapsed and gap times. The habit of keeping the last N minutes always visible. |
| [SigNoz](https://github.com/signoz/signoz) | The OpenTelemetry-native data model: services derived from `service.name`, endpoints from `http.route`, RED metrics per service, trace waterfall with a span detail drawer, logs correlated by trace id. |
| [OpenObserve](https://github.com/openobserve/openobserve) | Single binary, no external dependencies, everything on one port, a query bar above every list, a one-line "how to send data here" snippet in the UI. |

## One jar, three ways to run it

| Mode | Command | What happens |
|---|---|---|
| **Agent** (Glowroot-style) | `java -javaagent:spider-sense.jar -jar app.jar` | The OpenTelemetry Java agent instruments the app. An embedded collector + UI starts inside the same JVM on port 4000 and receives the agent's OTLP export over loopback. |
| **Agent, forwarding** | `java -javaagent:spider-sense.jar -Dspidersense.collector=http://localhost:4000 -jar app.jar` | Same instrumentation, no embedded UI: the agent exports to a Spider Sense running elsewhere. Several apps share one UI this way. |
| **Standalone** (SigNoz/OpenObserve-style) | `java -jar spider-sense.jar` | Collector + UI only, on port 4000. Anything that speaks OTLP/HTTP can send to it: the modes above, another language's SDK, a Collector. |

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
└── spider-sense/server.jar               the collector + UI as a nested fat jar (Spider Silk, Jetty, protobuf, our code)
```

The launcher is deliberately tiny and dependency-free because the OpenTelemetry agent appends the whole jar to the bootstrap class path (`Instrumentation.appendToBootstrapClassLoaderSearch`), so everything at the top level becomes bootstrap-visible.
The server and its dependencies therefore live in a nested jar that the launcher extracts to `${java.io.tmpdir}/spider-sense-<version>/server.jar` (skipped when already present with the same size) and loads through a dedicated `SenseClassLoader extends URLClassLoader` whose parent is the platform class loader.
The server never sees the application's classes, and the application never sees Jetty or protobuf from the server.

`SpiderSenseAgent.premain` does, in order:

1. Read configuration (system properties `spidersense.*`, see below).
2. Unless `spidersense.collector` is set: extract the nested jar, create the `SenseClassLoader`, and invoke `net.benelog.spidersense.server.SpiderSenseServer.main(String[])` with `--port=<port> --mode=agent ...`, on the current thread with the context class loader set to the `SenseClassLoader`. `main` returns once the port is bound (Spider Silk's `start` returns after binding). A failure here is logged to stderr and swallowed: Spider Sense must never prevent the application from starting.
3. Set defaults for the OpenTelemetry agent, only where the user has not set the property or its environment variable already:
   - `otel.exporter.otlp.protocol=http/protobuf`
   - `otel.exporter.otlp.endpoint=http://localhost:<port>` (or `spidersense.collector`)
   - `otel.service.name` = `spidersense.service` if given, else the OTel agent's own default (`unknown_service:java`); the UI shows the jar/main class hint from resource attributes when the name is the default.
   - `otel.bsp.schedule.delay=1000`, `otel.blrp.schedule.delay=1000`, `otel.metric.export.interval=5000`: a local tool should show a request within a second or two.
   - `otel.metrics.exporter=otlp`, `otel.logs.exporter=otlp`, `otel.traces.exporter=otlp`
   - `otel.javaagent.exclude-class-loaders=net.benelog.spidersense.launcher.SenseClassLoader`: the agent skips every class the UI server's loader defines, so the UI's own Jetty requests never become spans. (Verified in the agent source: `GlobalIgnoredTypesConfigurer` already ignores `ExtensionClassLoader` this way, and `otel.javaagent.exclude-class-loaders` feeds `IgnoredTypesBuilder.ignoreClassLoader`.)
   - `otel.instrumentation.runtime-telemetry.enabled=true` (JVM metrics; already the default, stated for clarity)
4. Call `io.opentelemetry.javaagent.OpenTelemetryAgent.premain(agentArgs, inst)`. Its jar-location check only requires a `Premain-Class` attribute in the manifest of the jar that class came from (verified against 2.31.1's `verifyJarManifestMainClassIsThis`), so our manifest satisfies it.

`SpiderSenseMain.main` (standalone) does step 2 with `--mode=standalone` and then blocks (`join`).

Even with the class-loader exclusion in place, the collector drops any span whose `server.port` attribute equals its own port and whose service is the one it is embedded in; belt and braces, so a misconfiguration never shows the UI monitoring itself.

The UI server's Jetty thread pool is marked daemon in agent mode (`JettyServer.threadPool(...)` with `QueuedThreadPool.setDaemon(true)`) and `shutdownHook(false)`, so a short-lived command-line application still exits when its `main` returns.

### Configuration

All via system properties (agent mode has no other channel before `main`); the standalone jar also takes them as `--key=value` arguments.

| Property | Default | Meaning |
|---|---|---|
| `spidersense.port` | `4000` | UI + OTLP/HTTP port |
| `spidersense.host` | `127.0.0.1` | bind address; `0.0.0.0` to reach it from another machine |
| `spidersense.collector` | unset | agent mode: forward to this base URL instead of starting the embedded UI |
| `spidersense.service` | unset | agent mode: sets `otel.service.name` |
| `spidersense.retention.spans` | `200000` | ring capacity |
| `spidersense.retention.logs` | `50000` | ring capacity |
| `spidersense.retention.metricPoints` | `2000` | points kept per series |
| `spidersense.slow.request.ms` | `500` | a server span slower than this is a "tingle" |
| `spidersense.slow.query.ms` | `100` | a DB span slower than this is a "tingle" |
| `spidersense.open` | `false` | agent mode: open the browser at startup (`java.awt.Desktop`), best effort |

Every `otel.*` property still works as documented by the OpenTelemetry agent; Spider Sense only fills in defaults.

## The server

Gradle module `spider-sense-server`. A Spider Silk `App` with three concerns:

1. **OTLP/HTTP receiver**: `POST /v1/traces`, `/v1/metrics`, `/v1/logs`. `Content-Type: application/x-protobuf` (the agent's format) and `application/json` (browser SDKs, `curl`), `Content-Encoding: gzip` accepted. Decoded with the `io.opentelemetry.proto:opentelemetry-proto` bindings, the same classes the OpenTelemetry Java SDK is generated from. The answer is an empty `Export*ServiceResponse` in the request's content type. There is no gRPC receiver: gRPC needs Netty or Armeria in the jar for a benefit no local setup has; senders set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`.
2. **Store**: in memory, bounded, no persistence. A restart of the monitored process is a fresh slate, which is what local development wants, and it keeps the jar free of a database that the OpenTelemetry agent would otherwise instrument. Structures (`net.benelog.spidersense.store`):
   - `SpanStore`: ring buffer of `SpanRecord` (capacity `retention.spans`), plus `traceId -> TraceEntry` index maintained on insert and eviction. Reads take a snapshot of the window and aggregate on demand (percentiles by sorting the window's entry-span durations; buckets by time). At 200k spans a full scan is milliseconds, and simplicity wins over pre-aggregation.
   - `MetricStore`: `(service, metric name, attribute set) -> Series` with a ring of points; gauges and sums keep a double, histograms keep count/sum/min/max/bucket counts. Metric metadata (unit, description, type) is kept per name.
   - `LogStore`: ring of `LogRecord` with trace and span ids for correlation.
   - `ServiceRegistry`: the resource attributes of every service seen, first and last seen timestamps, telemetry SDK name/language, whether it is the embedded (agent-mode) service.
   - `Tingles`: a bounded recent list (500) of noteworthy events: slow request, slow query, error, derived at ingest against the thresholds. The Overview feeds on it and the SSE stream pushes it live.
   - `EventBus`: ingest notifications to SSE subscribers, coalesced to at most 4 messages/second.
3. **JSON API + static UI**: the contract in [api.md](api.md); the UI in `src/main/resources/public` per [ui.md](ui.md).

Semantic conventions: the OpenTelemetry Java agent still emits the older database attributes by default (`db.system`, `db.statement`, `db.name`, `db.operation`, `db.sql.table`) and the stable HTTP ones (`http.request.method`, `http.route`, `url.path`, `http.response.status_code`, `server.port`); with `otel.semconv-stability.opt-in=database` it emits `db.system.name`, `db.query.text`, `db.namespace`, `db.operation.name`, `db.collection.name`. The decoder normalises both generations into `SpanRecord`'s accessors, and also the pre-stable HTTP names (`http.method`, `http.target`, `http.status_code`) for other SDKs.

Errors come from three places and are merged: span status `ERROR`, the `exception` span event (`exception.type`, `exception.message`, `exception.stacktrace`), and the `error.type` attribute. An error group is `(service, exception type or error.type, message with digits and quoted strings replaced by `?`)`.

Endpoint identity is `HTTP method + http.route` when a route exists, else the span name; the aggregation keys on entry spans (kind `SERVER` or `CONSUMER`, or a root span of any kind).

Query identity is `(service, db system, statement as the agent sanitised it)`; the agent replaces literals with `?` by default, which is exactly the grouping wanted. A statement is shown at most 2000 characters.

## The examples

Three applications under `examples/`, all sending to whichever Spider Sense they are pointed at, all with deliberately bad behaviour so there is something to see:

- `silk-bookstore`: a Spider Silk app (jte pages + JSON API), spring-jdbc, H2 file database, ~200k rows seeded so an unindexed `LIKE '%…%'` is a real slow query; an `SLEEP` alias so one query is slow by decree; a page that runs N+1 queries; an endpoint that throws; an endpoint that sleeps.
- `spring-orders`: Spring Boot 4.1 with Spring Data JPA and H2, an orders/customers domain; a slow report endpoint (JPQL over a large table), a lazy-loading N+1 page, an endpoint that calls `silk-bookstore` over HTTP so a trace spans two services, a checkout that fails with a business exception, and a "flaky" endpoint that fails 20% of the time.
- `load-gen`: a plain Java program that hits both apps at a randomised rate so the dashboards fill up without manual clicking. It is instrumented too (`java.net.http`), so some traces start at the client.

`scripts/demo.sh` builds the jar and starts both apps in agent mode, each hosting its own Spider Sense UI (bookstore on 4000, orders on 4001) with no extra process, then the load generator; `scripts/demo-shared.sh` is the other layout, one standalone Spider Sense that both apps forward to, which is where a trace crossing both services shows up in one place.

## What was considered and rejected

- **An OpenTelemetry agent extension instead of our own premain.** The extension mechanism (`extensions/` inside the agent jar, `AgentListener`) would also work, and `ExtensionClassLoader` is already exclusion-listed. Rejected because the UI would then depend on the agent's SPI and lifecycle, and the standalone mode would still need a launcher of its own. A premain that wraps the agent's premain keeps the server a plain program that the agent happens to be pointed at over a standard protocol.
- **In-process export (a custom `SpanExporter` handing spans straight to the store).** Faster, but it ties the store to the agent's shaded SDK classes and makes the standalone and embedded paths diverge. Loopback OTLP costs nothing measurable and exercises the same code path the standalone mode uses.
- **Persistence (H2, SQLite, files).** Deferred. The OpenTelemetry agent would instrument a JDBC store inside the app's JVM (the exclusion covers our class loader, but the driver's connection to a file is still work), and a local-dev tool is served well by memory. An export of the current window as JSON is on the API for the case where a trace must be kept.
- **A frontend build (React, Vue, TypeScript).** SigNoz and OpenObserve are built that way; Spider Sense is a single jar whose build must stay `./gradlew build` with no Node. The UI is plain ES modules, one CSS file, and uPlot for charts, served by Spider Silk's static files. A template engine was not used either: the UI is one page whose data all comes from the JSON API, and Spider Silk's JSON and SSE support is the part of the framework this application exercises.
- **gRPC receiver.** See above.
- **Profiling (Glowroot's stack sampling).** Not part of OpenTelemetry's stable signals in Java; deferred until the profiling signal lands in the agent.
