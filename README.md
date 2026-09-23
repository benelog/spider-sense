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

Spider Sense is an observability tool for the local development loop.
One jar, one JVM option, and a browser tab that shows every request, every SQL statement, every error, every log line and the JVM's vital signs of the application you are working on, a second after they happen.
It takes the three OpenTelemetry signals, traces, metrics and logs, and answers with them what is slow, what failed and why, and what the application was doing at that moment.
The same jar is a command line whose answers are written for an AI coding agent: what is wrong, ranked, with the trace that proves it.

It is a sibling of [Spider Silk](https://github.com/benelog/spider-silk), the web framework its UI is built with, and it follows the same idea: thin by design.
The instrumentation is the stock [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation), the collector speaks OTLP/HTTP, nothing has to be installed, and the data goes into an H2 file under `~/db/spider-sense/` that outlives the application.

It is for three things:

- **An AI coding agent fixes what it can see.** The same jar is a command line whose answers carry numbers and trace ids, so an N+1 in a JPA repository or a query without an index is found and fixed in the agent's own loop, before `git commit` and `git push`.
- **A developer watches the application while writing it.** Every request, query, error and log line of the application on the local machine, a second after it happens, so a problem is noticed while the code is still open.
- **The tests become a diagnosis.** A test run under Spider Sense leaves the same traces, queries, errors and logs as a request from a browser, so `findings` says what the code the tests exercised did wrong, and `check` fails the build on it. The fastest feedback there is, for the agent and the person alike: one `./gradlew test`, and the N+1 is named before anyone opens a browser.
- **The OpenTelemetry setup is checked before it ships.** A custom meter, a span attribute or a Spring Boot Actuator metric is confirmed to be collected and aggregated as intended before the application reaches a development server.

It is Java-first but not Java-only: in standalone mode the jar is a collector and dashboard for any platform with an OpenTelemetry SDK, and a Java application can send from the stock OpenTelemetry Java agent, or any compatible agent, instead of the Spider Sense jar.

**The manual is at <https://spider-sense.benelog.net>.**
This page is the short version: how to start it, what it looks like, and how to run the demo.

## Quick start

Requires Java 21 or later.

```bash
./gradlew :spider-sense-agent:senseJar
java -javaagent:spider-sense-agent/build/libs/spider-sense-0.1.0.jar -jar your-app.jar
```

Open <http://localhost:4000>.

| Mode | Command | When |
|---|---|---|
| Agent | `java -javaagent:spider-sense.jar -jar app.jar` | One application; the UI runs inside its JVM on port 4000. |
| Agent, forwarding | `java -javaagent:spider-sense.jar -Dspidersense.collector=http://localhost:4000 -jar app.jar` | Several applications sharing one UI. |
| Standalone | `java -jar spider-sense.jar` | Collector and UI only. Any OpenTelemetry SDK (Node.js, Python, Go, .NET) or agent sends to it with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4000` and `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`; with `-javaagent:opentelemetry-javaagent.jar` that is a Java application under the stock agent. |

Under Gradle the plugin puts the agent on `bootRun` and `run`, and with Maven the Spring Boot plugin's `agents` parameter does it:

```groovy
plugins {
    id 'net.benelog.spidersense' version '0.1.0'
}
```

[The Three Modes](https://spider-sense.benelog.net/modes.html), [Configuration](https://spider-sense.benelog.net/configuration.html), [The Gradle Plugin](https://spider-sense.benelog.net/gradle-plugin.html) and [Maven](https://spider-sense.benelog.net/maven.html) in the manual have the rest.

## What you see

<p align="center"><img src="manual/modules/ROOT/images/overview.jpg" alt="Overview: request rate, error rate, percentiles, the throughput chart, the services, and the tingle feed" width="900"></p>

<p align="center">
  <img src="manual/modules/ROOT/images/scatter.jpg" alt="Scatter: every request as a dot on time by response time" width="440">
  <img src="manual/modules/ROOT/images/trace-waterfall.jpg" alt="A trace across spring-orders and silk-bookstore as a waterfall" width="440">
</p>
<p align="center">
  <img src="manual/modules/ROOT/images/trace-profile.jpg" alt="The same trace as a step-by-step profile with gap and elapsed times" width="440">
  <img src="manual/modules/ROOT/images/queries.jpg" alt="Queries grouped by statement with calls, average, p95, max and total time" width="440">
</p>

Overview, a server map, services and endpoints, a scatter of every request, traces as a waterfall and as a profile, queries, errors, logs with trace ids, the JVM, and an explorer for every other metric, Spring Boot Actuator's included.
[The Pages](https://spider-sense.benelog.net/pages.html) describes each one.

## The demo

**See it first: <https://spider-sense.benelog.net/demo>** is the UI over a five-minute recording of the demo below, with nothing to install and no server behind it.
It is a recording: the page reads a DoltHub database, <https://www.dolthub.com/repositories/benelog/spider-sense-demo>, that holds the same tables a running Spider Sense keeps plus the answers captured once, so nothing updates, every trace opens, and the demo's rows can be queried there as SQL too.
To look deeper, run the demo locally as below; it is the same UI over a live server, with the CLI beside it.

Four deliberately misbehaving applications and a load generator come with the repository, so there is something to look at before there is anything of your own.

| Application | Stack | Port | What it does wrong |
|---|---|---|---|
| [spring-orders](examples/spring-orders) | Spring Boot 4, Spring Data JPA, Actuator, H2 | 8082 | a slow report, a lazy-loading N+1, a call to the bookstore over HTTP so one trace spans two services, a state machine that throws; Actuator's Micrometer metrics arrive through the agent |
| [servlet-warehouse](examples/servlet-warehouse) | Jakarta Servlet on embedded Tomcat, JDBC, H2 | 8083 | the same faults on a plain Servlet stack, plus an async servlet that times out, a filter, and Tomcat's error page |
| [batch-worker](examples/batch-worker) | plain Java, HikariCP, Logback, H2 | none | no HTTP at all: slow scheduled jobs, a connection held across a slow call so a three-connection pool runs out, errors that only the log knows about, a thread that leaks per message |
| [silk-bookstore](examples/silk-bookstore) | Spider Silk, spring-jdbc, H2 | 8081 | a full-scan search over 200,000 rows, an N+1 page, a query that sleeps, a slow URL with no SQL, a flaky endpoint |
| [load-gen](examples/load-gen) | plain Java | | drives the three web applications at a few requests per second |

### Run it

One command builds everything, starts it, prints the URLs, and stops it all on Ctrl-C:

```bash
scripts/demo-shared.sh          # one Spider Sense at :4000 that all four applications forward to
scripts/demo.sh                 # each application with its own embedded Spider Sense (:4000 to :4003)
```

Open <http://localhost:4000>, give the load generator a minute, and every page has data on it: the map shows four services and a database, the scatter has a slow cluster, the errors page has the flaky endpoints, and the JVM page has the worker's pool running dry.
`--no-build` skips the Gradle build on a second run, `RPS=10` raises the load, and every process writes its log to `build/demo-logs/`.

The same data is in the jar's command line, written for an agent:

```bash
java -jar spider-sense-agent/build/libs/spider-sense-0.1.0.jar findings --since=5m
```

After Ctrl-C the H2 file under `~/db/spider-sense/` still has everything, and `java -jar spider-sense-agent/build/libs/spider-sense-0.1.0.jar` opens the same screens on it.
`scripts/demo-site.sh record` runs the demo for five minutes and pushes its rows and the captured answers to the DoltHub database behind the page above.

### Hand it to an agent

The repository carries the agent skills at `skills/`, linked from `.claude/skills/` and `.agents/skills/`, so Claude Code and Codex started in this checkout find them without an `init` step.
Start the demo with `scripts/demo-shared.sh`, let the load generator run for a few minutes, and then ask in Claude Code:

```text
/spider-sense The example apps have been running under Spider Sense with some traffic for a few minutes.
What are the three biggest problems, and which lines under examples/ cause them?
Do not change the code yet; propose how to fix them first.
```

In Codex the skill is called `$spider-sense` instead of `/spider-sense`.
The prompt names no command: the skill tells the agent to read the findings, open a trace for each, and find the line in the code.
The agent answers with the findings as the tool printed them, the trace ids as evidence, the file and line each fault comes from, and a fix for each to decide on.
**Watch it first:** real runs of this prompt, replayed from the agents' event streams, in [Claude Code](https://spider-sense.benelog.net/agent-demo/claude-code/) ([한국어](https://spider-sense.benelog.net/agent-demo/claude-code/ko/)) and [Codex CLI](https://spider-sense.benelog.net/agent-demo/codex/) ([한국어](https://spider-sense.benelog.net/agent-demo/codex/ko/)).
A follow-up such as "apply the first fix and show me before and after" makes it fix, restart, exercise the same endpoints, and run `compare`.
In your own project, `java -jar spider-sense.jar init` installs the same skills into `.claude/skills/` and writes the jar's path into `CLAUDE.md`, so the prompt starts at "start the app under Spider Sense".

## For AI agents

```bash
java -jar spider-sense.jar mark before                     # name the moment
# exercise the endpoints, or run the tests
java -jar spider-sense.jar findings --since=before          # ranked: N+1, slow queries, slow jobs, errors, exhausted pools
java -jar spider-sense.jar trace 4bf92f3577b34da6a3ce929d0e0e4736   # one request as a tree, repeats collapsed
# fix, restart
java -jar spider-sense.jar compare --before=before --after=start     # the same endpoints and queries, side by side
java -jar spider-sense.jar check --max-queries-per-request=10       # exit code 0 or 1
```

Every command prints Markdown, takes `--json`, asks the running Spider Sense over HTTP, and reads the H2 file directly when none is running.
The same answers are an MCP server for a host without a shell, and `check` is a build gate.
Two skills teach an agent what to do with them: `skills/spider-sense/` runs the loop, and `skills/spider-sense-sql-tuning/` turns a slow query or an N+1 into the index to add, the rewrite or the fetch join, confirmed by the plan and proven by `compare`; `java -jar spider-sense.jar init` installs both into a project.
[Quick Start for Agents](https://spider-sense.benelog.net/agent-quickstart.html) in the manual chooses between the CLI and MCP and lists what to ask; [docs/agent.md](docs/agent.md) is the specification.

## Documentation

The manual at <https://spider-sense.benelog.net> is what to read; the specifications it is written from are in the repository:
[docs/design.md](docs/design.md) (the single jar, the store, what was rejected and why), [docs/storage.md](docs/storage.md) (the H2 schema), [docs/api.md](docs/api.md) (the JSON contract), [docs/ui.md](docs/ui.md) (the pages), [docs/agent.md](docs/agent.md) (findings, marks, compare, check, the CLI, MCP and the skill) and [docs/build-tools.md](docs/build-tools.md) (the Gradle plugin and Maven).

## License

Apache-2.0.
