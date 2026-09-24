package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;

/** The write-behind writer of storage.adoc#writer. */
class WriterTest {

    private static final long AT = 1_700_000_000_000L;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);

    @AfterEach
    void close() {
        store.close();
    }

    private void gauge(long at, double value) {
        decoder.accept(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By", at, value,
                Otlp.attr("jvm.memory.type", "heap")));
        store.writer().awaitIdle(5_000);
    }

    private long pointsWithASeries() {
        return store.sql().count("SELECT COUNT(*) FROM metric_point p"
                + " JOIN metric_series s ON s.id = p.series_id", List.of());
    }

    /**
     * {@code DELETE /api/data} in another process sharing the file, or the sweeper
     * removing a series that went quiet, deletes series rows this writer has cached
     * the ids of. The next point of such a series must join a series row again.
     */
    @Test
    void aSeriesDeletedBehindTheWritersBackIsFoundOrCreatedAgain() {
        gauge(AT, 1);
        assertThat(pointsWithASeries()).isEqualTo(1);

        // What another process's DELETE /api/data does; this writer's cache never hears of it.
        store.database().deleteAll();
        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_series", List.of())).isZero();

        gauge(AT + 1_000, 2);
        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_point", List.of())).isEqualTo(1);
        assertThat(pointsWithASeries()).as("the point joins a series row").isEqualTo(1);

        gauge(AT + 2_000, 3);
        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_series", List.of()))
                .as("the series is created once, and its id cached again").isEqualTo(1);
        assertThat(pointsWithASeries()).isEqualTo(2);
    }

    /**
     * A sender that sets no severity leaves the proto default 0; its name must fit the
     * column, or the flush fails and takes every span flushed beside it along.
     */
    @Test
    void aLogWithoutASeverityIsStoredWithTheSpansFlushedBesideIt() {
        spans(1);
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.Job",
                Otlp.log(AT, 0, "no severity", null, null)));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().count("SELECT COUNT(*) FROM log WHERE severity = 'UNSET'", List.of()))
                .isEqualTo(1);
        assertThat(store.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(1);
    }

    /** A value nested deep enough to overflow the JSON encoder costs its own export only. */
    @Test
    void aValueThatOverflowsTheEncoderCostsItsExportAndNotTheWriter() {
        Object nested = "leaf";
        for (int i = 0; i < 100_000; i++) {
            nested = List.of(nested);
        }
        Batch deep = new Batch();
        deep.add(new LogRecord(0, AT, "orders", "INFO", 9, "deep", null, null, null,
                java.util.Map.of("nested", nested)));
        store.writer().submit(deep);
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.Job", Otlp.log(AT, 9, "fine", null, null)));

        store.writer().awaitIdle(5_000);

        assertThat(store.sql().query("SELECT body FROM log", List.of(), rs -> rs.getString(1)))
                .containsExactly("fine");
    }

    /** A service or metric name longer than its column is stored cut, the same in every table. */
    @Test
    void aNameLongerThanItsColumnIsCutRatherThanLosingTheFlush() {
        String name = "s".repeat(300);
        decoder.accept(Otlp.traces(Otlp.service(name), Otlp.span("%032x".formatted(1), "%016x".formatted(1),
                "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, AT, 5)));
        decoder.accept(Otlp.gauge(Otlp.service(name), "m".repeat(300), "By", AT, 1));
        store.writer().awaitIdle(5_000);

        String cut = name.substring(0, 255);
        assertThat(store.sql().count("SELECT COUNT(*) FROM span WHERE service = ?", List.of(cut))).isEqualTo(1);
        assertThat(store.sql().count("SELECT COUNT(*) FROM trace WHERE root_service = ?", List.of(cut)))
                .isEqualTo(1);
        assertThat(store.sql().count("SELECT COUNT(*) FROM service WHERE name = ?", List.of(cut))).isEqualTo(1);
        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_series WHERE service = ? AND name = ?",
                List.of(cut, "m".repeat(255)))).isEqualTo(1);
        assertThat(pointsWithASeries()).isEqualTo(1);
    }

    /** A double attribute JSON has no number for is kept as text, not refused with its export. */
    @Test
    void aNonFiniteDoubleAttributeIsStoredAsText() {
        io.opentelemetry.proto.common.v1.KeyValue nan = io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                .setKey("ratio")
                .setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setDoubleValue(Double.NaN))
                .build();
        decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.span("%032x".formatted(3), "%016x".formatted(3),
                "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, AT, 5, nan)));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().query("SELECT attributes FROM span", List.of(), rs -> rs.getString(1)))
                .singleElement().asString().contains("\"ratio\":\"NaN\"");
    }

    /** A histogram without min and max stores neither, rather than a max of 0. */
    @Test
    void aHistogramWithoutMinAndMaxStoresNeither() {
        var point = io.opentelemetry.proto.metrics.v1.HistogramDataPoint.newBuilder()
                .setTimeUnixNano(AT * 1_000_000L).setCount(4).setSum(2.0)
                .addExplicitBounds(1).addBucketCounts(3).addBucketCounts(1);
        decoder.accept(Otlp.metrics(Otlp.service("orders"), io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                .setName("http.client.request.duration")
                .setHistogram(io.opentelemetry.proto.metrics.v1.Histogram.newBuilder().addDataPoints(point))
                .build()));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_point WHERE min IS NULL AND max IS NULL",
                List.of())).isEqualTo(1);
    }

    /** Buckets past their column are left out; the point keeps its count and sum. */
    @Test
    void aHistogramWithTooManyBucketsIsStoredWithoutThem() {
        double[] bounds = new double[1_000];
        long[] counts = new long[1_001];
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = i * 1.5;
        }
        counts[0] = 3;
        decoder.accept(Otlp.histogram(Otlp.service("orders"), "http.server.request.duration", AT, 3, 0.3,
                bounds, counts));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().count("SELECT COUNT(*) FROM metric_point WHERE count = 3 AND buckets IS NULL",
                List.of())).isEqualTo(1);
    }

    /** A batch the database refuses costs itself only, not the batches flushed beside it. */
    @Test
    void aRefusedBatchDoesNotTakeTheOthersOfItsFlushAlong() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        Batch refused = new Batch();
        refused.addTingles(List.of(new Tingle("k".repeat(20), AT, "orders", "too long a kind", null, null, null, 0)));
        writer.submit(spans(1));
        writer.submit(refused);
        writer.submit(decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.span("%032x".formatted(99),
                "%016x".formatted(99), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, AT, 5))));

        writer.flushNow();

        assertThat(database.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(2);
        assertThat(database.sql().count("SELECT COUNT(*) FROM tingle", List.of())).isZero();
        database.close();
    }

    /**
     * Two processes sharing the file flush the two halves of one distributed trace
     * at once. The second to recompute the trace row must wait for the first and
     * count both halves, not overwrite the row with its own half.
     */
    @Test
    void twoWritersFlushingOneTraceAtOnceBothCountInItsRow() throws Exception {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer first = writer(database);
        Writer second = writer(database);
        String trace = "%032x".formatted(7);
        Span.Builder client = Otlp.span(trace, "%016x".formatted(1), "GET /books", Span.SpanKind.SPAN_KIND_CLIENT,
                AT, 20);
        Span.Builder server = Otlp.child(client, "%016x".formatted(2), "GET /books", Span.SpanKind.SPAN_KIND_SERVER,
                AT + 1, 10);
        Batch orders = decoder.accept(Otlp.traces(Otlp.service("orders"), client));
        Batch bookstore = decoder.accept(Otlp.traces(Otlp.service("bookstore"), server));

        try (java.sql.Connection a = database.sql().connection();
                java.sql.Connection b = database.sql().connection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            Set<String> touchedByA = first.insertSpans(a, List.of(orders));
            Set<String> touchedByB = second.insertSpans(b, List.of(bookstore));
            first.mergeTraces(a, touchedByA);

            var merging = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    second.mergeTraces(b, touchedByB);
                    b.commit();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            a.commit();
            merging.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(database.sql().count("SELECT span_count FROM trace WHERE trace_id = ?", List.of(trace)))
                .isEqualTo(2);
        database.close();
    }

    // --- the flush at exit -----------------------------------------------------------

    @TempDir
    Path dir;

    /** A batch of {@code count} spans, decoded the way the receiver decodes them. */
    private Batch spans(int count) {
        Span.Builder[] spans = new Span.Builder[count];
        for (int i = 0; i < count; i++) {
            spans[i] = Otlp.span("%032x".formatted(i + 1), "%016x".formatted(i + 1), "GET /orders",
                    Span.SpanKind.SPAN_KIND_SERVER, AT, 5, Otlp.attr("http.route", "/orders"));
        }
        return decoder.accept(Otlp.traces(Otlp.service("orders"), spans));
    }

    private String fileUrl() {
        return "jdbc:h2:" + dir.resolve("sense") + ";AUTO_SERVER=TRUE;NON_KEYWORDS=KEY,VALUE";
    }

    private Writer writer(Database database) {
        return new Writer(database.sql(), new EventBus(),
                new Tingles(500, 100, IgnoredEndpoints.of(IgnoredEndpoints.DEFAULT)));
    }

    private long storedSpans() {
        try (Database reopened = Database.open(fileUrl(), dir.resolve("sense.mv.db"))) {
            assertThat(reopened.storage().fallback()).isFalse();
            return reopened.sql().count("SELECT COUNT(*) FROM span", List.of());
        }
    }

    /**
     * H2's own exit hook may close the file before the writer's runs, and
     * {@code DB_CLOSE_ON_EXIT=FALSE} is refused beside {@code AUTO_SERVER=TRUE}: the
     * flush at exit opens the file again for what is queued, and closes it again.
     */
    @Test
    void theFlushAtExitStoresWhatIsQueuedAfterH2ClosedTheFile() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        writer.submit(spans(20));

        // What H2's exit hook does when it runs first: every session goes, the file is closed.
        database.sql().execute("SHUTDOWN");
        assertThat(dir.resolve("sense.lock.db")).doesNotExist();

        writer.exit(database);

        assertThat(dir.resolve("sense.lock.db")).as("the file it opened again is closed again")
                .doesNotExist();
        assertThat(storedSpans()).isEqualTo(20);
        database.close();
    }

    /** When the writer's hook runs first, it writes through the open file and then closes it. */
    @Test
    void theFlushAtExitClosesTheFileAfterItsWriteWhenItRunsFirst() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        writer.submit(spans(20));
        assertThat(Files.exists(dir.resolve("sense.lock.db"))).as("AUTO_SERVER holds the file").isTrue();

        writer.exit(database);

        assertThat(dir.resolve("sense.lock.db")).doesNotExist();
        assertThat(storedSpans()).isEqualTo(20);
        database.close();
    }

    /** Once the flush at exit has run, a late flush must not open the file again behind it. */
    @Test
    void aFlushAfterTheExitWritesNothing() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        writer.exit(database);
        writer.submit(spans(1));

        writer.flushNow();

        assertThat(writer.queued()).as("left for nobody, rather than written into a closed file")
                .isEqualTo(1);
        database.close();
    }
}
