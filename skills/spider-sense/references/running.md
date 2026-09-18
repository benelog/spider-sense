# Running an application under Spider Sense

Spider Sense is a `-javaagent`: it has to be on the JVM's command line when the application starts.
An application that is already running cannot be brought under it; restart it.
There is no supported way to attach later, and nothing else has to be installed — the jar carries the OpenTelemetry Java agent, the collector, the UI and the CLI.

## The jar

Look for it before building it.

```bash
ls spider-sense-agent/build/libs/spider-sense-*.jar   # in the Spider Sense repository
./gradlew :spider-sense-agent:senseJar                # builds it if it is not there
```

The single distributable jar is `spider-sense-<version>.jar`; a `-launcher` jar beside it is a build artifact, not the one to use.
Keep the absolute path in a variable, because every command below wants it:

```bash
SENSE="$PWD/spider-sense-agent/build/libs/spider-sense-0.1.0.jar"
```

## The three modes

| Mode | Command | When |
|---|---|---|
| Agent | `java -javaagent:"$SENSE" -jar app.jar` | one application; the collector and the UI run inside its JVM on port 4000 |
| Agent, forwarding | `java -javaagent:"$SENSE" -Dspidersense.collector=http://127.0.0.1:4000 -jar app.jar` | several applications sharing one Spider Sense, or an application that must not host a web server |
| Standalone | `java -jar "$SENSE"` | the collector and the UI only, for anything already speaking OTLP/HTTP |

Forwarding is what to use when two applications call each other, because a trace that crosses them then arrives in one place.
Start the standalone first, then each application with `-Dspidersense.collector=` pointing at it.

## Properties

All are system properties, given after `-javaagent:` on the same command line; the standalone jar also takes them as `--key=value`.

| Property | Default | Meaning |
|---|---|---|
| `spidersense.port` | `4000` | UI and OTLP/HTTP port |
| `spidersense.host` | `127.0.0.1` | bind address |
| `spidersense.collector` | unset | forward to this base URL instead of starting the embedded UI |
| `spidersense.service` | unset | sets `otel.service.name` |
| `spidersense.db` | `~/db/spider-sense/sense` | H2 database path or `jdbc:h2:` URL |
| `spidersense.retention.hours` | `24` | rows older than this are swept |
| `spidersense.slow.request.ms` | `500` | the slow-request threshold, and the Apdex scale |
| `spidersense.slow.query.ms` | `100` | the slow-query threshold |
| `spidersense.open` | `false` | open a browser at startup |
| `spidersense.app.packages` | unset | comma-separated package prefixes that count as application code in a finding's `code` frames |
| `spidersense.ignore.endpoints` | `/actuator/**,/health,/healthz,/livez,/readyz` | comma-separated glob patterns; a matching entry span is stored and in its trace but is not a request; an empty value ignores nothing |

Every `otel.*` system property and `OTEL_*` environment variable of the OpenTelemetry agent still applies; Spider Sense only fills in defaults.
`-Dotel.service.name=` is worth setting always, because the alternative is `unknown_service:java` and `--service=` then has nothing to select.

## Per build tool

### The Gradle plugin

For a Gradle project, Spring Boot or the `application` plugin, this is the shortest route and the only one that needs no absolute path in the build file:

```groovy
plugins {
    id 'net.benelog.spidersense' version '0.1.0'
}
```

`./gradlew bootRun` (or `run`) then starts the application under Spider Sense with the service named after the project, and the jar comes from Maven Central.
`-PspiderSense.jar=$SENSE` uses a jar that is not published, `-PspiderSense.enabled=false` runs without it, and `spiderSense { port = 4001 }` is where the `spidersense.*` properties go; the block is specified in `docs/build-tools.md` of the Spider Sense repository.
The plugin also makes the CLI a task, pointed at the port the block names, so `$SENSE` is not needed at all:

```bash
./gradlew -q spiderSense --args="findings --since=start"
./gradlew -q spiderSense --args="check --max-n-plus-one=0"
```

Prefer this over editing `jvmArgs` when the build file is the user's to change; the sections below are for everything else.

### A jar, or a main class

```bash
java -javaagent:"$SENSE" -Dotel.service.name=my-app -jar build/libs/my-app.jar
java -javaagent:"$SENSE" -Dotel.service.name=my-app -cp build/classes/java/main com.acme.Main
```

### A start script from `installDist` (the Gradle `application` plugin)

The generated script passes `JAVA_OPTS` and `<APPNAME>_OPTS` to the JVM, so nothing in the build has to change.

```bash
./gradlew installDist
JAVA_OPTS="-javaagent:$SENSE -Dotel.service.name=my-app" build/install/my-app/bin/my-app
```

`applicationDefaultJvmArgs` in `build.gradle` is the other place these arguments can live, but that is an edit to the project.

### Gradle `run`

The `run` task forks a JVM whose arguments come from the build, not from the shell, so either configure the task or avoid it.

```groovy
tasks.named('run') {
    jvmArgs '-javaagent:/absolute/path/to/spider-sense-0.1.0.jar', '-Dotel.service.name=my-app'
}
```

When the build file is not yours to edit, `./gradlew installDist` and the start script above is the cleaner route.
`JAVA_TOOL_OPTIONS` works too, but every JVM the command starts picks it up, including the Gradle daemon, which then tries to host a Spider Sense of its own on port 4000; use `--no-daemon` with it, or give the daemon nothing to collide with.

### Spring Boot, Gradle

The simplest path is to build the jar and run it, which is also what `scripts/demo.sh` does:

```bash
./gradlew bootJar
java -javaagent:"$SENSE" -Dotel.service.name=spring-orders -jar build/libs/spring-orders-0.1.0.jar
```

To stay on `bootRun` without the plugin:

```groovy
tasks.named('bootRun') {
    jvmArgs '-javaagent:/absolute/path/to/spider-sense-0.1.0.jar', '-Dotel.service.name=spring-orders'
}
```

### Spring Boot, Maven

The Spring Boot Maven plugin's `agents` parameter puts a jar on the forked JVM as `-javaagent:`, and `jvmArguments` carries the properties:

```bash
mvn spring-boot:run -Dspring-boot.run.agents="$SENSE" -Dspring-boot.run.jvmArguments="-Dspidersense.service=my-app"
```

The same two parameters go into the POM as `<agents><agent>…</agent></agents>` and `<systemPropertyVariables>` under the plugin's `<configuration>`, best inside a profile; `docs/build-tools.md` of the Spider Sense repository has the profile that also fetches the jar from Maven Central.

Or against the packaged jar, which needs no plugin configuration at all:

```bash
mvn -q package -DskipTests
java -javaagent:"$SENSE" -Dotel.service.name=my-app -jar target/my-app.jar
```

### A Gradle `test` task

Measuring what the tests do is the same loop with the test task as the exercise step.

```groovy
tasks.named('test') {
    jvmArgs '-javaagent:/absolute/path/to/spider-sense-0.1.0.jar',
            '-Dspidersense.collector=http://127.0.0.1:4000',
            '-Dotel.service.name=my-app-test'
}
```

Forwarding rather than embedding is the right mode here: a test JVM is short-lived and may be forked more than once, and several embedded instances would fight over the port.
Start `java -jar "$SENSE"` once in the background, run the tests, then read the window:

```bash
java -jar "$SENSE" mark before
./gradlew test --tests '*OrderServiceTest'
java -jar "$SENSE" findings --since=before --service=my-app-test
```

### Something that is not a JVM

Anything speaking OTLP/HTTP can send to a standalone Spider Sense:

```bash
java -jar "$SENSE" &
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4000
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

## Knowing it worked

```bash
java -jar "$SENSE" status
```

`status` names the mode (`agent` or `standalone`), the port, the OTLP endpoint, the database file and its size, the thresholds in force, the services seen and how many spans are stored.
`counts.services` including the application's service name, and a span count that grows after a request, is the confirmation.
The UI at <http://127.0.0.1:4000> is the same information for the user; leave the browser to them.

A restart also writes an automatic `start` mark for the service, so `marks` shows a new row with the note `pid <pid>` every time the application comes up, and `--since=start` means the current run.

## When it does not work

| Symptom | Cause |
|---|---|
| `status` says there is no Spider Sense at the url | Nothing is running on that port, or the application was started on another one. Pass `--url=http://127.0.0.1:<port>`, or read the file directly with `--db=` |
| The application starts but `counts.spans` stays at 0 | The agent did not attach: `-javaagent:` was not on the JVM's own command line (a wrapper script, a container, an IDE run configuration). Check the application's own stdout, where a failure in `premain` is logged |
| The service is called `unknown_service:java` | No `-Dotel.service.name=`; set it and restart |
| Port 4000 is already in use | Another Spider Sense, or another application under the agent, has it. Use `-Dspidersense.port=4001` and `--url=http://127.0.0.1:4001`, or point the second application at the first with `-Dspidersense.collector=http://127.0.0.1:4000` |
| Two applications, and each trace stops at the service boundary | Both are embedding their own Spider Sense. Put them in forwarding mode against one standalone |
| The application crashed and the UI went with it | The CLI reads the H2 file directly and says so on stderr; `findings --since=start` still answers |
| A finding's `code` is empty or full of framework classes | Only errors carry stack traces, and the package heuristic can guess wrong. Set `-Dspidersense.app.packages=com.acme` |
| The window is full of another run | Windows default to `15m`. Use `--since=start`, or mark a moment and use `--since=<mark>` |

Spider Sense never prevents the application from starting: a failure in its own startup is logged and swallowed, so an application that runs while Spider Sense collects nothing is a configuration question, and `status` plus the application's stdout answer it.
