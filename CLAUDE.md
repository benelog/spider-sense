# Spider Sense

A local-development APM: one jar, `-javaagent`, OpenTelemetry-native, UI built with Spider Silk.
Read `docs/design.md` (architecture and decisions), `docs/storage.md` (the H2 schema, writer, queries, retention), `docs/api.md` (the JSON contract between server and UI), `docs/ui.md` (pages, look and feel), `docs/agent.md` (findings, marks, compare, check, the text renderings, the CLI and the skill: what AI agents use) and `docs/build-tools.md` (the Gradle plugin and the Maven setup) before changing anything; they are the specification, and a change to behaviour is a change to them first.

## Modules

| Module | What it is |
|---|---|
| `spider-sense-server` | Spider Silk app: OTLP/HTTP receiver, in-memory store, JSON API, static UI under `src/main/resources/public`. Builds a fat jar (`shadowJar`). |
| `spider-sense-agent` | The launcher (`premain`/`main`, no dependencies) and the packaging task that assembles the single distributable jar: OpenTelemetry Java agent + launcher + nested server jar + nested extension jar. |
| `spider-sense-gradle-plugin` | An included build (`pluginManagement.includeBuild`), not a subproject: the Gradle plugin `net.benelog.spidersense` that puts `-javaagent` on `bootRun`/`run`, the `spiderSense` block, and the `spiderSense`/`spiderSenseInit` tasks (`docs/build-tools.md`). Published to Maven Central with the jar. |
| `spider-sense-extension` | The OpenTelemetry agent extension: one `SpanProcessor` that records `code.stacktrace` on a database span slower than `slow.query.ms`. Compiled `compileOnly` against the SDK, nested as `spider-sense/extension.jar`. |
| `examples/silk-bookstore` | Spider Silk + spring-jdbc + H2 example app with deliberately slow queries and endpoints (port 8081). |
| `examples/spring-orders` | Spring Boot + Spring Data JPA + H2 example app, calls silk-bookstore over HTTP (port 8082). |
| `examples/load-gen` | Traffic generator for both apps. |
| `skills/spider-sense` | The agent skill: how to run the loop (start under the agent, mark, exercise, findings, fix, compare, check) with references beside it. |

## Rules

- **Spider Silk principles apply to the server**: no reflection-based frameworks, no DI container, routes registered explicitly, JSON written with `Json`/`JsonWriter`, handlers are `WebRequest -> WebResponse`. The Spider Silk agent skill is at `../spider-silk/skills/spider-silk/SKILL.md` with references beside it; consult it before writing web code.
- **The launcher stays dependency-free and tiny.** Everything else lives in the nested server jar loaded by `SenseClassLoader`.
- **Nothing in Spider Sense may prevent the monitored application from starting.** Every failure in `premain` is logged and swallowed.
- **Storage is H2 under `~/db/spider-sense/`** (`docs/storage.md`), opened with `AUTO_SERVER=TRUE` so several processes share it and the data outlives the monitored application. Plain JDBC, no ORM; nothing else keeps state.
- **Every agent-facing answer comes from the same `Queries` as the UI**, and its text rendering is deterministic over the window (`docs/agent.md`); the CLI never renders on its own, it prints what the server or the in-process renderer produced.
- **No frontend build.** Plain HTML/CSS/ES modules; the only vendored library is uPlot.
- Versions shared across modules are declared once in the root `build.gradle` `ext` block; the release version is `version` in `gradle.properties`, which the jar, the plugin, and the plugin's default jar version all read.
- **The plugin adds arguments, it never edits a task's own `jvmArgs`**, and every value in it is a lazy provider (configuration-cache safe); the examples apply it against the jar the build just made, never against Maven Central.
- Markdown is one sentence per line (as in Spider Silk).
- Commit messages say what the change does, without conventional-commit prefixes and without issue references.

## Build / run

```bash
./gradlew build                                   # every module, all tests
./gradlew :spider-sense-agent:senseJar            # the single jar: spider-sense-agent/build/libs/spider-sense-<version>.jar
java -jar spider-sense-agent/build/libs/spider-sense-<version>.jar findings --since=start   # the CLI (docs/agent.md)
./gradlew :examples:spring-orders:bootRun          # the example under the Gradle plugin (docs/build-tools.md)
./gradlew publishToMavenLocal                     # the jar and the plugin into ~/.m2, for a project outside this repository
scripts/demo.sh                                   # both example apps, each with its own embedded Spider Sense (:4000, :4001), plus the load generator
scripts/demo-shared.sh                            # one standalone Spider Sense both apps forward to
```

Ports: silk-bookstore 8081 (embedded Spider Sense 4000), spring-orders 8082 (embedded Spider Sense 4001); the shared standalone Spider Sense also uses 4000.
H2 files for the examples live under `~/db/spider-sense/`.
