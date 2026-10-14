package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;

/**
 * {@code export} and {@code import} from the terminal
 * (cli.adoc#export-import).
 *
 * <p>Both halves of the promise are exercised: the file a running Spider Sense
 * writes, and the file a database with no server reads back — because the point
 * of the pair is to move a session between two machines, and neither end is
 * guaranteed to have a server up.
 */
class ExportCommandTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String FAILING = "4bf92f3577b34da6a3ce929d0e0e4737";

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

    /** A command that must not need the network: nothing listens on the default URL. */
    private static Run run(String... args) {
        return runAt("http://127.0.0.1:1", args);
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

    /** Eight spans over two traces: a request that repeats a query, and one that failed. */
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

    @Test
    void exportWritesTheFileAndImportPrintsWhatArrived(@TempDir Path dir) {
        Path file = dir.resolve("session.json");
        serve((server, base) -> {
            Run export = runAt(base, "export", "--out=" + file, "--url=" + base);
            assertThat(export.exit()).isZero();
            assertThat(export.out()).isEmpty();
            assertThat(export.err()).isEqualTo("wrote " + file + "\n");
        });

        JsonObject document = Json.parse(read(file)).asObject();
        assertThat(document.getArray("spans")).hasSize(8);
        assertThat(document.getObject("spiderSense").has("schema")).isTrue();

        String db = "--db=" + TestStore.writtenUrl();
        Run first = run("import", file.toString(), db);
        assertThat(first.exit()).isZero();
        assertThat(first.out()).startsWith("imported 8 spans, 0 logs, 0 metric points, ");
        assertThat(first.out()).contains(" marks from ").doesNotContain("already present");

        Run again = run("import", file.toString(), db);
        assertThat(again.exit()).isZero();
        assertThat(again.out()).startsWith("imported 0 spans, ");
        assertThat(again.out()).contains("(2 traces already present)");

        Run json = run("import", file.toString(), db, "--json");
        assertThat(Json.parse(json.out()).asObject().getLong("skippedTraces")).isEqualTo(2);

        Run traces = run("traces", db);
        assertThat(traces.exit()).isZero();
        assertThat(traces.out()).contains(TRACE).contains(FAILING);
    }

    @Test
    void aGzippedNameIsAGzippedFileAtBothEnds(@TempDir Path dir) {
        Path packed = dir.resolve("session.json.gz");
        serve((server, base) -> assertThat(
                runAt(base, "export", "--out=" + packed, "--url=" + base).exit()).isZero());

        assertThat(gunzip(packed)).startsWith("{\"spiderSense\":");

        String db = "--db=" + TestStore.writtenUrl();
        Run imported = run("import", packed.toString(), db);
        assertThat(imported.exit()).isZero();
        assertThat(imported.out()).startsWith("imported 8 spans, ");

        // And out of that database again, with no server anywhere.
        Path second = dir.resolve("again.json.gz");
        Run export = run("export", db, "--out=" + second);
        assertThat(export.exit()).isZero();
        assertThat(Json.parse(gunzip(second)).asObject().getArray("spans")).hasSize(8);
    }

    @Test
    void importSaysWhichFileIsMissingRatherThanImportingNothing() {
        Run missing = run("import", "/no/such/session.json", "--db=" + TestStore.writtenUrl());
        assertThat(missing.exit()).isEqualTo(2);
        assertThat(missing.err()).contains("no such file");
    }

    /**
     * A document import refuses, and a file export cannot write, are the same one sentence over
     * HTTP and from the file, never a Java class name and never without the reason.
     */
    @Test
    void aRefusedImportOrExportIsTheSameSentenceInBothModes(@TempDir Path dir) throws IOException {
        Path oldSchema = Files.writeString(dir.resolve("old.json"), "{\"spiderSense\":{\"schema\":3}}");
        Path notJson = Files.writeString(dir.resolve("notjson.txt"), "this is not JSON");
        Path notGzip = Files.writeString(dir.resolve("fake.json.gz"), "{}");
        String db = "--db=" + TestStore.writtenUrl();
        serve((server, base) -> {
            for (Path file : List.of(oldSchema, notJson, notGzip)) {
                Run http = runAt(base, "import", file.toString(), "--url=" + base);
                Run local = run("import", file.toString(), db);
                assertThat(http.exit()).as(file.toString()).isEqualTo(2);
                assertThat(local.exit()).as(file.toString()).isEqualTo(2);
                assertThat(local.err()).as(file.toString()).isEqualTo(http.err())
                        .doesNotContain("Exception").doesNotContain("net.benelog");
            }
            assertThat(run("import", oldSchema.toString(), db).err()).isEqualTo("spider-sense: the document"
                    + " is schema version 3 and this Spider Sense reads schema version "
                    + net.benelog.spidersense.store.Schema.VERSION + "\n");
            assertThat(run("import", notGzip.toString(), db).err())
                    .isEqualTo("spider-sense: Undecodable import document: Not in GZIP format\n");

            Run http = runAt(base, "export", "--out=" + dir, "--url=" + base);
            Run local = run("export", "--out=" + dir, db);
            assertThat(http.exit()).isEqualTo(2);
            assertThat(local.exit()).isEqualTo(2);
            assertThat(http.err()).isEqualTo("spider-sense: could not write " + dir + ": Is a directory\n");
            assertThat(local.err()).isEqualTo(http.err());
        });
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String gunzip(Path file) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
