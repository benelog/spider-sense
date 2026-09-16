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
    private static Path javaBinary;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @TempDir
    Path work;

    @BeforeAll
    static void locateEverything() {
        senseJar = Path.of(required("spidersense.it.jar"));
        testClasses = required("spidersense.it.testClasses");
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

            traces = await("a trace whose root service is sample", log, app,
                    () -> get(base + "/api/traces?limit=50"),
                    body -> compact(body).contains("\"rootService\":\"sample\""));

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

    /** The objects of the {@code traces} array, each as a compact string. */
    private static List<String> traceSummaries(String body) {
        String compact = compact(body);
        int start = compact.indexOf("\"traces\":[");
        if (start < 0) {
            return List.of();
        }
        start += "\"traces\":[".length();
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
