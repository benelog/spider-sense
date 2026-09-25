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

    private final Store store = new Store(Store.Settings.defaults(TestStore.memoryUrl()));
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);

    @AfterEach
    void close() {
        store.close();
    }

    private void gauge(long at, double value) {
        decoder.ingest(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By", at, value,
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
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.Job",
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
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.Job", Otlp.log(AT, 9, "fine", null, null)));

        store.writer().awaitIdle(5_000);

        assertThat(store.sql().query("SELECT body FROM log", List.of(), rs -> rs.getString(1)))
                .containsExactly("fine");
    }

    /**
     * The overflow strikes after the flush's spans are inserted: the flush must roll
     * them back before each batch is written again on its own, or the spans are
     * committed without their trace row and then stored a second time.
     */
    @Test
    void aValueThatOverflowsTheEncoderAfterTheSpansLeavesThemStoredOnce() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        Object nested = "leaf";
        for (int i = 0; i < 100_000; i++) {
            nested = List.of(nested);
        }
        Batch deep = new Batch();
        deep.add(new LogRecord(0, AT, "orders", "INFO", 9, "deep", null, null, null,
                java.util.Map.of("nested", nested)));
        writer.submit(spans(1));
        writer.submit(deep);

        writer.flushNow();

        assertThat(database.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(1);
        assertThat(database.sql().query("SELECT span_count FROM trace", List.of(), rs -> rs.getInt(1)))
                .containsExactly(1);
        assertThat(database.sql().count("SELECT COUNT(*) FROM log", List.of())).isZero();
        database.close();
    }

    /**
     * A flush after a burst, or an import, touches tens of thousands of traces. Their
     * spans are read back in chunks, because one {@code IN} list of every id costs H2
     * the square of its length: 20,000 traces took about ten seconds that way, and the
     * writer fell behind the queue.
     */
    @Test
    void theRecomputeOfTwentyThousandTracesReadsTheirSpansInChunks() throws Exception {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        int traces = 20_000;
        Batch batch = new Batch();
        for (int i = 0; i < traces; i++) {
            batch.add(new SpanRecord("%032x".formatted(i + 1), "%016x".formatted(1), null, "orders",
                    "GET /orders", "SERVER", AT * 1_000_000L, AT * 1_000_000L + 5_000_000L, "UNSET", null,
                    java.util.Map.of(), List.of(), "test"));
        }

        long tookMs;
        try (java.sql.Connection connection = database.sql().connection()) {
            connection.setAutoCommit(false);
            Set<String> touched = writer.insertSpans(connection, List.of(batch));
            long started = System.nanoTime();
            new TraceSummaries(500).merge(connection, touched);
            tookMs = (System.nanoTime() - started) / 1_000_000;
            connection.commit();
        }

        assertThat(database.sql().count("SELECT COUNT(*) FROM trace WHERE span_count = 1", List.of()))
                .isEqualTo(traces);
        assertThat(tookMs).as("the recompute of %d traces, in ms", traces).isLessThan(4_000);
        database.close();
    }

    /** A service or metric name longer than its column is stored cut, the same in every table. */
    @Test
    void aNameLongerThanItsColumnIsCutRatherThanLosingTheFlush() {
        String name = "s".repeat(300);
        decoder.ingest(Otlp.traces(Otlp.service(name), Otlp.span("%032x".formatted(1), "%016x".formatted(1),
                "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, AT, 5)));
        decoder.ingest(Otlp.gauge(Otlp.service(name), "m".repeat(300), "By", AT, 1));
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

    /** An SDK language longer than its column is stored cut, not refused with the flush. */
    @Test
    void aLanguageLongerThanItsColumnIsCutRatherThanLosingTheFlush() {
        String language = "j".repeat(100);
        decoder.ingest(Otlp.traces(Otlp.resource(Otlp.attr("service.name", "orders"),
                        Otlp.attr("telemetry.sdk.language", language)),
                Otlp.span("%032x".formatted(1), "%016x".formatted(1), "GET /orders",
                        Span.SpanKind.SPAN_KIND_SERVER, AT, 5)));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().query("SELECT language FROM service WHERE name = 'orders'", List.of(),
                rs -> rs.getString(1))).containsExactly(language.substring(0, 64));
        assertThat(store.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(1);
    }

    /** A double attribute JSON has no number for is kept as text, not refused with its export. */
    @Test
    void aNonFiniteDoubleAttributeIsStoredAsText() {
        io.opentelemetry.proto.common.v1.KeyValue nan = io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                .setKey("ratio")
                .setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setDoubleValue(Double.NaN))
                .build();
        decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.span("%032x".formatted(3), "%016x".formatted(3),
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
        decoder.ingest(Otlp.metrics(Otlp.service("orders"), io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
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
        decoder.ingest(Otlp.histogram(Otlp.service("orders"), "http.server.request.duration", AT, 3, 0.3,
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
        writer.submit(decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.span("%032x".formatted(99),
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
        Batch orders = decoder.ingest(Otlp.traces(Otlp.service("orders"), client));
        Batch bookstore = decoder.ingest(Otlp.traces(Otlp.service("bookstore"), server));

        try (java.sql.Connection a = database.sql().connection();
                java.sql.Connection b = database.sql().connection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            Set<String> touchedByA = first.insertSpans(a, List.of(orders));
            Set<String> touchedByB = second.insertSpans(b, List.of(bookstore));
            new TraceSummaries(500).merge(a, touchedByA);

            var merging = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    new TraceSummaries(500).merge(b, touchedByB);
                    b.commit();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            LockWaits.awaitRunning(database.sql(), "MERGE INTO trace");
            assertThat(merging).as("the second recompute waits for the first").isNotDone();
            a.commit();
            merging.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(database.sql().count("SELECT span_count FROM trace WHERE trace_id = ?", List.of(trace)))
                .isEqualTo(2);
        database.close();
    }

    /**
     * The orphan sweep, in this process or another, runs while a flush adds the
     * first points of a series that has none committed. It must wait for that
     * flush and keep the series, not delete it under the uncommitted points.
     */
    @Test
    void theOrphanSweepWaitsForAFlushAddingPointsToASeries() throws Exception {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        Batch first = decoder.ingest(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By", AT, 1));
        writer.submit(first);
        writer.flushNow();
        // The point goes by age; the series stays, cached in the writer, with nothing committed.
        database.sql().update("DELETE FROM metric_point", List.of());

        Batch next = decoder.ingest(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By", AT + 1_000, 2));
        try (java.sql.Connection flush = database.sql().connection()) {
            flush.setAutoCommit(false);
            writer.writeMetrics(flush, List.of(next));

            var sweep = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> Sweeper.deleteOrphanSeries(database.sql()));
            LockWaits.awaitBlocked(database.sql(), 1);
            assertThat(sweep).as("the sweep waits for the flush's lock").isNotDone();
            flush.commit();
            assertThat(sweep.get(10, java.util.concurrent.TimeUnit.SECONDS)).isZero();
        }

        assertThat(database.sql().count("SELECT COUNT(*) FROM metric_point p"
                + " JOIN metric_series s ON s.id = p.series_id", List.of())).isEqualTo(1);
        database.close();
    }

    // --- DELETE /api/data ------------------------------------------------------------

    /**
     * The writer's thread keeps flushing while the data is cleared. A flush between
     * two of the deletes would keep its spans and lose its trace rows, so none runs
     * until the deletes have committed, and it then writes into the emptied store.
     */
    @Test
    void aFlushWaitsForAClearAndIsStoredWhole() throws Exception {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        writer.submit(spans(3));
        Batch after = decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.span("%032x".formatted(99),
                "%016x".formatted(99), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, AT, 5)));
        var flushed = new java.util.concurrent.CompletableFuture<Void>();
        Thread flushing = new Thread(() -> {
            writer.flushNow();
            flushed.complete(null);
        }, "flushing");

        writer.clear(() -> {
            writer.submit(after);
            flushing.start();
            LockWaits.awaitBlocked(flushing);
            assertThat(flushed).as("the flush waits for the clear").isNotDone();
            database.deleteAll();
        });
        flushed.get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(database.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(1);
        assertThat(database.sql().count("SELECT COUNT(*) FROM trace", List.of())).isEqualTo(1);
        database.close();
    }

    /** The deletes are one transaction: one that fails leaves every table as it was. */
    @Test
    void aClearThatFailsDeletesNothing() {
        Database database = Database.open(fileUrl(), dir.resolve("sense.mv.db"));
        Writer writer = writer(database);
        writer.submit(spans(2));
        writer.flushNow();
        // A table that is not there fails the delete after span's and trace's.
        database.sql().execute("DROP TABLE ack");

        org.assertj.core.api.Assertions.assertThatThrownBy(database::deleteAll)
                .isInstanceOf(Sql.SqlException.class);

        assertThat(database.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(2);
        assertThat(database.sql().count("SELECT COUNT(*) FROM trace", List.of())).isEqualTo(2);
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
        return decoder.ingest(Otlp.traces(Otlp.service("orders"), spans));
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

        assertThat(writer.queuedBatches()).as("left for nobody, rather than written into a closed file")
                .isEqualTo(1);
        database.close();
    }
}
