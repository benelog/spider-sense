# spider-sense-agent

This module is the launcher and the packaging task that assembles `spider-sense-<version>.jar`, the one jar the whole product ships as.

## What is in the jar

`./gradlew :spider-sense-agent:senseJar` writes `spider-sense-agent/build/libs/spider-sense-0.1.0.jar`, roughly 33 MB, containing four things.

- The stock OpenTelemetry Java agent 2.31.1, verbatim: its bootstrap classes at the top level (`io/opentelemetry/javaagent/**`), everything else under `inst/**` as `.classdata`, plus `META-INF/licenses/**` and `META-INF/native-image/**`.
- The launcher, `net/benelog/spidersense/launcher/**`: six small classes with no dependencies at all, because the agent appends this whole jar to the bootstrap class path.
- The collector and UI as a nested fat jar at `spider-sense/server.jar`, extracted at startup to `${java.io.tmpdir}/spider-sense-<version>/server.jar` and loaded by `SenseClassLoader`, whose parent is the platform class loader.
- A manifest that makes the same file a Java agent and an executable jar: `Premain-Class`/`Agent-Class: net.benelog.spidersense.launcher.SpiderSenseAgent`, `Main-Class: net.benelog.spidersense.launcher.SpiderSenseMain`, `Can-Redefine-Classes` and `Can-Retransform-Classes`.

The OpenTelemetry agent's own manifest is replaced by ours, which its jar check accepts because it only requires a `Premain-Class` attribute to be present.
Its version is kept where it looks for it: `io.opentelemetry.javaagent.tooling.AgentVersion` reads `OpenTelemetryAgent.class.getPackage().getImplementationVersion()`, so the manifest carries a per-package section `io/opentelemetry/javaagent/` with `Implementation-Version: 2.31.1` while the main attributes carry Spider Sense's own version.

## The three ways to run it

Agent mode, Glowroot style: the application is instrumented and the UI runs inside it.

```bash
java -javaagent:spider-sense.jar -jar app.jar
# [spider-sense] UI: http://127.0.0.1:4000
```

Agent mode, forwarding: the same instrumentation, no embedded UI, several applications sharing one Spider Sense.

```bash
java -javaagent:spider-sense.jar -Dspidersense.collector=http://127.0.0.1:4000 -jar app.jar
# [spider-sense] forwarding to http://127.0.0.1:4000
```

Standalone: the collector and UI alone, for anything that speaks OTLP/HTTP.

```bash
java -jar spider-sense.jar
java -jar spider-sense.jar --port=4005 --db=~/db/spider-sense/other
```

A sender that is not a JVM needs only two environment variables.

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4000
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

Agent mode needs Java 21 or later, because the embedded UI is a Spider Silk application; the monitored application itself can be any JVM the OpenTelemetry agent supports when it forwards instead.

## Configuration

Everything is a system property under `-javaagent`, because `premain` runs before there are any arguments; the standalone jar takes the same keys as `--key=value` without the prefix, and those win over the properties.

| Property | Default | Meaning |
|---|---|---|
| `spidersense.port` | `4000` | UI + OTLP/HTTP port |
| `spidersense.host` | `127.0.0.1` | bind address; `0.0.0.0` to reach it from another machine |
| `spidersense.collector` | unset | agent mode: forward to this base URL instead of starting the embedded UI |
| `spidersense.service` | unset | agent mode: sets `otel.service.name` |
| `spidersense.db` | `~/db/spider-sense/sense` | H2 database path or `jdbc:h2:` URL (`AUTO_SERVER=TRUE` is appended to a path) |
| `spidersense.retention.hours` | `24` | rows older than this are deleted by the sweeper |
| `spidersense.slow.request.ms` | `500` | a server span slower than this is a "tingle" |
| `spidersense.slow.query.ms` | `100` | a DB span slower than this is a "tingle" |
| `spidersense.open` | `false` | agent mode: open the browser at startup (`java.awt.Desktop`), best effort |

`spidersense.db` and `spidersense.retention.hours` are passed to the server only when set, so their defaults live in one place, the server.

The launcher also fills in OpenTelemetry defaults, and only where neither the property nor its environment variable is already set: `otel.exporter.otlp.protocol=http/protobuf`, `otel.exporter.otlp.endpoint` pointing at the UI or the collector, `otel.bsp.schedule.delay=1000`, `otel.blrp.schedule.delay=1000`, `otel.metric.export.interval=5000`, the three exporters to `otlp`, `otel.instrumentation.runtime-telemetry.enabled=true`, and `otel.javaagent.exclude-class-loaders=net.benelog.spidersense.launcher.SenseClassLoader` so the UI's own Jetty never becomes a span.

## Troubleshooting

**Port already in use.** Another Spider Sense or another program holds 4000; start on a different one with `-Dspidersense.port=4001` (or `--port=4001` standalone), and remember that the port is also the OTLP endpoint, so a forwarding application must be pointed at the same number.

**The application runs on Java 17 or older.** The embedded UI needs Java 21, so agent mode cannot serve it there; run Spider Sense standalone in a Java 21 JVM and put the application in forwarding mode with `-Dspidersense.collector=http://127.0.0.1:4000`, which works on any JVM the OpenTelemetry agent supports.

**Nothing appears in the UI.** Check the first lines of the application's output for `[otel.javaagent ... version: 2.31.1` (the agent installed) and `[spider-sense] UI: ...` (the collector bound); an `otel.*` property or environment variable you set yourself always wins over our defaults, so `OTEL_TRACES_EXPORTER=none` or an `OTEL_EXPORTER_OTLP_ENDPOINT` pointing elsewhere silently sends the data somewhere else.

**Two processes may share the database at once** (H2 `AUTO_SERVER`), so the UI in a standalone `java -jar spider-sense.jar` shows what the applications wrote even after they stopped; point a run at its own file with `-Dspidersense.db=~/db/spider-sense/experiment` when you want a clean slate, or use `DELETE /api/data`.

**Spider Sense monitoring itself.** The class-loader exclusion keeps the UI's own requests out; if you set `otel.javaagent.exclude-class-loaders` yourself we add our loader to your list rather than replace it, so a `SERVER` span from the UI's own port means something overrode the property after `premain`.

**Starting the UI failed.** Nothing here may stop the application, so every failure of ours is one `[spider-sense] ...` line on stderr and then silence; the application starts, instrumented, with no UI.

## Build and test

```bash
./gradlew :spider-sense-agent:test              # the launcher's unit tests
./gradlew :spider-sense-agent:senseJar          # the distributable jar
./gradlew :spider-sense-agent:integrationTest   # packages the jar, then runs a real JVM under it
```

`SingleJarIT` spawns `java -javaagent:<the jar> ... SampleApp`, polls the UI inside that JVM until the sample's own client spans are there, checks that the UI's server spans are not, and finally checks that the process exits by itself with status 0, which is what proves Jetty's pool, the storage writer and H2's threads are all daemon threads.
