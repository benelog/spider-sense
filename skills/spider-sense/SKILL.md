---
name: spider-sense
description: >-
  Measure and explain a locally running application with Spider Sense, an APM for the development loop:
  one jar, `-javaagent`, OpenTelemetry-native, with a CLI that answers in Markdown and a verdict in its exit code.
  Use this skill whenever the user asks why a local Java or JVM application (or anything sending OpenTelemetry) is slow,
  where an N+1 comes from, which query costs the most, what is throwing, or why a connection pool runs out;
  whenever they mention Spider Sense, spider-sense.jar, `spidersense.*` properties or `~/db/spider-sense`;
  and whenever they want to know what a code change did to request latency, query count or errors
  (mark, exercise, compare, check). It also covers starting an application under the agent
  (plain `java -jar`, the Gradle plugin, Gradle `run`, `installDist`, Spring Boot `bootRun`, a test task, Maven) and reading a trace as a tree.
license: Apache-2.0
metadata:
  version: "0.1.0"
  homepage: https://github.com/benelog/spider-sense
---

# Spider Sense

Spider Sense is an APM for the local development loop: one jar attached with `-javaagent`, the stock OpenTelemetry Java agent for instrumentation, and a collector, a UI and a CLI inside the same JVM.
Everything it collects lands in an H2 file under `~/db/spider-sense/`, opened with `AUTO_SERVER=TRUE`, so the data is shared between processes and outlives the application that produced it.
For an agent the CLI is the interface, not the dashboard: it prints deterministic Markdown, so two answers over the same window render to the same bytes and can be diffed.

What it gives you that a dashboard does not:

- **Findings**: a ranked, bounded list of things worth fixing, each with the numbers that justify it, the trace ids that prove it, and the application frames when they are known.
- **Marks**: a named moment (`before`, `after-fix`), plus an automatic `start` mark whenever a service restarts, so `--since=start` means "since I rebuilt it".
- **Compare**: the window before a mark beside the window after it, endpoint by endpoint, query by query, with a verdict per row.
- **Check**: thresholds turned into pass or fail in the exit code, so Spider Sense can be used the way a test is used.
- **It answers with the application down.** When nothing is listening on the port, the CLI opens the H2 file in process and runs the same queries, so `findings --since=start` still works after a crash.

## The loop

Find the jar before building it; `./gradlew :spider-sense-agent:senseJar` writes it under `spider-sense-agent/build/libs/` and it may already be there or wherever the user keeps it.

```bash
SENSE="$(ls spider-sense-agent/build/libs/spider-sense-*.jar 2>/dev/null | grep -v -- '-launcher' | head -1)"
[ -n "$SENSE" ] || { ./gradlew :spider-sense-agent:senseJar; SENSE="$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"; }
```

Then, in order:

```bash
java -javaagent:"$SENSE" -jar build/libs/app.jar &   # 1. start the application under the agent
java -jar "$SENSE" status                            # confirm it is collecting
java -jar "$SENSE" mark before                       # 2. name the moment
curl -s http://localhost:8080/orders/42 >/dev/null   #    exercise: the endpoints in question, the tests, or the load generator
java -jar "$SENSE" findings --since=before           # 3. read the top finding
java -jar "$SENSE" trace 4bf92f3577b34da6a3ce929d0e0e4736   #    open its evidence, locate the code
#                                                    # 4. fix, rebuild, restart
java -jar "$SENSE" mark after                        # 5. exercise the same way
java -jar "$SENSE" compare --before=before --after=after
java -jar "$SENSE" check --since=after --max-p95-ms=300 --max-n-plus-one=0
```

After a restart there is a fresh `start` mark, so `--since=start` covers the new run without marking anything.
`java -jar "$SENSE" init` writes the Spider Sense block into the project's `CLAUDE.md` — where the jar is, how to start the application under it, what the CLI answers — and installs this skill into `.claude/skills/spider-sense/`, so the next session finds both without being told.
`--url=<base url>` (or `SPIDERSENSE_URL`) points the CLI at a Spider Sense on another port; `--db=<path>` reads a database directly.

## Starting the application under the agent

The application has to be restarted with the agent on its command line; Spider Sense does not attach to a JVM that is already running.

| Situation | How |
|---|---|
| A jar, or a main class you launch | `java -javaagent:"$SENSE" -jar app.jar` |
| A start script from `installDist`, or anything honouring `JAVA_OPTS` | `JAVA_OPTS="-javaagent:$SENSE" build/install/app/bin/app` |
| A start command that is not yours to edit | `JAVA_TOOL_OPTIONS="-javaagent:$SENSE" <command>` |
| Spring Boot or `application` under Gradle | `id 'net.benelog.spidersense' version '0.1.0'` in `plugins {}`, then `./gradlew bootRun` (or `run`); `-PspiderSense.jar=$SENSE` for an unpublished jar; `./gradlew -q spiderSense --args="findings --since=start"` is the CLI |
| Spring Boot under Gradle, plugin not applied | build the jar and run it, or add `jvmArgs '-javaagent:...'` to `bootRun` |
| Maven Spring Boot | `mvn spring-boot:run -Dspring-boot.run.agents=$SENSE` (`-Dspring-boot.run.jvmArguments="-Dspidersense.port=4001"` for the properties) |

Useful properties, all after `-javaagent:` on the same command line:

- `-Dspidersense.port=4001` when 4000 is taken, and every CLI call then needs `--url=http://127.0.0.1:4001`.
- `-Dotel.service.name=spring-orders` so the service has a name instead of `unknown_service:java`; `-Dspidersense.service=` does the same.
- `-Dspidersense.collector=http://127.0.0.1:4000` to forward to a Spider Sense running elsewhere instead of hosting one, which is how two applications share a UI and how a trace that crosses them shows up in one place.
- `-Dspidersense.app.packages=com.acme` when a finding's `code` frames come out empty or full of framework classes.
- `-Dspidersense.ignore.endpoints=` takes endpoints out of the request count; health checks are ignored already (`/actuator/**,/health,/healthz,/livez,/readyz`), so set it only to add a pattern of your own, and set it to the empty value to ignore nothing.

Confirm with `java -jar "$SENSE" status`: it names the mode, the port, the database and how much it holds.
The UI is at <http://127.0.0.1:4000> for the user, not for you.
[references/running.md](references/running.md) has each build tool in full, including test tasks and what to do when the port is already in use.

## Reading findings

`findings` ranks by severity, then by impact, then by id, so the list is stable between two calls over the same data.
Each finding carries `why` (the numbers in a sentence), `numbers` (kind-specific), `statement` (when the finding is about one), `code` (application frames, innermost first, empty when none is known), and `traces` (at most three, the evidence).
The table is the ranked answer and the numbered blocks under it are that evidence, one per row, in the same order.

| Kind | What it means | What it usually wants |
|---|---|---|
| `n-plus-one` | the same query group ran 5 or more times under one entry span | a fetch join, batch loading, or one query with `IN (…)` |
| `slow-query` | a query group whose p95 is over `slow.query.ms` | an index, a rewrite, or not selecting what is not needed |
| `slow-endpoint`, high `dbShare` | most of the endpoint's time is in database spans | look at its queries; the fix is one of the two above |
| `slow-endpoint`, low `dbShare` | the time is elsewhere | look at the external call in its trace, or at the code itself |
| `error` | an error group with an occurrence in the window | the top application frame in `code` is where to start |
| `pool-exhausted` | pending requests above zero, or used equal to max | connections not being returned, or a pool too small for the concurrency |

`trace <id>` opens the evidence as an indented tree: one span per line with its offset and duration, the service named where it changes, repeated siblings collapsed after the third into `× n` with the average and the total, the statement under a slow or collapsed database span, the exception and its application frames under an error span, and the trace's log lines at the end.
`--full` expands the collapsed spans and keeps statements whole.
[references/findings.md](references/findings.md) has every kind with its rule, its numbers and a worked fix in Java.

## Verifying a change

```bash
java -jar "$SENSE" mark after
# exercise exactly as before: the same endpoints, the same number of times
java -jar "$SENSE" compare --before=before --after=after
java -jar "$SENSE" check --since=after --max-p95-ms=300 --max-queries-per-request=5 --max-errors=0
```

`compare` gives each endpoint, query and error a verdict: `worse` when errors appear or grow or p95 grows by more than 20% and at least 10 ms, `better` when the same shrinks by that much, `new`, `gone`, or `same`.
The verdict is the first column and the worst rows come first, so the top of each table is the answer; every other cell holds both windows, `before → after`, with `—` where a side has nothing.

`check` prints the verdict in its heading (`# check  fail  <window>`) and then one row per rule, with its `limit`, its `actual` value, its own `pass` or `fail` and a detail naming what decided it.
`check` exits `0` when it passed, `1` when it failed, `2` on a usage or connection error, `3` when there was no request to judge, and `4` when something named was not found.
Exit code `3` means the exercise step did not reach the application, not that the fix worked.
With no rule given the defaults are `--max-errors=0`, `--max-n-plus-one=0` and `--max-p95-ms=<slow.request.ms>`.

## Rules

- **Prefer the CLI's text output.** It is smaller than JSON and it is the same data; reach for `--json` only when a value has to be parsed.
- **Keep the window small.** The default `--since=15m` drags in whatever ran before; `--since=before` or `--since=start` answers about the run you care about.
- **Never quote a number the tool did not print.** Percentages, p95s and call counts come from the output, not from an estimate.
- **Quote the trace id as evidence.** A claim about an endpoint that names no trace cannot be checked by the user.
- **Run `check` before calling a fix done**, and say which rules it passed with which limits.
- **`sql` is the last resort, not the first.** `findings` and the tables come with the thresholds, the ranking and the evidence already applied; reach for [references/sql.md](references/sql.md) when the question is genuinely one none of them has a column for, and say that a capped answer was capped.
- **Do not change the monitored application's Spider Sense configuration unless asked.** Adding `-javaagent` to start it is the loop; editing the project's ports, thresholds or `spidersense.*` properties is a change to the project.
- Nothing in Spider Sense may keep the application from starting; if the agent fails it logs and gets out of the way, so an application that starts but sends nothing is a configuration question, answered by `status`.

## Where to look next

| Task | Read |
|---|---|
| Every command, its flags, its exit codes, and what its output looks like | [references/cli.md](references/cli.md) |
| Each finding kind: rule, severity, numbers, and the fix with Java examples | [references/findings.md](references/findings.md) |
| Starting each kind of application under the agent, ports, forwarding, troubleshooting | [references/running.md](references/running.md) |
| A question none of the commands answers: the schema, and read-only SQL over it | [references/sql.md](references/sql.md) |

`docs/agent.md` in the Spider Sense repository is the specification these files are distilled from; `docs/api.md` is the wire contract when the HTTP API is wanted instead of the CLI.
