# Spider Sense from a build tool

Spider Sense is a `-javaagent`, so it has to be on the command line of the JVM that runs the application.
A build tool forks that JVM itself when it runs the application for you — Gradle's `bootRun` and `run`, Maven's `spring-boot:run` — and the option has to reach the forked JVM, not the build tool's own.
This document is the specification of the two ways that happens: the Gradle plugin `net.benelog.spidersense`, and the parameters of the Spring Boot Maven plugin.
The jar's own options, and the modes it runs in, are in [design.md](design.md#configuration); this is only about getting it onto the command line.

## Gradle: the plugin

```groovy
plugins {
    id 'org.springframework.boot' version '4.1.1'
    id 'net.benelog.spidersense' version '0.1.0'
}
```

```bash
./gradlew bootRun
```

That is the whole setup: the application starts under Spider Sense, named after the project, and the UI is at <http://127.0.0.1:4000>.
The jar is resolved from Maven Central (`net.benelog.spidersense:spider-sense:0.1.0`, the plugin's own version) through the project's repositories, so `mavenCentral()` has to be among them, as it is in every Spring Boot project.
The plugin is a plain Gradle plugin published to Maven Central with its marker, which the Gradle Plugin Portal proxies, so the default `pluginManagement` finds it.

The plugin applies nothing else and configures nothing it was not asked to.
It works the same with the `application` plugin's `run` task, and a project with neither Spring Boot nor `application` gets the `spiderSense` block and the three tasks and nothing attached.

### What applying it does

1. Creates the `spiderSense` extension, the block below.
2. Creates the `spiderSense` configuration, resolvable and not consumable, non-transitive, with one default dependency: `net.benelog.spidersense:spider-sense:<version>`, where `version` is the block's property and defaults to the plugin's own version.
   A dependency added to it by the build replaces the default, which is how a project on a checkout of this repository uses the jar it just built: `dependencies { spiderSense project(path: ':spider-sense-agent', configuration: 'senseJar') }`.
3. Attaches to every `JavaExec` task whose name is in `attachTo` (default `bootRun`, `bootTestRun`, `run`) by adding a `CommandLineArgumentProvider` to the task's `jvmArgumentProviders`.
   The provider contributes, in this order, `-javaagent:<jar>` and then one `-Dspidersense.<key>=<value>` per property of the block that has a value; the jar is a declared input of the task, so a project dependency on it is built first.
   A task that is not in `attachTo`, or a run with `spiderSense.enabled` false, gets nothing: no argument, no jar resolution.
   Adding a provider rather than editing `jvmArgs` leaves the task's own `jvmArgs` alone, and the arguments are computed when the task runs, so a `spiderSense { }` block anywhere in the build file, before or after `tasks.named('bootRun')`, is seen.
4. Registers the tasks `spiderSense`, `spiderSenseInit` and `spiderSenseCheck` in the group `spider sense`.

Nothing here touches the Gradle daemon: a `jvmArgumentProvider` reaches only the forked JVM, which is the reason the plugin exists instead of `JAVA_TOOL_OPTIONS` ([the skill's running notes](../skills/spider-sense/references/running.md)).

### The block

Every property is a lazy Gradle `Property`; unset means "leave the jar's own default", and the jar's defaults are in [design.md](design.md#configuration).

| Property | Type | Default | Becomes |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | nothing is attached when `false`; the project property `spiderSense.enabled` (`-PspiderSense.enabled=false`, or `=true`) wins over the block |
| `version` | `String` | the plugin's version | the version of `net.benelog.spidersense:spider-sense` the default dependency names |
| `jar` | `RegularFile` | unset | the jar to attach instead of resolving one; the project property `spiderSense.jar` (`-PspiderSense.jar=/path/to/spider-sense.jar`) wins over the block |
| `attachTo` | `Set<String>` | `bootRun`, `bootTestRun`, `run` | the names of the `JavaExec` tasks that get the agent |
| `configFile` | `RegularFile` | unset | `-Dspidersense.config=`, the file's absolute path: a properties file of `spidersense.*` keys the launcher reads ([design.md](design.md#configuration)); every other property of the block wins over a key in the file |
| `service` | `String` | `project.name` | `-Dspidersense.service=`, which is `otel.service.name` unless that is set already |
| `port` | `Integer` | unset (`4000`) | `-Dspidersense.port=` |
| `host` | `String` | unset (`127.0.0.1`) | `-Dspidersense.host=` |
| `collector` | `String` | unset | `-Dspidersense.collector=`: forward to that Spider Sense instead of embedding one |
| `db` | `String` | unset (`~/db/spider-sense/sense`) | `-Dspidersense.db=` |
| `retentionHours` | `Integer` | unset (`24`) | `-Dspidersense.retention.hours=` |
| `slowRequestMs` | `Long` | unset (`500`) | `-Dspidersense.slow.request.ms=` |
| `slowQueryMs` | `Long` | unset (`100`) | `-Dspidersense.slow.query.ms=` |
| `open` | `Boolean` | unset (`false`) | `-Dspidersense.open=`: open the browser at startup |
| `appPackages` | `List<String>` | unset | `-Dspidersense.app.packages=`, the list joined with commas |
| `ignoreEndpoints` | `List<String>` | unset (the jar's default list) | `-Dspidersense.ignore.endpoints=`, the list joined with commas; an empty list set explicitly (`ignoreEndpoints = []`) passes an empty value, which ignores nothing |
| `retentionSpans` | `Long` | unset (`1000000`) | `-Dspidersense.retention.spans=` |
| `maxSpansPerSecond` | `Long` | unset | `-Dspidersense.ingest.max-spans-per-second=` |
| `check { }` | a nested block | see [Check as a build step](#check-as-a-build-step) | the rules of the `spiderSenseCheck` task |

Where the jar comes from, in order: the project property `spiderSense.jar`, then the block's `jar`, then the single file of the `spiderSense` configuration.
More than one file in the configuration, or none, is an error naming the configuration when a task that needs the jar runs.

The block sets only `spidersense.*` properties.
A `spider-sense.properties` in the project directory is read by the launcher as well, because the forked JVM's working directory is the project's, and `configFile` names another one; the block's `-D` properties win over the file either way ([design.md](design.md#configuration)).
Everything the OpenTelemetry agent takes as `otel.*` goes on the task as usual, and the two combine:

```groovy
spiderSense {
    port = 4001
    slowQueryMs = 50
    appPackages = ['com.acme.orders']
}

tasks.named('bootRun') {
    jvmArgs '-Dotel.instrumentation.jdbc-datasource.enabled=true'
}
```

### Switching it off and on

`./gradlew bootRun -PspiderSense.enabled=false` runs the application bare, and `spiderSense { enabled = false }` with `-PspiderSense.enabled=true` is the other way round: kept in the build, attached on request.
An `enabled` of `false` is the same as not applying the plugin, except that the block and the tasks are still there.

### The tasks

`spiderSense` runs the jar exactly as `java -jar spider-sense.jar` would, so both the standalone server and the CLI are one Gradle task away in a project that never checked out this repository:

```bash
./gradlew spiderSense                                   # the standalone collector + UI; Ctrl-C stops it
./gradlew -q spiderSense --args="findings --since=start" # the CLI (docs/agent.md); -q keeps Gradle's own lines out
./gradlew -q spiderSense --args="check --max-n-plus-one=0"
```

It is a `JavaExec` with the jar as its only class path, `net.benelog.spidersense.launcher.SpiderSenseMain` as its main class, and no dependency on compiling the project.
The block's `spidersense.*` properties are passed to it as they are to the application, so a standalone started this way listens where the block says.
When the block names a `collector`, a `host` or a `port`, it also sets the environment variable `SPIDERSENSE_URL` to the base URL they imply — `collector` when set, else `http://<host>:<port>` with the defaults filled in — so a CLI command asks the Spider Sense the application is sending to rather than the default port.
When it names none of them the variable stays unset, and the CLI's own default applies: what the `spidersense.*` properties the task gets imply, which is how a port in `configFile`, or in `spider-sense.properties`, is followed too ([agent.md](agent.md)).
The exit code is the CLI's exit code, which is what `check` is for.

`spiderSenseInit` runs `init --dir=<the project directory> --jar=<the jar>` ([agent.md](agent.md#init)): it writes the Spider Sense block into the project's `CLAUDE.md` and installs the skills into `.claude/skills/`.
The jar path it writes is wherever Gradle resolved the jar to, a file under `~/.gradle/caches/` for a Maven Central jar, which stays valid until the version changes; run it again after a `version` bump.

### Check as a build step

`spiderSenseCheck` runs the CLI's `check` ([agent.md](agent.md#check)) with the rules of the `check { }` block and fails the build when the verdict is `fail`:

```groovy
spiderSense {
    check {
        since = 'start'          // the default: since the application was last started
        maxP95Ms = 300
        maxNPlusOne = 0
        maxErrors = 0
    }
}
```

```bash
./gradlew bootRun &                  # or the test task forwarding to a standalone, as below
./gradlew test spiderSenseCheck
```

| Property | Type | Becomes |
|---|---|---|
| `since` | `String` | `--since=`; default `start` |
| `until` | `String` | `--until=` |
| `service` | `String` | `--service=`; default the block's `service` |
| `endpoint` | `String` | `--endpoint=` |
| `maxP95Ms` | `Long` | `--max-p95-ms=` |
| `maxErrors` | `Long` | `--max-errors=` |
| `maxErrorRate` | `Double` | `--max-error-rate=` |
| `maxQueriesPerRequest` | `Double` | `--max-queries-per-request=` |
| `maxSlowQueries` | `Long` | `--max-slow-queries=` |
| `maxNPlusOne` | `Long` | `--max-n-plus-one=` |
| `maxLogErrors` | `Long` | `--max-log-errors=` |
| `minApdex` | `Double` | `--min-apdex=` |
| `failOnNoRequests` | `Boolean` | default `true`: exit code `3` (no request in the window) fails the build too, because a check that judged nothing is not a pass |

The task is the `spiderSense` task with `check` and those arguments, so it asks the Spider Sense the block implies (`SPIDERSENSE_URL` as above, or the CLI's own default) and prints the check's text rendering; exit code `1` fails the build with `Spider Sense check failed`, `3` with `Spider Sense check had no request to judge` unless `failOnNoRequests` is `false`, and `2` or `4` with `Spider Sense check could not run (exit <n>)`, after the CLI's own message has reached the build log.
With no rule set the CLI's defaults apply (`maxErrors=0`, `maxNPlusOne=0`, `maxP95Ms=<slow.request.ms>`).
`-PspiderSense.check.since=before` overrides `since` for one run.
The task depends on nothing: producing the traffic it judges is the build's job, as in the test setup below.

### Tests

The test task is not attached by default, because a test JVM is short-lived and may be forked several times, and several embedded servers would fight over one port.
The way to measure tests is to forward to a standalone Spider Sense, which is one line each:

```groovy
spiderSense {
    attachTo.add('test')
    collector = 'http://127.0.0.1:4000'
}
```

```bash
./gradlew spiderSense &                           # once
./gradlew -q spiderSense --args="mark before"
./gradlew test --tests '*OrderServiceTest'
./gradlew -q spiderSense --args="findings --since=before"
```

With `collector` set, `bootRun` forwards too; that is what is wanted when the tests and the running application should land in one place.

### Two applications, several modules

Each module that applies the plugin gets its own block, so in a multi-project build the applications are configured where they are:

```groovy
// orders/build.gradle
spiderSense { port = 4001 }
// bookstore/build.gradle
spiderSense { port = 4000 }
```

When one calls the other and the trace should be one trace, run a standalone and forward both:

```groovy
spiderSense { collector = 'http://127.0.0.1:4000' }
```

### Spring Boot devtools

A devtools restart replaces the application's classes inside the same JVM; the agent stays attached, the embedded UI keeps running, and the restarted classes are instrumented like the first ones.
Nothing to configure.

### A jar that is not on Maven Central

Three ways, for a version that is not published yet or a jar built from a checkout:

```bash
./gradlew bootRun -PspiderSense.jar=/home/me/spider-sense/spider-sense-agent/build/libs/spider-sense-0.1.0.jar
```

```groovy
spiderSense {
    jar = file('/home/me/tools/spider-sense.jar')
}
```

```groovy
// In this repository: the jar the build just made, built before bootRun runs.
dependencies {
    spiderSense project(path: ':spider-sense-agent', configuration: 'senseJar')
}
```

`./gradlew publishToMavenLocal` in this repository publishes both the jar and the plugin to `~/.m2`, after which `mavenLocal()` in `repositories` and in `pluginManagement.repositories` makes an unreleased version resolvable like a released one.

### Configuration cache

The plugin is compatible with Gradle's configuration cache: every value is a provider, the argument provider holds no `Project`, and the jar is a `FileCollection` input.

## Maven

The Spring Boot Maven plugin already has the two parameters this needs, so there is no Spider Sense Maven plugin: `agents` (user property `spring-boot.run.agents`) takes agent jars and puts each on the forked JVM as `-javaagent:`, and `jvmArguments` (`spring-boot.run.jvmArguments`) or `systemPropertyVariables` carry the `spidersense.*` properties.
Both belong to `spring-boot:run` and to `spring-boot:start`, the goal that runs the application around integration tests.

### Once, from the command line

```bash
mvn spring-boot:run -Dspring-boot.run.agents=/home/me/tools/spider-sense.jar
mvn spring-boot:run -Dspring-boot.run.agents=/home/me/tools/spider-sense.jar \
    -Dspring-boot.run.jvmArguments="-Dspidersense.port=4001 -Dspidersense.service=orders"
```

Without `spidersense.service` the service is `unknown_service:java`, because Maven has no equivalent of the Gradle plugin's project-name default.

### In the POM

The `maven-dependency-plugin` copies the jar from Maven Central into `target/` before the application starts (`spring-boot:run` runs the `test-compile` phase first, so a copy bound to `initialize` is done by then), and the run goal names it as an agent.
In a profile, so `mvn spring-boot:run -Psense` is the run under Spider Sense and `mvn spring-boot:run` is the plain one:

```xml
<profiles>
  <profile>
    <id>sense</id>
    <properties>
      <spider-sense.version>0.1.0</spider-sense.version>
      <spider-sense.jar>${project.build.directory}/spider-sense.jar</spider-sense.jar>
    </properties>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-dependency-plugin</artifactId>
          <executions>
            <execution>
              <id>spider-sense</id>
              <phase>initialize</phase>
              <goals>
                <goal>copy</goal>
              </goals>
              <configuration>
                <artifactItems>
                  <artifactItem>
                    <groupId>net.benelog.spidersense</groupId>
                    <artifactId>spider-sense</artifactId>
                    <version>${spider-sense.version}</version>
                    <outputDirectory>${project.build.directory}</outputDirectory>
                    <destFileName>spider-sense.jar</destFileName>
                  </artifactItem>
                </artifactItems>
              </configuration>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <configuration>
            <agents>
              <agent>${spider-sense.jar}</agent>
            </agents>
            <systemPropertyVariables>
              <spidersense.service>${project.artifactId}</spidersense.service>
              <spidersense.port>4000</spidersense.port>
            </systemPropertyVariables>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

A jar that is not on Maven Central goes in as a path: drop the `maven-dependency-plugin` execution and set `spider-sense.jar` to the file, in the profile or on the command line with `-Dspider-sense.jar=/home/me/tools/spider-sense.jar`.

The CLI is the same jar, so once it is in `target/`:

```bash
java -jar target/spider-sense.jar findings --since=start
java -jar target/spider-sense.jar init            # CLAUDE.md block and the skills, docs/agent.md
```

### Tests under Surefire

Surefire forks its own JVM and takes its options from `argLine`; forward rather than embed, for the reason given for Gradle above:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-javaagent:${spider-sense.jar} -Dspidersense.collector=http://127.0.0.1:4000 -Dspidersense.service=${project.artifactId}-test</argLine>
  </configuration>
</plugin>
```

### The packaged jar

Nothing in the POM at all:

```bash
mvn -q package -DskipTests
java -javaagent:/home/me/tools/spider-sense.jar -Dspidersense.service=orders -jar target/orders-0.0.1.jar
```

## Coordinates

| Artifact | Coordinates | What it is |
|---|---|---|
| The jar | `net.benelog.spidersense:spider-sense:<version>` | the single distributable jar from `:spider-sense-agent:senseJar`; its POM declares no dependencies |
| The Gradle plugin | `net.benelog.spidersense:spider-sense-gradle-plugin:<version>`, marker `net.benelog.spidersense:net.benelog.spidersense.gradle.plugin` | the plugin above, id `net.benelog.spidersense` |

Both are published from this repository by `./gradlew publishToMavenLocal` (to `~/.m2`) and, signed, by `./gradlew centralBundle` into one bundle for the Central Portal ([RELEASING.md](../RELEASING.md)).
The version of both is `version` in the root `gradle.properties`, and the plugin's default `version` for the jar is that same value, so a plugin and the jar it resolves are always the same release.
