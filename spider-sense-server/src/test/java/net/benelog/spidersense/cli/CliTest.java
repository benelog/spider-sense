package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
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
}
