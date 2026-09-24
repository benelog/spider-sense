package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
