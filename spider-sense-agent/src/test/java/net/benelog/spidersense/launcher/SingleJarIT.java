package net.benelog.spidersense.launcher;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole product, from the packaged jar: a real JVM, {@code -javaagent:spider-sense.jar}, a
 * sample application, and the UI answering inside the same process.
 *
 * <p>Tagged {@code integration} and run by the {@code integrationTest} task, which packages
 * {@code senseJar} first and passes its path in {@code spidersense.it.jar}.
 */
@Tag("integration")
class SingleJarIT {

    private static Path senseJar;
    private static String testClasses;
    private static String sampleClasspath;
    private static Path javaBinary;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** How many times SampleApp calls its own {@code /hello}. */
    private static final int SAMPLE_CALLS = 3;

    @TempDir
    Path work;

    @BeforeAll
    static void locateEverything() {
        senseJar = Path.of(required("spidersense.it.jar"));
        testClasses = required("spidersense.it.testClasses");
        sampleClasspath = required("spidersense.it.sampleClasspath");
        javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        assertThat(senseJar).as("the packaged jar").isRegularFile();
    }

    private static String required(String property) {
        String v = System.getProperty(property);
        assertThat(v).as(property + " must be set by the integrationTest task").isNotNull();
        return v;
    }

    // --- agent mode ---------------------------------------------------------------------------

    @Test
    void agentModeInstrumentsTheApplicationAndServesTheUiInsideIt() throws Exception {
        int port = freePort();
        Path log = work.resolve("agent.log");
        String base = "http://127.0.0.1:" + port;

        Process app = start(log,
                javaBinary.toString(),
                "-javaagent:" + senseJar,
                "-Dspidersense.port=" + port,
                "-Dspidersense.db=" + throwawayDatabase(),
                "-Dotel.service.name=sample",
                "-cp", testClasses,
                "net.benelog.spidersense.launcher.SampleApp");

        String status;
        String traces;
        String services;
        try {
            status = await("GET /api/status answering in agent mode", log, app,
                    () -> get(base + "/api/status"),
                    body -> compact(body).contains("\"mode\":\"agent\""));

            // Every assertion below reads this one snapshot, so it waits for all of the sample's
            // calls to be whole: a trace row is written from whatever spans have arrived, and the
            // SERVER span of a call can land before the CLIENT root above it.
            traces = await("the sample's " + SAMPLE_CALLS + " calls, each a CLIENT root over its SERVER span",
                    log, app,
                    () -> get(base + "/api/traces?limit=50"),
                    SingleJarIT::sampleCallsComplete);

            // The traces above are whole, so the service is already there; nothing below reads a
            // count out of this answer.
            services = await("sample in /api/services", log, app,
                    () -> get(base + "/api/services"),
                    body -> compact(body).contains("\"name\":\"sample\""));
        } catch (AssertionError e) {
            app.destroyForcibly();
            throw new AssertionError(e.getMessage() + "\n--- application output ---\n" + read(log), e);
        }

        assertThat(compact(status)).contains("\"mode\":\"agent\"");

        List<String> summaries = traceSummaries(traces);
        assertThat(summaries).as("traces in %s", traces).isNotEmpty();
        assertThat(summaries)
                .as("the sample's calls to the UI are its own client spans")
                .anyMatch(t -> t.contains("\"rootService\":\"sample\"") && t.contains("\"rootKind\":\"CLIENT\""));
        assertThat(summaries)
                .as("the UI's own Jetty must not be instrumented (exclude-class-loaders)")
                .noneMatch(t -> t.contains("\"rootKind\":\"SERVER\""));
        assertThat(summaries)
                .as("nor its storage: every insert the UI makes would be a db span of its own, and "
                        + "the sample touches no database at all")
                .allMatch(t -> t.contains("\"dbCount\":0"));

        // And no span anywhere carries the UI's port, which is what a Jetty span inside it would.
        for (String summary : summaries) {
            String trace = get(base + "/api/traces/" + traceId(summary));
            assertThat(compact(trace))
                    .as("no span from the UI's own server in %s", trace)
                    .doesNotContain("\"server.port\":" + port);
        }

        assertThat(compact(services)).contains("\"name\":\"sample\"");

        // Daemon threads only: the sample's main returns and the JVM goes with it. That covers
        // Jetty's pool, the storage writer and H2's own threads.
        assertThat(app.waitFor(60, TimeUnit.SECONDS))
                .as("the application exits by itself; the embedded UI must not hold the JVM open\n%s", read(log))
                .isTrue();
        assertThat(app.exitValue()).as("application output:\n%s", read(log)).isZero();

        String output = read(log);
        assertThat(output).as("the OpenTelemetry agent installed").contains("otel.javaagent");
        assertThat(output)
                .as("the packaged agent reports its own version, not Spider Sense's")
                .contains("2.31.1");
        assertThat(output).contains("[spider-sense] UI: " + base);
    }

    // --- the extension ------------------------------------------------------------------------

    /**
     * The one thing Spider Sense instruments itself, from the packaged jar: the nested
     * {@code extension.jar} is extracted, the agent is pointed at it, and the slow H2 statement the
     * sample runs comes back as a {@code slow-query} finding that names the line it was issued from
     * (design.md, "The extension"; agent.md, "Code locations") and carries the index catalog of the
     * table it read, with the one predicate column no index leads with named as such (agent.md,
     * "The schema block"). The catalog can only be proven here: it needs the agent's advice in the
     * driver's class loader, the JDBC instrumentation's virtual field, and the log exporter.
     */
    @Test
    void aSlowQueryFindingNamesTheCodeThatIssuedItAndTheColumnsNoIndexServes() throws Exception {
        int port = freePort();
        Path log = work.resolve("extension.log");
        String base = "http://127.0.0.1:" + port;

        Process app = start(log,
                javaBinary.toString(),
                "-javaagent:" + senseJar,
                "-Dspidersense.port=" + port,
                "-Dspidersense.db=" + throwawayDatabase(),
                // Four times the 100 ms default, so p95 is over the threshold with room to spare.
                "-Dsample.db.sleep.ms=400",
                "-Dsample.linger.ms=30000",
                "-Dotel.service.name=sample",
                "-cp", sampleClasspath,
                "net.benelog.spidersense.launcher.SampleApp");

        String findings;
        try {
            await("GET /api/status answering in agent mode", log, app,
                    () -> get(base + "/api/status"),
                    body -> compact(body).contains("\"mode\":\"agent\""));

            // The catalog travels as a log record and lands in a later flush than the span; wait for
            // the finding to carry it rather than for the finding alone.
            findings = await("a slow-query finding for the sample's statement, with its schema block",
                    log, app,
                    () -> get(base + "/api/findings?since=15m&format=json"),
                    body -> compact(body).contains("\"kind\":\"slow-query\"")
                            && compact(body).contains("\"unindexed\":[\"items.name\"]"));
        } catch (AssertionError e) {
            app.destroyForcibly();
            throw new AssertionError(e.getMessage() + "\n--- application output ---\n" + read(log), e);
        } finally {
            app.destroy();
            if (!app.waitFor(30, TimeUnit.SECONDS)) {
                app.destroyForcibly();
            }
        }

        String slowQuery = objects(findings, "findings").stream()
                .filter(f -> f.contains("\"kind\":\"slow-query\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slow-query finding in " + findings));

        assertThat(slowQuery).as("the statement the sample ran").contains("sleep(?)");
        assertThat(codeFrames(slowQuery))
                .as("code frames of %s\n--- application output ---\n%s", slowQuery, read(log))
                .isNotEmpty()
                .anyMatch(frame -> frame.contains("SampleApp"));

        // The catalog as H2 reports it: the primary key and the two-column index, the table's name
        // upper-cased by the database and the predicates in the statement's own spelling.
        assertThat(slowQuery).as("the schema block of %s", slowQuery)
                .contains("\"table\":\"ITEMS\"")
                .contains("\"name\":\"IDX_ITEMS_SUPPLIER\",\"unique\":false,\"columns\":[\"SUPPLIER_ID\",\"NAME\"]")
                .contains("\"predicates\":[\"items.name\",\"items.supplier_id\"]")
                .contains("\"unindexed\":[\"items.name\"]");
        assertThat(read(log)).as("the catalog lookup must not reach the application's output")
                .doesNotContainIgnoringCase("IndexCatalog")
                .doesNotContain("NoClassDefFoundError");
    }

    /**
     * The extension's other instrumentation, from the packaged jar: a Spider Silk application is
     * one servlet mapped at {@code /*}, and the route its router matched becomes the span's
     * {@code http.route} and the endpoint's name (design.md, "The extension"). Two ids of one
     * route are one endpoint, and a path no route matched keeps the servlet's mapping.
     */
    @Test
    void aSpiderSilkRequestIsTheEndpointOfTheRouteThatMatchedIt() throws Exception {
        int port = freePort();
        Path log = work.resolve("silk.log");
        String base = "http://127.0.0.1:" + port;

        Process app = start(log,
                javaBinary.toString(),
                "-javaagent:" + senseJar,
                "-Dspidersense.port=" + port,
                "-Dspidersense.db=" + throwawayDatabase(),
                "-Dotel.service.name=silk-sample",
                "-cp", sampleClasspath,
                "net.benelog.spidersense.launcher.SilkSampleApp");

        String endpoints;
        List<String> details = new ArrayList<>();
        try {
            // compact() takes the spaces out, so an endpoint name reads GET/books/{id} below.
            endpoints = await("the sample's three endpoints", log, app,
                    () -> get(base + "/api/endpoints?service=silk-sample"),
                    body -> compact(body).contains("\"name\":\"GET/books/{id}\"")
                            && compact(body).contains("\"name\":\"GET/books\"")
                            && compact(body).contains("\"name\":\"GET/*\""));
            // The UI lives in the sample's JVM, so the traces are read before it goes.
            for (String summary : traceSummaries(get(base + "/api/traces?service=silk-sample&limit=50"))) {
                details.add(compact(get(base + "/api/traces/" + traceId(summary))));
            }
        } catch (AssertionError e) {
            app.destroyForcibly();
            throw new AssertionError(e.getMessage() + "\n--- application output ---\n" + read(log), e);
        } finally {
            app.destroy();
            if (!app.waitFor(30, TimeUnit.SECONDS)) {
                app.destroyForcibly();
            }
        }

        String book = objects(endpoints, "endpoints").stream()
                .filter(e -> e.contains("\"name\":\"GET/books/{id}\""))
                .findFirst()
                .orElseThrow();
        assertThat(book).as("both ids are one endpoint").contains("\"calls\":2")
                .contains("\"route\":\"/books/{id}\"");
        assertThat(compact(endpoints)).as("no endpoint per id").doesNotContain("/books/1");

        // The route is the server span's own attribute, not only the server's reading of its name;
        // each trace's root is the sample's client span, so the server span is looked for inside.
        assertThat(details)
                .as("a server span carrying the route")
                .anyMatch(trace -> trace.contains("\"http.route\":\"/books/{id}\""));
        assertThat(read(log)).doesNotContain("NoClassDefFoundError");
    }

    /** What separates two frames of the compact {@code code} array. */
    private static final Pattern BETWEEN_FRAMES = Pattern.compile("\",\"");

    /** The {@code code} array of one compact finding object. */
    private static List<String> codeFrames(String compactFinding) {
        int start = compactFinding.indexOf("\"code\":[");
        assertThat(start).as("a code array in %s", compactFinding).isNotNegative();
        start += "\"code\":[".length();
        int end = compactFinding.indexOf(']', start);
        String inside = compactFinding.substring(start, end);
        List<String> frames = new ArrayList<>();
        // The limit keeps a trailing empty frame rather than dropping it; the loop skips it either way.
        for (String each : BETWEEN_FRAMES.split(inside, -1)) {
            String frame = each.replace("\"", "").trim();
            if (!frame.isEmpty()) {
                frames.add(frame);
            }
        }
        return frames;
    }

    // --- standalone mode ----------------------------------------------------------------------

    @Test
    void standaloneModeServesTheUiOnItsOwn() throws Exception {
        int port = freePort();
        Path log = work.resolve("standalone.log");
        String base = "http://127.0.0.1:" + port;

        Process server = start(log,
                javaBinary.toString(), "-jar", senseJar.toString(),
                "--port=" + port, "--db=" + throwawayDatabase());
        try {
            String status = await("GET /api/status answering in standalone mode", log, server,
                    () -> get(base + "/api/status"),
                    body -> compact(body).contains("\"mode\":\"standalone\""));
            assertThat(compact(status)).contains("\"mode\":\"standalone\"");
            String banner = read(log);
            assertThat(banner).as("the banner").contains("OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf");
            assertThat(banner).as("the banner names the database").contains("jdbc:h2:mem:it-");
        } finally {
            server.destroy();
            if (!server.waitFor(15, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    // --- the command line ---------------------------------------------------------------------

    /**
     * The CLI of agent.md from the packaged jar: first against the Spider Sense inside the running
     * application, then against the file it left behind.
     *
     * <p>The second half is the promise that matters: the application is gone, its UI with it, and
     * the same commands still answer, because the database is a file and {@code AUTO_SERVER=TRUE}
     * never made it the server's private property.
     */
    @Test
    void theCommandLineAsksTheRunningSpiderSenseAndThenTheFileItLeftBehind() throws Exception {
        int port = freePort();
        Path log = work.resolve("cli-app.log");
        Path database = work.resolve("cli-db/sense");
        String base = "http://127.0.0.1:" + port;

        Process app = start(log,
                javaBinary.toString(),
                "-javaagent:" + senseJar,
                "-Dspidersense.port=" + port,
                "-Dspidersense.db=" + database,
                "-Dsample.linger.ms=60000",
                "-Dotel.service.name=sample",
                "-cp", testClasses,
                "net.benelog.spidersense.launcher.SampleApp");
        try {
            await("GET /api/status answering in agent mode", log, app,
                    () -> get(base + "/api/status"),
                    body -> compact(body).contains("\"mode\":\"agent\""));

            Command mark = cli("mark", "before", "--note=the first run", "--url=" + base);
            assertThat(mark.exit()).as("mark: %s", mark.err()).isZero();
            assertThat(mark.out()).startsWith("mark before at ").contains("the first run");

            await("the sample's " + SAMPLE_CALLS + " calls, each a CLIENT root over its SERVER span",
                    log, app,
                    () -> get(base + "/api/traces?limit=50"),
                    SingleJarIT::sampleCallsComplete);

            Command findings = cli("findings", "--since=before", "--url=" + base);
            assertThat(findings.exit()).as("findings: %s", findings.err()).isZero();
            assertThat(findings.out()).startsWith("# findings  ");

            Command status = cli("status", "--url=" + base);
            assertThat(status.exit()).as("status: %s", status.err()).isZero();
            assertThat(status.out()).startsWith("# status");
            assertThat(status.out()).as("the server it asked is the one in the application").contains("agent");

            Command passed = cli("check", "--max-errors=0", "--url=" + base);
            assertThat(passed.exit()).as("the sample's requests carry no error: %s", passed.out()).isZero();
            assertThat(passed.out()).startsWith("# check  pass");

            Command failed = cli("check", "--max-p95-ms=0", "--url=" + base);
            assertThat(failed.exit()).as("no request is faster than nothing: %s", failed.out()).isEqualTo(1);
            assertThat(failed.out()).startsWith("# check  fail");

            Command nonsense = cli("nonsense", "--url=" + base);
            assertThat(nonsense.exit()).isEqualTo(2);
            assertThat(nonsense.err()).contains("unknown command: nonsense");
        } finally {
            app.destroy();
            if (!app.waitFor(30, TimeUnit.SECONDS)) {
                app.destroyForcibly();
            }
        }

        // Nothing is listening on that port any more, and --db says so without asking.
        Command file = cli("status", "--db=" + database);
        assertThat(file.exit()).as("status from the file: %s", file.err()).isZero();
        assertThat(file.out()).startsWith("# status");
        assertThat(file.out()).as("no server answered, so the mode is the file").contains("file");
        assertThat(file.err()).isEmpty();

        Command marks = cli("marks", "--db=" + database);
        assertThat(marks.exit()).isZero();
        assertThat(marks.out()).as("the mark written through the running server").contains("before");

        Command findings = cli("findings", "--since=1h", "--db=" + database);
        assertThat(findings.exit()).as("findings from the file: %s", findings.err()).isZero();
        assertThat(findings.out()).startsWith("# findings  ");
    }

    /**
     * {@code init} from the packaged jar, which is the only way to see the part that cannot be
     * unit-tested: the launcher leaving its own path in {@code spidersense.jar}, so the block
     * names the jar the user actually typed rather than the nested one in the temporary directory.
     */
    @Test
    void initWritesTheBlockNamingThePackagedJarAndInstallsTheSkill() throws Exception {
        Path project = work.resolve("init-project");
        Files.createDirectories(project);

        Command init = cli("init", "--dir=" + project);
        assertThat(init.exit()).as("init: %s", init.err()).isZero();
        assertThat(init.out()).startsWith("wrote CLAUDE.md block (jar: ");
        assertThat(init.out()).contains("installed skill to " + project.resolve(".claude/skills/spider-sense"));

        String claude = Files.readString(project.resolve("CLAUDE.md"), UTF_8);
        assertThat(claude).startsWith("<!-- spider-sense:start -->\n## Spider Sense\n");
        assertThat(claude).endsWith("<!-- spider-sense:end -->\n");
        assertThat(claude)
                .as("the block names the jar this JVM was started from, not the nested server jar")
                .contains("-javaagent:" + senseJar.toAbsolutePath())
                .contains("java -jar " + senseJar.toAbsolutePath() + " findings --since=start");

        assertThat(project.resolve(".claude/skills/spider-sense/SKILL.md")).isRegularFile();
        assertThat(project.resolve(".claude/skills/spider-sense/references/cli.md")).isRegularFile();
    }

    /** One run of {@code java -jar spider-sense.jar <command>}, with its streams kept apart. */
    private record Command(int exit, String out, String err) {
    }

    private int commands;

    private Command cli(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaBinary.toString(), "-jar", senseJar.toString()));
        command.addAll(List.of(args));
        int run = ++commands;
        Path out = work.resolve("cli-" + run + ".out");
        Path err = work.resolve("cli-" + run + ".err");
        Process process = new ProcessBuilder(command)
                .directory(work.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS))
                .as("the command finishes: %s", String.join(" ", command)).isTrue();
        return new Command(process.exitValue(), read(out), read(err));
    }

    // --- plumbing -----------------------------------------------------------------------------

    private Process start(Path log, String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        pb.directory(work.toFile());
        return pb.start();
    }

    /**
     * Polls until the answer satisfies the predicate. The deadline is generous, but the loop also
     * gives up as soon as the process is gone: an application that already exited will never answer.
     */
    private static String await(String what, Path log, Process process,
                                Supplier<String> call, Predicate<String> ok) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        String last = null;
        while (System.nanoTime() < deadline) {
            last = call.get();
            if (last != null && ok.test(last)) {
                return last;
            }
            if (!process.isAlive()) {
                break;
            }
            sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what
                + " (alive=" + process.isAlive() + ", last answer: " + last + ")"
                + "\n--- output ---\n" + read(log));
    }

    private static String get(String url) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(UTF_8));
            return response.statusCode() == 200 ? response.body() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /**
     * Whether every call SampleApp makes to its own {@code /hello} has arrived whole: a trace whose
     * root is the sample's CLIENT span with the SERVER span under it.
     */
    private static boolean sampleCallsComplete(String body) {
        long whole = traceSummaries(body).stream()
                .filter(t -> t.contains("\"rootService\":\"sample\"")
                        && t.contains("\"rootKind\":\"CLIENT\"")
                        && t.contains("\"spanCount\":2,"))
                .count();
        return whole >= SAMPLE_CALLS;
    }

    /** The objects of the {@code traces} array, each as a compact string. */
    private static List<String> traceSummaries(String body) {
        return objects(body, "traces");
    }

    /** The objects of one top-level JSON array, each as a compact string. */
    private static List<String> objects(String body, String key) {
        String compact = compact(body);
        int start = compact.indexOf("\"" + key + "\":[");
        if (start < 0) {
            return List.of();
        }
        start += ("\"" + key + "\":[").length();
        int depth = 0;
        int from = -1;
        List<String> out = new ArrayList<>();
        for (int i = start; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    from = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    out.add(compact.substring(from, i + 1));
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static String traceId(String compactSummary) {
        int at = compactSummary.indexOf("\"traceId\":\"") + "\"traceId\":\"".length();
        return compactSummary.substring(at, compactSummary.indexOf('"', at));
    }

    /** Whitespace out, so the assertions do not depend on how the server formats its JSON. */
    private static String compact(String json) {
        return json == null ? "" : json.replaceAll("\\s+", "");
    }

    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "(no output)";
        } catch (IOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /** An in-memory database of its own per case, so a test run never writes under ~/db. */
    private static String throwawayDatabase() {
        return "jdbc:h2:mem:it-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
