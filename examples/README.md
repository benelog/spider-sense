# The Examples

Four deliberately misbehaving applications and a load generator come with the repository, so there is something to look at before there is anything of your own.
The recorded demo linked from the [README](../README.md#demo) is these applications under one Spider Sense; this page runs them on your own machine.

| Application | Stack | Port | What it does wrong |
|---|---|---|---|
| [spring-orders](spring-orders) | Spring Boot 4, Spring Data JPA, Actuator, H2 | 8082 | a slow report, a lazy-loading N+1, a call to the bookstore over HTTP so one trace spans two services, a state machine that throws; Actuator's Micrometer metrics arrive through the agent |
| [servlet-warehouse](servlet-warehouse) | Jakarta Servlet on embedded Tomcat, JDBC, H2 | 8083 | the same faults on a plain Servlet stack, plus an async servlet that times out, a filter, and Tomcat's error page |
| [batch-worker](batch-worker) | plain Java, HikariCP, Logback, H2 | none | no HTTP at all: slow scheduled jobs, a connection held across a slow call so a three-connection pool runs out, errors that only the log knows about, a thread that leaks per message |
| [silk-bookstore](silk-bookstore) | Spider Silk, spring-jdbc, H2 | 8081 | a full-scan search over 200,000 rows, an N+1 page, a query that sleeps, a slow URL with no SQL, a flaky endpoint |
| [load-gen](load-gen) | plain Java | | drives the three web applications at a few requests per second |

## Run it

One command from the repository root builds everything, starts it, prints the URLs, and stops it all on Ctrl-C:

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
`scripts/demo-site.sh record` runs the demo for five minutes and pushes its rows and the captured answers to the DoltHub database behind the recorded UI demo.

## Hand it to an agent

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
A follow-up such as "apply the first fix and show me before and after" makes it fix, restart, exercise the same endpoints, and run `compare`.
In your own project, `java -jar spider-sense.jar init` installs the same skills into `.claude/skills/` and writes the jar's path into `CLAUDE.md`, so the prompt starts at "start the app under Spider Sense".
`scripts/agent-demo.sh record claude en` records such a run for the agent demo, and `scripts/agent-demo.sh push` pushes the recorded sessions to DoltHub.

[The Examples](https://spider-sense.benelog.net/examples.html) in the manual has the rest.
