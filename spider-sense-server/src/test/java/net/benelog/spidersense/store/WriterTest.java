package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
