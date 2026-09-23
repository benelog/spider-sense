# Spider Sense

A local-development observability tool: one jar, `-javaagent`, OpenTelemetry-native, UI built with Spider Silk.
Read `docs/design.md` (architecture and decisions), `docs/storage.md` (the H2 schema, writer, queries, retention), `docs/api.md` (the JSON contract between server and UI), `docs/ui.md` (pages, look and feel), `docs/agent.md` (findings, marks, compare, check, the text renderings, the CLI and the skill: what AI agents use) and `docs/build-tools.md` (the Gradle plugin and the Maven setup) before changing anything; they are the specification, and a change to behaviour is a change to them first.

## Modules

| Module | What it is |
|---|---|
| `spider-sense-server` | Spider Silk app: OTLP/HTTP receiver, in-memory store, JSON API, static UI under `src/main/resources/public`. Builds a fat jar (`shadowJar`). |
| `spider-sense-agent` | The launcher (`premain`/`main`, no dependencies) and the packaging task that assembles the single distributable jar: OpenTelemetry Java agent + launcher + nested server jar + nested extension jar. |
| `spider-sense-gradle-plugin` | An included build (`pluginManagement.includeBuild`), not a subproject: the Gradle plugin `net.benelog.spidersense` that puts `-javaagent` on `bootRun`/`run`, the `spiderSense` block, and the `spiderSense`/`spiderSenseInit`/`spiderSenseCheck` tasks (`docs/build-tools.md`). Published to Maven Central with the jar. |
| `spider-sense-extension` | The OpenTelemetry agent extension: one `SpanProcessor` that records `code.stacktrace` on a database span slower than `slow.query.ms`. Compiled `compileOnly` against the SDK, nested as `spider-sense/extension.jar`. |
| `examples/spring-orders` | Spring Boot + Spring Data JPA + H2 example app, calls silk-bookstore over HTTP (port 8082). |
| `examples/servlet-warehouse` | Jakarta Servlet on embedded Tomcat + Tomcat JDBC pool + H2 example app: servlet-mapping endpoints, a filter, an async servlet, Tomcat's error page (port 8083). |
| `examples/batch-worker` | A worker with no HTTP server: `@WithSpan` scheduled jobs over HikariCP + Logback + H2, for `slow-job`, `log-error`, `pool-exhausted`, `thread-growth`. |
| `examples/silk-bookstore` | Spider Silk + spring-jdbc + H2 example app with deliberately slow queries and endpoints (port 8081). |
| `examples/load-gen` | Traffic generator for the three web apps. |
| `skills/spider-sense` | The agent skill: how to run the loop (start under the agent, mark, exercise, findings, fix, compare, check) with references beside it. Both skills are linked from `.claude/skills/` and `.agents/skills/`, so Claude Code (`/spider-sense`) and Codex (`$spider-sense`) find them in this checkout. |
| `skills/spider-sense-sql-tuning` | The query-tuning agent skill: an index, a rewrite, a fetch join, a batch, each verified with `compare` and `check`. |

## Rules

- **Spider Silk principles apply to the server**: no reflection-based frameworks, no DI container, routes registered explicitly, JSON written with `Json`/`JsonWriter`, handlers are `WebRequest -> WebResponse`. The Spider Silk agent skill is at `../spider-silk/skills/spider-silk/SKILL.md` with references beside it; consult it before writing web code.
- **The launcher stays dependency-free and tiny.** Everything else lives in the nested server jar loaded by `SenseClassLoader`.
- **Nothing in Spider Sense may prevent the monitored application from starting.** Every failure in `premain` is logged and swallowed.
- **Storage is H2 under `~/db/spider-sense/`** (`docs/storage.md`), opened with `AUTO_SERVER=TRUE` so several processes share it and the data outlives the monitored application. Plain JDBC, no ORM; nothing else keeps state.
- **Every agent-facing answer comes from the same `Queries` as the UI**, and its text rendering is deterministic over the window (`docs/agent.md`); the CLI never renders on its own, it prints what the server or the in-process renderer produced.
- **No frontend build.** Plain HTML/CSS/ES modules; the only vendored library is uPlot.
- Versions shared across modules are declared once in the root `build.gradle` `ext` block; the release version is `version` in `gradle.properties`, which the jar, the plugin, and the plugin's default jar version all read.
  The Gradle plugin is an included build and reads none of that block, so it repeats the Error Prone, NullAway, and JSpecify versions in its own `build.gradle`; a bump lands in both places.
- **Error Prone runs inside every javac, and NullAway with it on main code.** Every main package is `@NullMarked` (a `package-info.java` per package, and a new package gets one), so a null reaching a non-`@Nullable` type is a compile error; nullness is written with JSpecify's `@Nullable` (a type-use annotation: `@Nullable Foo`, `Foo @Nullable []`), on the compile class path only, so no jar ships it.
  A finding is fixed in the code; a suppression carries a comment saying why, and a check is disabled in a build file only when it misreads a convention the whole module follows (`InjectOnConstructorOfAbstractClass` on Gradle's managed types, in the plugin).
  Tests are exempt from NullAway (they pass null on purpose) but not from the other checks.
- **The plugin adds arguments, it never edits a task's own `jvmArgs`**, and every value in it is a lazy provider (configuration-cache safe); the examples apply it against the jar the build just made, never against Maven Central.
- Markdown is one sentence per line (as in Spider Silk), and so is the AsciiDoc under `manual/`.
- **The manual lives in `manual/`, as an Antora component, and is published at <https://spider-sense.benelog.net> by `.github/workflows/docs.yml`.**
  Pages are AsciiDoc under `manual/modules/ROOT/pages/`, one chapter per file, listed in `manual/modules/ROOT/nav.adoc`; a new chapter is a new page plus a `nav.adoc` entry under one of the existing groups.
  `docs/*.md` stay the specification and the manual is what a user reads: a change to behaviour lands in the spec first and in the page that covers it in the same commit, and the manual never says more than the spec does.
  `manual/antora.yml` carries the version attributes (`project-version`, `otel-agent-version`, `spider-silk-version`) that every page reads; the release procedure updates them.
  `npm install && npm run docs` builds the site into `build/site`, and fails on a broken xref (`failure_level: warn`).
  The site lists versions: `main` is the unreleased manual, and every `docs/x.y.z` branch, cut by `scripts/docs-branch.sh` at release time, is a released one; the latest release answers at the site root with no version in its URL.
  The screenshots the README shows live in `manual/modules/ROOT/images/`, so the README and the manual share one copy.
  The prose rules are Spider Silk's `.claude/skills/doc-tone/SKILL.md`: lead with the conclusion, one idea per sentence, the register of a technical reference manual.
- Commit messages say what the change does, without conventional-commit prefixes and without issue references, in the subject or the body.
- When a commit resolves or advances a GitHub issue, the link goes the other way: after pushing, comment on the issue with the commit URL (`gh issue comment <n> --body "…"`), and close the issue from that comment when the work is complete.

## Build / run

```bash
./gradlew build                                   # every module, all tests
./gradlew :spider-sense-agent:senseJar            # the single jar: spider-sense-agent/build/libs/spider-sense-<version>.jar
java -jar spider-sense-agent/build/libs/spider-sense-<version>.jar findings --since=start   # the CLI (docs/agent.md)
./gradlew :examples:spring-orders:bootRun          # the example under the Gradle plugin (docs/build-tools.md)
./gradlew publishToMavenLocal                     # the jar and the plugin into ~/.m2, for a project outside this repository
npm install && npm run docs                       # the manual as a site, into build/site
scripts/demo-shared.sh                            # one standalone Spider Sense (:4000) all four example apps forward to, plus the load generator
scripts/demo.sh                                   # each example app with its own embedded Spider Sense (:4000 to :4003)
```

Ports: silk-bookstore 8081 (embedded Spider Sense 4000), spring-orders 8082 (4001), servlet-warehouse 8083 (4002), batch-worker no HTTP port (4003); the shared standalone Spider Sense also uses 4000.
H2 files for the examples live under `~/db/spider-sense/`.
