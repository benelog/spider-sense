package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersilk.json.Json;

/**
 * {@code tail}: the SSE stream read as lines, and what ends it.
 *
 * <p>The parser is driven through its own seam with a stream of bytes, because
 * every rule worth testing — the keepalive comment, the blank line that ends an
 * event, the baseline the first {@code stats} sets — is about the bytes and not
 * about the socket. One test then runs the whole command against a real server,
 * so the seam is not the only thing that has ever been exercised.
 */
class TailTest {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final long FIRST = 1_700_000_000_123L;
    private static final long SECOND = 1_700_000_001_456L;

    private static final String STREAM = """
            : keepalive

            event: stats
            data: {"at":1,"spans":10,"traces":5,"logs":0,"droppedSpans":0}

            event: tingle
            data: {"kind":"slow-query","at":%d,"service":"spring-orders","title":"SELECT orders",\
            "detail":"1,532 ms","traceId":"2519b548daad800090e8f56de6a5a62a","spanId":"aa",\
            "durationMs":1532.4}

            event: service
            data: {"name":"silk-bookstore","firstSeen":1}

            event: tingle
            data: {"kind":"error","at":%d,"service":"silk-bookstore","title":"GET /api/books/stats",\
            "detail":"ArithmeticException: / by zero","traceId":"09e96c4c6db157e690716c2615ffd146",\
            "spanId":"bb","durationMs":1.0}

            event: stats
            data: {"at":2,"spans":20,"traces":6,"logs":0,"droppedSpans":0}

            event: tingle
            data: {"kind":"error","at":%d,"service":"silk-bookstore","title":"after the count grew",\
            "detail":"never printed","traceId":"09e96c4c6db157e690716c2615ffd146","spanId":"cc",\
            "durationMs":1.0}

            """.formatted(FIRST, SECOND, SECOND);

    private record Run(int exit, String out, String err) {
    }

    private static Run read(Tail.Watch watch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8)) {
            exit = Tail.read(new ByteArrayInputStream(STREAM.getBytes(UTF_8)), watch, toOut);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Run(exit, out.toString(UTF_8), "");
    }

    private static Run cli(String defaultUrl, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(args, defaultUrl, toOut, toErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    private static String clock(long at) {
        return CLOCK.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    private static String closedUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- the parser and the columns --------------------------------------------

    @Test
    void oneLinePerTingleInTheColumnsAgentMdNames() {
        Run run = read(new Tail.Watch(null, null, null, null, false));

        assertThat(run.exit()).isZero();
        assertThat(run.out()).isEqualTo(
                clock(FIRST) + "  slow-query    spring-orders   SELECT orders  1,532 ms  "
                        + "2519b548daad800090e8f56de6a5a62a\n"
                        + clock(SECOND) + "  error         silk-bookstore  GET /api/books/stats  "
                        + "ArithmeticException: / by zero  09e96c4c6db157e690716c2615ffd146\n"
                        + clock(SECOND) + "  error         silk-bookstore  after the count grew  "
                        + "never printed  09e96c4c6db157e690716c2615ffd146\n");
    }

    @Test
    void kindAndServiceFilterOneValueEach() {
        assertThat(read(new Tail.Watch("slow-query", null, null, null, false)).out())
                .containsOnlyOnce("SELECT orders")
                .doesNotContain("ArithmeticException");

        assertThat(read(new Tail.Watch(null, "silk-bookstore", null, null, false)).out())
                .doesNotContain("SELECT orders")
                .contains("ArithmeticException");
    }

    @Test
    void untilTracesEndsOnceTheCountHasGrownByThatMuchSinceTheFirstStats() {
        Run run = read(new Tail.Watch(null, null, 1L, null, false));

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("SELECT orders").contains("ArithmeticException")
                .doesNotContain("after the count grew");
    }

    @Test
    void jsonPrintsTheEventObjectWithItsNameAddedOnePerLine() {
        Run run = read(new Tail.Watch(null, null, null, null, true));

        List<String> lines = run.out().lines().toList();
        assertThat(lines).hasSize(5);
        Json.JsonObject stats = Json.parse(lines.get(0)).asObject();
        assertThat(stats.getString("event")).isEqualTo("stats");
        assertThat(stats.getLong("traces")).isEqualTo(5);
        Json.JsonObject tingle = Json.parse(lines.get(1)).asObject();
        assertThat(tingle.getString("event")).isEqualTo("tingle");
        assertThat(tingle.getString("kind")).isEqualTo("slow-query");
        assertThat(tingle.getString("title")).isEqualTo("SELECT orders");
    }

    // --- the command -------------------------------------------------------------

    @Test
    void nothingAtTheUrlIsAMessageOnStderrAndTwoWithNoFileFallback() {
        String closed = closedUrl();

        Run run = cli(closed, "tail");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).startsWith("spider-sense: no Spider Sense at " + closed)
                .contains("no file to tail");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aKindThatIsNotATingleKindIsAUsageError() {
        Run run = cli(closedUrl(), "tail", "--kind=slow");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("--kind is one of");
    }

    @Test
    void tailHasNoFileToReadSoItRefusesDb() {
        Run run = cli(closedUrl(), "tail", "--db=jdbc:h2:mem:nothing");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("unknown option for tail: --db");
    }

    @Test
    void aTimeoutThatIsNotADurationIsAUsageError() {
        Run run = cli(closedUrl(), "tail", "--timeout=soon");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("Not a duration: soon");
    }

    @Test
    void theStreamOfARunningSpiderSenseEndsWhenTheTraceItWasWaitingForArrives() {
        Config config = TestStore.config();
        SpiderSenseServer server = SpiderSenseServer.start(config);
        try {
            String base = "http://127.0.0.1:" + server.port();
            Thread traffic = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                new OtlpDecoder(server.store(), server::port).ingest(sample());
                server.store().writer().awaitIdle(5_000);
            });
            traffic.setDaemon(true);
            traffic.start();

            Run run = cli(base, "tail", "--until-traces=1", "--timeout=30s");

            assertThat(run.exit()).isZero();
            assertThat(run.err()).isEmpty();
        } finally {
            server.stop();
        }
    }

    /** One request slow enough to be a tingle, so the stream has something to say. */
    private static io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest sample() {
        return Otlp.traces(Otlp.service("orders"),
                Otlp.span("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                        "GET /orders/report", Span.SpanKind.SPAN_KIND_SERVER,
                        System.currentTimeMillis() - 2_000, 1_500,
                        Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/orders/report"),
                        Otlp.attr("http.response.status_code", 200)));
    }
}
