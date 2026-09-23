package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.json.Json;

/**
 * The command line of agent.md: what each command asks for, what it prints, and
 * the exit code it leaves behind.
 *
 * <p>Both ways of answering are exercised against the same commands — a server on
 * a port of its own, and an in-memory database with no server at all — because
 * the promise of the CLI is that the second is as good as the first.
 */
class CliTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String FAILING = "4bf92f3577b34da6a3ce929d0e0e4737";

    private record Run(int exit, String out, String err) {
    }

    /** One command, with the streams captured and a default URL of the test's choosing. */
    private static Run runAt(String defaultUrl, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(args, defaultUrl, toOut, toErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    /** A command that must not need the network: the default URL is one nothing listens on. */
    private static Run run(String... args) {
        return runAt(closedUrl(), args);
    }

    private static String closedUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A server on a port of its own, with the sample already stored. */
    private static void serve(boolean withData, BiConsumer<SpiderSenseServer, String> body) {
        Config config = TestStore.config();
        SpiderSenseServer server = SpiderSenseServer.start(config);
        try {
            if (withData) {
                new OtlpDecoder(server.store(), server::port).accept(sample());
                server.store().writer().awaitIdle(5_000);
            }
            body.accept(server, "http://127.0.0.1:" + server.port());
        } finally {
            server.stop();
        }
    }

    /** A slow endpoint whose trace repeats a statement, and a second request that failed. */
    private static io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest sample() {
        Span.Builder root = Otlp.span(TRACE, "00f067aa0ba902b7", "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(Otlp.child(root, "%016x".formatted(100 + i), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        spans.add(Otlp.failing(
                Otlp.span(FAILING, "00f067aa0ba902c1", "POST /orders/{id}/ship",
                        Span.SpanKind.SPAN_KIND_SERVER, NOW + 100, 20,
                        Otlp.attr("http.request.method", "POST"),
                        Otlp.attr("http.route", "/orders/{id}/ship"),
                        Otlp.attr("http.response.status_code", 500)),
                "java.lang.IllegalStateException", "Order 42 is already shipped",
                "java.lang.IllegalStateException: Order 42 is already shipped\n"
                        + "\tat orders.OrderService.ship(OrderService.java:41)"));
        return Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0]));
    }

    // --- parsing -------------------------------------------------------------

    @Test
    void theDefaultUrlIsWhatTheSpidersensePropertiesImply() {
        java.util.Properties none = new java.util.Properties();
        assertThat(Cli.configuredUrl(none)).isEqualTo("http://127.0.0.1:4000");

        java.util.Properties port = new java.util.Properties();
        port.setProperty("spidersense.port", "4001");
        assertThat(Cli.configuredUrl(port)).isEqualTo("http://127.0.0.1:4001");

        java.util.Properties everywhere = new java.util.Properties();
        everywhere.setProperty("spidersense.host", "0.0.0.0");
        everywhere.setProperty("spidersense.port", "4002");
        assertThat(Cli.configuredUrl(everywhere))
                .as("0.0.0.0 is a bind address, not one to connect to")
                .isEqualTo("http://127.0.0.1:4002");

        java.util.Properties collector = new java.util.Properties();
        collector.setProperty("spidersense.collector", "http://elsewhere:4000/");
        collector.setProperty("spidersense.port", "4002");
        assertThat(Cli.configuredUrl(collector))
                .as("an application that forwards sends there, so that is what to ask")
                .isEqualTo("http://elsewhere:4000");
    }

    @Test
    void helpIsTheTableAndEveryUsageErrorPrintsItOnStderr() {
        Run help = run("help");
        assertThat(help.exit()).isZero();
        assertThat(help.out()).contains("Commands:").contains("compare --before=");
        assertThat(help.err()).isEmpty();

        Run unknown = run("nonsense");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.out()).isEmpty();
        assertThat(unknown.err()).startsWith("spider-sense: unknown command: nonsense");
        assertThat(unknown.err()).contains("Commands:");

        assertThat(run("findings", "--sinse=5m").err())
                .startsWith("spider-sense: unknown option for findings: --sinse");
        assertThat(run("findings", "--sinse=5m").exit()).isEqualTo(2);
        assertThat(run("trace").err()).startsWith("spider-sense: trace needs a trace id");
        assertThat(run("trace").exit()).isEqualTo(2);
        assertThat(run("mark").err()).startsWith("spider-sense: mark needs a mark name");
        assertThat(run("compare", "--before=5m").err())
                .startsWith("spider-sense: compare needs both --before and --after");
        assertThat(run("--json").exit()).isEqualTo(2);
        assertThat(run("marks", "--limit=many").exit()).isEqualTo(2);
    }

    @Test
    void everyCommandAsksTheUrlItsSectionOfApiMdNames() {
        assertThat(path("status")).isEqualTo("/api/status?format=text");
        assertThat(path("findings", "--since=before", "--service=orders", "--limit=5"))
                .isEqualTo("/api/findings?since=before&service=orders&limit=5&format=text");
        assertThat(path("findings", "--json")).isEqualTo("/api/findings?since=15m&limit=20&format=json");
        assertThat(path("findings", "--hide-acked"))
                .isEqualTo("/api/findings?since=15m&limit=20&hideAcked=true&format=text");
        assertThat(path("findings", "--no-git")).as("the suspect change is the CLI's own")
                .isEqualTo("/api/findings?since=15m&limit=20&format=text");
        assertThat(path("ack", "slow-endpoint:1a2b3c4d5e6f", "--note=known"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/ack?format=text");
        assertThat(path("unack", "slow-endpoint:1a2b3c4d5e6f"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/ack?format=text");
        assertThat(path("resolve", "slow-endpoint:1a2b3c4d5e6f", "--note=index"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/resolve?format=text");
        assertThat(path("unresolve", "slow-endpoint:1a2b3c4d5e6f"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/resolve?format=text");
        assertThat(path("trace", TRACE, "--full"))
                .isEqualTo("/api/traces/" + TRACE + "?full=true&format=text");
        assertThat(path("traces", "--status=error", "--min-ms=100", "--q=orders"))
                .isEqualTo("/api/traces?since=15m&status=error&minMs=100&q=orders&limit=20&format=text");
        assertThat(path("endpoints", "--until=now")).isEqualTo("/api/endpoints?since=15m&until=now&format=text");
        assertThat(path("queries")).isEqualTo("/api/queries?since=15m&limit=100&format=text");
        assertThat(path("errors")).isEqualTo("/api/errors?since=15m&limit=100&format=text");
        assertThat(path("logs", "--severity=WARN", "--trace=" + TRACE))
                .isEqualTo("/api/logs?since=15m&severity=WARN&traceId=" + TRACE + "&limit=200&format=text");
        assertThat(path("mark", "before", "--note=the slow one")).isEqualTo("/api/marks?format=text");
        assertThat(path("marks", "--limit=3")).isEqualTo("/api/marks?limit=3&format=text");
        assertThat(path("compare", "--before=before", "--after=after", "--until=now"))
                .isEqualTo("/api/compare?before=before&after=after&until=now&format=text");
        assertThat(path("check", "--max-p95-ms=500", "--max-n-plus-one=0", "--endpoint=GET /orders"))
                .isEqualTo("/api/check?since=15m&endpoint=GET%20%2Forders&maxP95Ms=500&maxNPlusOne=0&format=text");
        assertThat(path("check", "--min-apdex=0.9", "--max-error-rate=0.01"))
                .isEqualTo("/api/check?since=15m&maxErrorRate=0.01&minApdex=0.9&format=text");
        assertThat(path("sql", "SELECT 1")).as("the statement travels in the body")
                .isEqualTo("/api/sql?format=text");
        assertThat(path("sql", "SELECT 1", "--json", "--limit=10"))
                .isEqualTo("/api/sql?format=json");
    }

    private static String path(String... args) {
        return Remote.path(Options.parse(args));
    }

    // --- over HTTP -----------------------------------------------------------

    @Test
    void aRunningSpiderSenseAnswersEveryCommandAndTheCliPrintsItVerbatim() {
        serve(true, (server, base) -> {
            assertThat(runAt(base, "status", "--url=" + base).out()).startsWith("# status");
            assertThat(runAt(base, "findings", "--url=" + base).out()).startsWith("# findings  ");
            assertThat(runAt(base, "findings", "--no-git", "--url=" + base).out())
                    .startsWith("# findings  ");
            assertThat(runAt(base, "traces", "--url=" + base).out()).startsWith("# traces  ");
            assertThat(runAt(base, "endpoints", "--url=" + base).out()).startsWith("# endpoints  ");
            assertThat(runAt(base, "queries", "--url=" + base).out()).startsWith("# queries  ");
            assertThat(runAt(base, "errors", "--url=" + base).out()).startsWith("# errors  ");
            assertThat(runAt(base, "logs", "--url=" + base).out()).startsWith("# logs  ");

            Run trace = runAt(base, "trace", TRACE, "--url=" + base);
            assertThat(trace.exit()).isZero();
            assertThat(trace.out()).startsWith("# trace " + TRACE + "  ");
            assertThat(trace.out()).contains("db SELECT order_line  × 6");

            Run json = runAt(base, "findings", "--json", "--url=" + base);
            assertThat(json.exit()).isZero();
            assertThat(Json.parse(json.out()).asObject().getArray("findings").size()).isPositive();
        });
    }

    @Test
    void theExitCodeOfCheckIsTheVerdict() {
        serve(false, (server, base) -> {
            Run nothing = runAt(base, "check", "--url=" + base);
            assertThat(nothing.exit()).as("no request to judge").isEqualTo(3);
            assertThat(nothing.out()).startsWith("# check  ");
        });
        serve(true, (server, base) -> {
            assertThat(runAt(base, "check", "--url=" + base).exit()).as("the defaults").isEqualTo(1);
            assertThat(runAt(base, "check", "--max-errors=0", "--url=" + base).exit())
                    .as("one request failed").isEqualTo(1);
            Run passed = runAt(base, "check", "--max-p95-ms=100000", "--url=" + base);
            assertThat(passed.exit()).isZero();
            assertThat(passed.out()).startsWith("# check  pass");
        });
    }

    @Test
    void whatIsNotThereIsFourAndWhatIsNotASelectorIsTwo() {
        serve(true, (server, base) -> {
            Run missing = runAt(base, "trace", "f".repeat(32), "--url=" + base);
            assertThat(missing.exit()).isEqualTo(4);
            assertThat(missing.err()).contains("f".repeat(32));
            assertThat(missing.out()).isEmpty();

            Run noMark = runAt(base, "findings", "--since=nowhere", "--url=" + base);
            assertThat(noMark.exit()).isEqualTo(4);
            assertThat(noMark.err()).contains("No mark named nowhere");

            Run bad = runAt(base, "findings", "--since=5 minutes", "--url=" + base);
            assertThat(bad.exit()).isEqualTo(2);
            assertThat(bad.err()).contains("Not a time selector");
        });
    }

    @Test
    void aMarkIsRecordedOverHttpAndListedAgain() {
        serve(false, (server, base) -> {
            Run mark = runAt(base, "mark", "before", "--note=the slow version", "--url=" + base);
            assertThat(mark.exit()).isZero();
            assertThat(mark.out()).startsWith("mark before at ").contains("the slow version");

            assertThat(runAt(base, "marks", "--url=" + base).out()).contains("before");
            assertThat(runAt(base, "mark", "two words", "--url=" + base).exit()).isEqualTo(2);
        });
    }

    /** The same pair over HTTP, where the id travels in the path and has a colon in it. */
    @Test
    void aFindingIsAcknowledgedOverHttpAndWithdrawnAgain() {
        serve(true, (server, base) -> {
            String id = Json.parse(runAt(base, "findings", "--json", "--url=" + base).out())
                    .asObject().getArray("findings").get(0).asObject().getString("id");

            Run acked = runAt(base, "ack", id, "--note=known", "--url=" + base);
            assertThat(acked.exit()).isZero();
            assertThat(acked.out()).isEqualTo("acked " + id + " — known\n");

            assertThat(runAt(base, "findings", "--url=" + base).out()).contains("1 acked)");

            Run withdrawn = runAt(base, "unack", id, "--url=" + base);
            assertThat(withdrawn.exit()).isZero();
            assertThat(withdrawn.out()).isEqualTo("unacked " + id + "\n");

            Run twice = runAt(base, "unack", id, "--url=" + base);
            assertThat(twice.exit()).isEqualTo(4);
            assertThat(twice.err()).contains("No such acknowledgement: " + id);
        });
    }

    /** {@code resolve} and {@code unresolve}, over HTTP as {@code ack} and {@code unack} are. */
    @Test
    void aFindingIsResolvedOverHttpAndWithdrawnAgain() {
        serve(true, (server, base) -> {
            String id = Json.parse(runAt(base, "findings", "--json", "--url=" + base).out())
                    .asObject().getArray("findings").get(0).asObject().getString("id");

            Run resolved = runAt(base, "resolve", id, "--note=fixed", "--url=" + base);
            assertThat(resolved.exit()).isZero();
            assertThat(resolved.out()).isEqualTo("resolved " + id + " — fixed\n");

            assertThat(runAt(base, "findings", "--url=" + base).out()).contains("1 resolved)");

            Run withdrawn = runAt(base, "unresolve", id, "--url=" + base);
            assertThat(withdrawn.exit()).isZero();
            assertThat(withdrawn.out()).isEqualTo("unresolved " + id + "\n");

            Run twice = runAt(base, "unresolve", id, "--url=" + base);
            assertThat(twice.exit()).isEqualTo(4);
            assertThat(twice.err()).contains("No such resolution: " + id);
        });
    }

    @Test
    void anExplicitUrlThatAnswersNothingIsAConnectionErrorAndNeverTheFile() {
        String closed = closedUrl();
        Run run = runAt("http://127.0.0.1:1", "status", "--url=" + closed);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).startsWith("spider-sense: no Spider Sense at " + closed + " (");
        assertThat(run.out()).isEmpty();
    }

    // --- from the file -------------------------------------------------------

    @Test
    void theDatabaseAnswersEveryCommandWithNoServerRunning() {
        String db = "--db=" + TestStore.memoryUrl();

        Run status = run("status", db);
        assertThat(status.exit()).isZero();
        assertThat(status.out()).startsWith("# status").contains("mode").contains("file");

        Run mark = run("mark", "before", "--note=the slow version", db);
        assertThat(mark.exit()).isZero();
        assertThat(mark.out()).startsWith("mark before at ");

        Run marks = run("marks", db);
        assertThat(marks.exit()).isZero();
        assertThat(marks.out()).startsWith("# marks").contains("before").contains("the slow version");

        assertThat(run("findings", db).out()).startsWith("# findings  ");
        assertThat(run("findings", "--since=before", db).exit()).isZero();
        assertThat(run("endpoints", db).out()).startsWith("# endpoints  ");
        assertThat(run("queries", db).out()).startsWith("# queries  ");
        assertThat(run("errors", db).out()).startsWith("# errors  ");
        assertThat(run("logs", db).out()).startsWith("# logs  ");
        assertThat(run("traces", db).out()).startsWith("# traces  ");
        assertThat(run("compare", "--before=10m", "--after=5m", db).out()).startsWith("# compare  ");

        Run check = run("check", db);
        assertThat(check.exit()).as("nothing was requested").isEqualTo(3);

        Run missing = run("trace", "f".repeat(32), db);
        assertThat(missing.exit()).isEqualTo(4);
        assertThat(missing.err()).contains("No such trace");

        Run json = run("marks", "--json", db);
        assertThat(Json.parse(json.out()).asObject().getArray("marks")).hasSize(1);

        assertThat(run("findings", "--since=nowhere", db).exit()).isEqualTo(4);
        assertThat(run("mark", "two words", db).exit()).isEqualTo(2);
    }

    /**
     * Acknowledging from the file, which is where an agent does it: the loop runs
     * against a crashed application as readily as against a live one (agent.md).
     */
    @Test
    void ackAndUnackAreWrittenToTheFileAndShowUpInFindings() {
        String url = TestStore.memoryUrl();
        String db = "--db=" + url;
        try (Store store = new Store(url, null, 24, 500, 100, null)) {
            new OtlpDecoder(store, () -> 4000).accept(sample());
            store.writer().awaitIdle(5_000);
        }

        String id = Json.parse(run("findings", "--json", db).out()).asObject()
                .getArray("findings").get(0).asObject().getString("id");

        Run acked = run("ack", id, "--note=slow by design", db);
        assertThat(acked.exit()).isZero();
        assertThat(acked.out()).isEqualTo("acked " + id + " — slow by design\n");

        Run listed = run("findings", db);
        assertThat(listed.exit()).isZero();
        assertThat(listed.out()).contains("1 acked)");
        assertThat(listed.out()).contains("| acked | ");
        assertThat(run("findings", "--hide-acked", db).out()).doesNotContain(id);

        Run withdrawn = run("unack", id, db);
        assertThat(withdrawn.exit()).isZero();
        assertThat(withdrawn.out()).isEqualTo("unacked " + id + "\n");

        Run twice = run("unack", id, db);
        assertThat(twice.exit()).isEqualTo(4);
        assertThat(twice.err()).contains("No such acknowledgement: " + id);
        assertThat(twice.out()).isEmpty();

        assertThat(run("unack", db).exit()).as("the id is the argument").isEqualTo(2);

        Run resolved = run("resolve", id, "--note=fetch join", db);
        assertThat(resolved.exit()).isZero();
        assertThat(resolved.out()).isEqualTo("resolved " + id + " — fetch join\n");
        assertThat(run("findings", db).out()).contains("1 resolved)").contains("| resolved | ");
        assertThat(run("findings", "--hide-acked", db).out()).doesNotContain(id);
        assertThat(run("unresolve", id, db).out()).isEqualTo("unresolved " + id + "\n");
        Run none = run("unresolve", id, db);
        assertThat(none.exit()).isEqualTo(4);
        assertThat(none.err()).contains("No such resolution: " + id);
    }

    @Test
    void sqlIsAnsweredOverHttpAndRefusedWhenItIsNotARead() {
        serve(true, (server, base) -> {
            Run rows = runAt(base, "sql",
                    "SELECT service, COUNT(*) AS spans FROM span GROUP BY service", "--url=" + base);
            assertThat(rows.exit()).isZero();
            assertThat(rows.out()).startsWith("# sql  1 rows");
            assertThat(rows.out()).contains("| SERVICE | SPANS |").contains("| orders |");

            Run json = runAt(base, "sql", "SELECT COUNT(*) AS spans FROM span", "--json",
                    "--url=" + base);
            assertThat(json.exit()).isZero();
            assertThat(Json.parse(json.out()).asObject().getLong("rowCount")).isEqualTo(1);

            Run capped = runAt(base, "sql", "SELECT id FROM span ORDER BY id", "--limit=2",
                    "--url=" + base);
            assertThat(capped.out()).startsWith("# sql  2 rows (truncated at 2)");

            Run refused = runAt(base, "sql", "DELETE FROM span", "--url=" + base);
            assertThat(refused.exit()).isEqualTo(2);
            assertThat(refused.out()).isEmpty();
            assertThat(refused.err()).as("the server's own line, not a status code")
                    .isEqualTo("spider-sense: only EXPLAIN, SELECT, SHOW, TABLE, VALUES, WITH"
                            + " may be run here, and this statement starts with DELETE\n");
        });
    }

    /**
     * The direct-file path answers too — once a server of this version has created
     * the reader user. A database that predates it says so rather than answering.
     */
    @Test
    void sqlFromTheFileNeedsTheReaderUserAServerCreates() {
        String url = TestStore.memoryUrl();

        Run tooEarly = run("sql", "SELECT 1", "--db=" + url);
        assertThat(tooEarly.exit()).isEqualTo(2);
        assertThat(tooEarly.err())
                .contains("the database has no read-only user yet")
                .contains("start an application or the standalone server with this version first");
        assertThat(tooEarly.out()).isEmpty();

        try (Database opened = Database.open(url, null)) {
            opened.sql().update("INSERT INTO mark (at_ms, name) VALUES (1, 'before')", List.of());

            Run rows = run("sql", "SELECT name, at_ms FROM mark ORDER BY at_ms", "--db=" + url);
            assertThat(rows.exit()).isZero();
            assertThat(rows.out()).startsWith("# sql  1 rows");
            assertThat(rows.out()).contains("| NAME | AT_MS |").contains("| before | 1 |");

            Run refused = run("sql", "DROP TABLE mark", "--db=" + url);
            assertThat(refused.exit()).isEqualTo(2);
            assertThat(run("sql", "SELECT COUNT(*) AS marks FROM mark", "--db=" + url).out())
                    .contains("| 1 |");
        }
    }

    /**
     * The one line agent.md prints when it falls back, and the reason it exists:
     * an agent must never mistake a file for a live server.
     */
    @Test
    void nothingListeningAndNoUrlFallsBackToTheFileAndSaysSoOnStderr() {
        String memory = TestStore.memoryUrl();
        System.setProperty("spidersense.db", memory);
        try {
            String closed = closedUrl();
            Run run = runAt(closed, "status");
            assertThat(run.exit()).isZero();
            assertThat(run.err())
                    .isEqualTo("(no Spider Sense at " + closed + "; reading " + memory + " directly)\n");
            assertThat(run.out()).startsWith("# status");
        } finally {
            System.clearProperty("spidersense.db");
        }
    }

    /**
     * The files of each packaged skill, keyed by the skill's directory: what the build's
     * {@code skillIndex} task wrote beside them, read here rather than pinned, so that a
     * new reference page — or a whole new skill under {@code skills/} — does not break
     * this test. The order is the order {@code init} prints, which is by skill name
     * (agent.md, "init").
     */
    private static Map<String, Long> packagedSkills() throws IOException {
        String index = new String(Objects.requireNonNull(
                CliTest.class.getResourceAsStream("/spider-sense/skills/index.txt"), "skill index")
                .readAllBytes(), UTF_8);
        return index.lines()
                .map(String::trim)
                .filter(line -> line.contains("/"))
                .collect(java.util.stream.Collectors.groupingBy(
                        line -> line.substring(0, line.indexOf('/')),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
    }

    /**
     * {@code init} is the one command that asks nothing and only writes: the block into
     * the project's CLAUDE.md, and the skills beside it (agent.md, "init").
     */
    @Test
    void initWritesTheBlockAndInstallsEverySkill(@TempDir Path project) throws IOException {
        Run init = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");
        assertThat(init.exit()).as("stderr: %s", init.err()).isZero();

        Map<String, Long> skills = packagedSkills();
        assertThat(skills.keySet())
                .as("every directory under skills/ travels in the jar")
                .contains("spider-sense", "spider-sense-sql-tuning");
        StringBuilder expected = new StringBuilder("wrote CLAUDE.md block (jar: /x/spider-sense.jar)\n");
        skills.forEach((skill, files) -> expected
                .append("installed skill to ").append(project.resolve(".claude/skills").resolve(skill))
                .append(" (").append(files).append(" files)\n"));
        assertThat(init.out()).isEqualTo(expected.toString());

        String claude = Files.readString(project.resolve("CLAUDE.md"), UTF_8);
        assertThat(claude).startsWith("<!-- spider-sense:start -->\n## Spider Sense\n");
        assertThat(claude).endsWith("<!-- spider-sense:end -->\n");
        assertThat(claude)
                .contains("java -javaagent:/x/spider-sense.jar -jar <app jar>")
                .contains("JAVA_TOOL_OPTIONS=\"-javaagent:/x/spider-sense.jar\" ./gradlew bootRun")
                .contains("java -jar /x/spider-sense.jar findings --since=start")
                .contains("<http://127.0.0.1:4000>")
                .contains("The loop — start, mark, exercise, findings, fix, compare, check — is in the "
                        + "skill at `.claude/skills/spider-sense/SKILL.md`.")
                .contains("Query tuning — an index to add, a rewrite, a fetch join, a batch — is in the "
                        + "skill at `.claude/skills/spider-sense-sql-tuning/SKILL.md`.");

        assertThat(project.resolve(".claude/skills/spider-sense/SKILL.md")).isRegularFile();
        assertThat(project.resolve(".claude/skills/spider-sense/references/cli.md")).isRegularFile();
        assertThat(project.resolve(".claude/skills/spider-sense-sql-tuning/SKILL.md")).isRegularFile();
        assertThat(Files.readString(project.resolve(".claude/skills/spider-sense/SKILL.md"), UTF_8))
                .as("the repository's skill, copied verbatim").contains("name: spider-sense");
    }

    /**
     * A second {@code init} overwrites the files it wrote before and leaves anything else
     * in those directories alone: an agent may run it whenever it is unsure.
     */
    @Test
    void aSecondInitRewritesTheSkillsAndKeepsWhatIsNotOurs(@TempDir Path project) throws IOException {
        run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");
        Path skill = project.resolve(".claude/skills/spider-sense/SKILL.md");
        Path mine = project.resolve(".claude/skills/spider-sense/references/notes.md");
        Files.writeString(skill, "clobbered\n", UTF_8);
        Files.writeString(mine, "my own\n", UTF_8);

        Run again = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");

        assertThat(again.exit()).as("stderr: %s", again.err()).isZero();
        assertThat(again.out().lines())
                .as("one line for the block and one for each skill")
                .hasSize(1 + packagedSkills().size());
        assertThat(again.out()).startsWith("updated CLAUDE.md block (jar: /x/spider-sense.jar)\n");
        assertThat(again.out()).contains("installed skill to "
                + project.resolve(".claude/skills/spider-sense-sql-tuning"));
        assertThat(Files.readString(skill, UTF_8))
                .as("the file init owns is written again").contains("name: spider-sense");
        assertThat(Files.readString(mine, UTF_8)).as("not ours to remove").isEqualTo("my own\n");
        assertThat(project.resolve(".claude/skills/spider-sense-sql-tuning/SKILL.md")).isRegularFile();
    }

    /**
     * A second {@code init} is a replacement, not a second copy: everything outside the
     * two markers comes through byte for byte, because the file belongs to the project.
     */
    @Test
    void aSecondInitReplacesTheBlockAndLeavesTheRestOfTheFileAlone(@TempDir Path project) throws IOException {
        Path claude = project.resolve("CLAUDE.md");
        Files.writeString(claude, "# My project\n\nOne rule: no reflection.\n", UTF_8);

        assertThat(run("init", "--dir=" + project, "--jar=/x/spider-sense.jar").out())
                .startsWith("wrote CLAUDE.md block");
        Files.writeString(claude,
                Files.readString(claude, UTF_8) + "\n## Afterwards\n\nKeep me exactly as I am.\n", UTF_8);

        Run again = run("init", "--dir=" + project, "--jar=/y/other/spider-sense.jar");
        assertThat(again.exit()).isZero();
        assertThat(again.out()).startsWith("updated CLAUDE.md block (jar: /y/other/spider-sense.jar)");

        String text = Files.readString(claude, UTF_8);
        assertThat(text).startsWith("# My project\n\nOne rule: no reflection.\n\n<!-- spider-sense:start -->");
        assertThat(text).endsWith("<!-- spider-sense:end -->\n\n## Afterwards\n\nKeep me exactly as I am.\n");
        assertThat(text).contains("/y/other/spider-sense.jar").doesNotContain("/x/spider-sense.jar");
        assertThat(text.split(java.util.regex.Pattern.quote(Init.START), -1))
                .as("one block, not two").hasSize(2);
    }

    @Test
    void initWithoutTheSkillsWritesNoClaudeDirectory(@TempDir Path project) throws IOException {
        Run init = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar", "--no-skill");
        assertThat(init.exit()).isZero();
        assertThat(init.out()).endsWith("skipped skills (--no-skill)\n");
        assertThat(project.resolve(".claude")).doesNotExist();
        assertThat(Files.readString(project.resolve("CLAUDE.md"), UTF_8))
                .as("the block points at the repository instead")
                .contains("<https://github.com/benelog/spider-sense>")
                .contains("`skills/spider-sense-sql-tuning/` of the same repository")
                .doesNotContain("`.claude/skills/spider-sense/SKILL.md`")
                .doesNotContain("`.claude/skills/spider-sense-sql-tuning/SKILL.md`");
    }

    /** Exploded classes and no {@code --jar}: nobody knows the path, and inventing one would be worse. */
    @Test
    void initWithNoJarToNameIsAUsageError(@TempDir Path project) {
        String remembered = System.getProperty(Init.JAR_PROPERTY);
        System.clearProperty(Init.JAR_PROPERTY);
        try {
            Run init = run("init", "--dir=" + project);
            assertThat(init.exit()).isEqualTo(2);
            assertThat(init.err()).contains("--jar=<path>");
            assertThat(init.out()).isEmpty();
            assertThat(project.resolve("CLAUDE.md")).doesNotExist();
        } finally {
            if (remembered != null) {
                System.setProperty(Init.JAR_PROPERTY, remembered);
            }
        }
    }
}
