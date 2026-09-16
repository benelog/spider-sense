<p align="center">
  <img src="notes/logo.svg" alt="Spider Sense" width="160">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-e2603f?style=flat-square&labelColor=2b303b" alt="Java 21">
  <img src="https://img.shields.io/badge/OpenTelemetry-OTLP%2FHTTP-8a93a6?style=flat-square&labelColor=2b303b" alt="OpenTelemetry">
  <img src="https://img.shields.io/badge/UI-Spider%20Silk-8a93a6?style=flat-square&labelColor=2b303b" alt="Spider Silk">
  <img src="https://img.shields.io/badge/one%20jar-yes-e2603f?style=flat-square&labelColor=2b303b" alt="One jar">
</p>

# Spider Sense

Feel what moves on the web.

Spider Sense is an APM for the local development loop.
One jar, one JVM option, and a browser tab that shows every request, every SQL statement, every error and the JVM's vital signs of the application you are working on, a second after they happen.

It is a sibling of [Spider Silk](https://github.com/benelog/spider-silk), the web framework its UI is built with, and it follows the same idea: thin by design.

- **Glowroot's deployment, OpenTelemetry's data.**
  `-javaagent:spider-sense.jar` is all it takes, like Glowroot.
  Unlike Glowroot, the instrumentation is the stock [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation) and the collector speaks OTLP/HTTP, so anything that emits OpenTelemetry can send to it.
- **Nothing to install, nothing to keep.**
  No database, no Docker, no account.
  Data lives in memory for the life of the process; a restart is a clean slate.
- **Built for the questions you ask while coding.**
  Which endpoint is slow, which query made it slow, what did that request do step by step, what threw, what did the log say at that moment.

## Quick start

Requires Java 21 or later.

```bash
./gradlew :spider-sense-agent:senseJar
java -javaagent:spider-sense-agent/build/libs/spider-sense-0.1.0.jar -jar your-app.jar
```

Open <http://localhost:4000>.

## Three ways to run it

| Mode | Command | When |
|---|---|---|
| Agent | `java -javaagent:spider-sense.jar -jar app.jar` | One application. The UI runs inside its JVM on port 4000. |
| Agent, forwarding | `java -javaagent:spider-sense.jar -Dspidersense.collector=http://localhost:4000 -jar app.jar` | Several applications sharing one UI, or an application that must not host a web server. |
| Standalone | `java -jar spider-sense.jar` | Collector and UI only. Point any OpenTelemetry SDK at it with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4000` and `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`. |

Every `otel.*` system property and `OTEL_*` environment variable of the OpenTelemetry agent still applies; Spider Sense only fills in defaults.
`-Dspidersense.port=4001` moves the UI; the full table is in [docs/design.md](docs/design.md#configuration).

## What you see

| Page | What it answers |
|---|---|
| Overview | Is anything wrong right now: request rate, error rate, p95, and the feed of *tingles* (slow requests, slow queries, errors) as they happen. |
| Services, Endpoints | Which route costs the most: calls, rps, p50/p95/p99, errors, status codes, and the queries and errors behind it. |
| XLog | Scouter's view: every request as a dot on time × response time; drag over a cluster to see those traces. |
| Traces | The list, the waterfall, a span drawer with every attribute and stack trace, and a Scouter-style profile view: what the request did, step by step, with gap times. |
| Queries | SQL statements grouped as the agent sanitised them: calls, avg, p95, max, total time, who calls them. |
| Errors | Exceptions grouped by type and message, with a sample stack trace and the traces they occurred in. |
| Logs | The application's log records with trace ids, so a trace and its log lines are one click apart. |
| JVM, Metrics | Heap, GC, threads, CPU, classes from the agent's JVM metrics, and an explorer for every other metric. |

## The examples

Two deliberately misbehaving applications and a load generator, so there is something to look at:

- [examples/silk-bookstore](examples/silk-bookstore): Spider Silk, spring-jdbc, H2 with 200,000 unindexed rows: a full-scan search, an N+1 page, a query that sleeps, a slow URL that does no SQL, an endpoint that fails at random.
- [examples/spring-orders](examples/spring-orders): Spring Boot 4, Spring Data JPA, H2: a slow revenue report, a lazy-loading N+1, an endpoint that calls the bookstore over HTTP so one trace spans two services, a state machine that throws, a flaky endpoint.
- [examples/load-gen](examples/load-gen): drives both at a few requests per second.

```bash
scripts/demo.sh             # each app with its own embedded Spider Sense (:4000 and :4001), no extra process
scripts/demo-shared.sh      # one standalone Spider Sense both apps forward to, so a cross-service trace shows in one UI
```

## How it is built

[docs/design.md](docs/design.md) explains the single jar (the OpenTelemetry agent verbatim, a dependency-free launcher, and the collector + UI as a nested jar in an isolated class loader), the in-memory store, and what was rejected and why.
[docs/api.md](docs/api.md) is the JSON contract between the server and the UI.
[docs/ui.md](docs/ui.md) is the UI specification.

## License

Apache-2.0.
