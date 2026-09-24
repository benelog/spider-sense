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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The index catalog and the catalog-query suppression of the extension on PostgreSQL and MySQL
 * (design.adoc#extension).
 *
 * <p>SingleJarIT proves the catalog on H2, which stores unquoted names upper-cased and answers the
 * metadata calls without a statement the JDBC instrumentation sees. Here the same slow statement
 * runs against PostgreSQL, which stores them lower-cased, and MySQL, which keeps the table's case,
 * each in a container; the finding's schema block must name the table and its indexes the way the
 * database spells them, and no span of any trace may be the driver's catalog query.
 *
 * <p>Tagged {@code integration}, like SingleJarIT, and skipped rather than failed where there is no
 * Docker to start the containers in.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseCatalogIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    /**
     * What the drivers' catalog queries read, and what no statement of the application does; with
     * the whitespace taken out, as the traces are compared.
     */
    private static final List<String> CATALOG_MARKERS = List.of(
            "pg_catalog", "pg_class", "pg_index", "information_schema", "showindex", "showkeys",
            "showfulltables", "showtables");

    private static Path senseJar;
    private static String sampleClasspath;
    private static Path javaBinary;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @TempDir
    Path work;

    @BeforeAll
    static void locateEverything() {
        senseJar = Path.of(required("spidersense.it.jar"));
        sampleClasspath = required("spidersense.it.sampleClasspath");
        javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        assertThat(senseJar).as("the packaged jar").isRegularFile();
    }

    private static String required(String property) {
        String v = System.getProperty(property);
        assertThat(v).as(property + " must be set by the integrationTest task").isNotNull();
        return v;
    }

    /**
     * PostgreSQL folds the unquoted {@code Items} to {@code items}, and names the primary key's
     * index after the table; the lookup finds the table only because it folds the name the same way.
     */
    @Test
    void postgresqlNamesTheTableAndItsIndexesLowerCased() throws Exception {
        String finding = slowQueryFinding(POSTGRES, "postgresql");

        assertThat(finding).as("the schema block of %s", finding)
                .contains("\"table\":\"items\"")
                .contains("\"schema\":\"public\"")
                .contains("\"name\":\"items_pkey\",\"unique\":true,\"columns\":[\"id\"]")
                .contains("\"name\":\"idx_items_supplier\",\"unique\":false,\"columns\":[\"supplier_id\",\"name\"]")
                .contains("\"unindexed\":[\"items.name\"]");
    }

    /**
     * MySQL on Linux keeps the table's case ({@code lower_case_table_names=0}) and the index's as
     * written, calls the primary key {@code PRIMARY}, and has no schemas beside its catalogs.
     */
    @Test
    void mysqlNamesTheTableAndItsIndexesAsWritten() throws Exception {
        String finding = slowQueryFinding(MYSQL, "mysql");

        assertThat(finding).as("the schema block of %s", finding)
                .contains("\"table\":\"Items\"")
                .contains("\"name\":\"PRIMARY\",\"unique\":true,\"columns\":[\"id\"]")
                .contains("\"name\":\"Idx_Items_Supplier\",\"unique\":false,\"columns\":[\"supplier_id\",\"name\"]")
                .contains("\"unindexed\":[\"items.name\"]");
    }

    /**
     * Runs DatabaseSampleApp under the agent against the container, waits for the slow-query finding
     * to carry its schema block, checks that the catalog queries left no span, and returns the
     * finding.
     */
    private String slowQueryFinding(JdbcDatabaseContainer<?> database, String name) throws Exception {
        int port = freePort();
        Path log = work.resolve(name + ".log");
        String base = "http://127.0.0.1:" + port;

        Process app = start(log,
                javaBinary.toString(),
                "-javaagent:" + senseJar,
                "-Dspidersense.port=" + port,
                "-Dspidersense.db=" + throwawayDatabase(),
                "-Dsample.jdbc.url=" + database.getJdbcUrl(),
                "-Dsample.jdbc.user=" + database.getUsername(),
                "-Dsample.jdbc.password=" + database.getPassword(),
                "-Dsample.db.sleep.ms=400",
                "-Dsample.linger.ms=30000",
                "-Dotel.service.name=sample-" + name,
                "-cp", sampleClasspath,
                "net.benelog.spidersense.launcher.DatabaseSampleApp");

        String findings;
        List<String> traces = new ArrayList<>();
        List<String> summaries;
        try {
            await("GET /api/status answering in agent mode", log, app,
                    () -> get(base + "/api/status"),
                    body -> compact(body).contains("\"mode\":\"agent\""));

            // The catalog travels as a log record and lands in a later flush than the span.
            findings = await("a slow-query finding with its schema block", log, app,
                    () -> get(base + "/api/findings?since=15m&format=json"),
                    body -> compact(body).contains("\"kind\":\"slow-query\"")
                            && compact(body).contains("\"unindexed\":["));

            summaries = objects(await("the request's trace", log, app,
                    () -> get(base + "/api/traces?limit=200"),
                    body -> compact(body).contains("\"rootService\":\"sample-" + name + "\"")), "traces");
            for (String summary : summaries) {
                traces.add(get(base + "/api/traces/" + traceId(summary)));
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

        String slowQuery = objects(findings, "findings").stream()
                .filter(f -> f.contains("\"kind\":\"slow-query\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slow-query finding in " + findings));
        assertThat(slowQuery).as("the statement the sample ran").contains("sleep(?)");
        assertThat(slowQuery).as("the predicates, lower-cased whatever the statement wrote")
                .contains("\"predicates\":[\"items.name\",\"items.supplier_id\"]");

        // The request's trace: the client call, the server span and the one statement, nothing of
        // the lookup that statement set off on the same thread and the same connection.
        int request = -1;
        for (int i = 0; i < traces.size(); i++) {
            if (compact(traces.get(i)).contains("sleep(?)")) {
                assertThat(request).as("one trace holds the slow statement").isNegative();
                request = i;
            }
        }
        assertThat(request).as("a trace holding the slow statement among %s", summaries).isNotNegative();
        assertThat(summaries.get(request)).as("the request's trace has one database span")
                .contains("\"dbCount\":1");

        for (String trace : traces) {
            String lower = compact(trace).toLowerCase(Locale.ROOT);
            for (String marker : CATALOG_MARKERS) {
                assertThat(lower).as("no span is the driver's catalog query (%s) in %s", marker, trace)
                        .doesNotContain(marker);
            }
        }
        assertThat(read(log)).as("the catalog lookup must not reach the application's output")
                .doesNotContainIgnoringCase("IndexCatalog")
                .doesNotContain("NoClassDefFoundError")
                .contains("-> 200");
        return slowQuery;
    }

    // --- plumbing, as in SingleJarIT ------------------------------------------------------------

    private Process start(Path log, String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        pb.directory(work.toFile());
        return pb.start();
    }

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
