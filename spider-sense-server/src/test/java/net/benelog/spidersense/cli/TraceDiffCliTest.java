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

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.json.Json;

/**
 * {@code trace <a> --diff=<b>} over both ways of answering.
 *
 * <p>The promise of the CLI is that the H2 file is as good as a running server,
 * and a diff is the answer most likely to drift between the two, because it is
 * the only one rendered from two reads instead of one.
 */
class TraceDiffCliTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String SLOW = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String FIXED = "09e96c4c6db157e690716c2615ffd146";

    private record Run(int exit, String out, String err) {
    }

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

    private static String closedUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same request twice: six repeated statements, then one. */
    private static ExportTraceServiceRequest sample() {
        List<Span.Builder> spans = new ArrayList<>();
        Span.Builder slow = Otlp.span(SLOW, "00f067aa0ba902b7", "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 300,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        spans.add(slow);
        for (int i = 0; i < 6; i++) {
            spans.add(Otlp.child(slow, "%016x".formatted(100 + i), "SELECT product",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from product where id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "product")));
        }
        Span.Builder fixed = Otlp.span(FIXED, "00f067aa0ba902c1", "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW + 1_000, 40,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        spans.add(fixed);
        spans.add(Otlp.child(fixed, "00f067aa0ba902c2", "SELECT product",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1_001, 2,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from product where id in (?)"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "product")));
        return Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0]));
    }

    private static void serve(BiConsumer<SpiderSenseServer, String> body) {
        Config config = TestStore.config();
        SpiderSenseServer server = SpiderSenseServer.start(config);
        try {
            new OtlpDecoder(server.store(), server::port).ingest(sample());
            server.store().writer().awaitIdle(5_000);
            body.accept(server, "http://127.0.0.1:" + server.port());
        } finally {
            server.stop();
        }
    }

    private static void inTheFile(BiConsumer<Store, String> body) {
        Config config = TestStore.config();
        try (Store store = new Store(config.storeSettings(System::currentTimeMillis))) {
            new OtlpDecoder(store, () -> 4000).ingest(sample());
            store.writer().awaitIdle(5_000);
            body.accept(store, "--db=" + config.jdbcUrl());
        }
    }

    private static void assertIsTheDiff(Run run) {
        assertThat(run.exit()).isZero();
        assertThat(run.out()).startsWith("# trace diff " + SLOW + " → " + FIXED + "  ");
        assertThat(run.out()).contains("300.0 ms → 40.0 ms").contains("7 → 2 spans");
        assertThat(run.out()).contains("   a          b          span");
        assertThat(run.out()).contains("=  300.0 ms   40.0 ms    SERVER orders GET /orders/{id} → 200");
        assertThat(run.out()).contains("db SELECT product  × 6").contains("db SELECT product  × 1");
    }

    @Test
    void theServerAnswersTheDiffAsTextAndAsJson() {
        serve((server, base) -> {
            assertIsTheDiff(runAt(base, "trace", SLOW, "--diff=" + FIXED, "--url=" + base));

            Run json = runAt(base, "trace", SLOW, "--diff=" + FIXED, "--json", "--url=" + base);
            assertThat(json.exit()).isZero();
            assertThat(Json.parse(json.out()).asObject().getArray("lines").size()).isEqualTo(3);
        });
    }

    @Test
    void theFileAnswersTheSameDiffWithNoServerRunning() {
        inTheFile((store, db) -> assertIsTheDiff(
                runAt(closedUrl(), "trace", SLOW, "--diff=" + FIXED, db)));
    }

    @Test
    void theJsonCarriesTheAlignedLines() {
        inTheFile((store, db) -> {
            Run run = runAt(closedUrl(), "trace", SLOW, "--diff=" + FIXED, "--json", db);

            assertThat(run.exit()).isZero();
            Json.JsonObject json = Json.parse(run.out()).asObject();
            assertThat(json.getString("a")).isEqualTo(SLOW);
            assertThat(json.getString("b")).isEqualTo(FIXED);
            assertThat(json.getObject("spans").getLong("a")).isEqualTo(7);
            assertThat(json.getArray("lines").size()).isEqualTo(3);
            assertThat(json.getArray("lines").get(1).asObject().getObject("count").getLong("a"))
                    .isEqualTo(6);
        });
    }

    @Test
    void fullExpandsBothSidesBeforeAligning() {
        inTheFile((store, db) -> {
            Run run = runAt(closedUrl(), "trace", SLOW, "--diff=" + FIXED, "--full", db);

            assertThat(run.exit()).isZero();
            assertThat(run.out()).doesNotContain("× 6");
            assertThat(run.out().lines().filter(line -> line.endsWith("db SELECT product")).count())
                    .isEqualTo(6);
        });
    }

    @Test
    void anIdThatIsNotStoredIsFourAndTheMessageNamesIt() {
        String missing = "f".repeat(32);

        inTheFile((store, db) -> {
            Run run = runAt(closedUrl(), "trace", SLOW, "--diff=" + missing, db);

            assertThat(run.exit()).isEqualTo(4);
            assertThat(run.err()).contains("No such trace: " + missing);
            assertThat(run.out()).isEmpty();
        });

        serve((server, base) -> {
            Run run = runAt(base, "trace", missing, "--diff=" + SLOW, "--url=" + base);

            assertThat(run.exit()).isEqualTo(4);
            assertThat(run.err()).contains("No such trace: " + missing);
        });
    }
}
