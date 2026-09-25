package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;

/**
 * The span cap of storage.adoc#retention: while there are more spans than the cap,
 * the oldest hour of everything goes.
 */
class SweeperTest {

    private static final long HOUR = 3_600_000L;
    private static final int HOURS = 5;
    private static final int PER_HOUR = 50;

    /** Now, rounded down to the hour, so every row sits at a predictable distance from it. */
    private static final long NOW = System.currentTimeMillis() / HOUR * HOUR;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null,
            IgnoredEndpoints.DEFAULT, Sweeper.DEFAULT_RETENTION_SPANS, IngestCap.none(), () -> NOW);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);

    @AfterEach
    void close() {
        store.close();
    }

    /** The start of hour {@code n}, counting back from five hours ago. */
    private static long hour(int n) {
        return NOW - (HOURS - n) * HOUR;
    }

    private void fill() {
        for (int h = 0; h < HOURS; h++) {
            for (int i = 0; i < PER_HOUR; i++) {
                int n = h * PER_HOUR + i + 1;
                long at = hour(h) + i * 1000L;
                Span.Builder root = Otlp.span("%032x".formatted(n), "%016x".formatted(n),
                        "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, at, 900,
                        Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/orders"),
                        Otlp.attr("http.response.status_code", 200));
                decoder.ingest(Otlp.traces(Otlp.service("orders"), root));
                decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.Web",
                        Otlp.log(at, 9, "served", "%032x".formatted(n), "%016x".formatted(n))));
            }
            decoder.ingest(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By",
                    hour(h), 1024 * h));
        }
        store.writer().awaitIdle(10_000);
    }

    private long count(String table, String column, long before) {
        return store.sql().count("SELECT COUNT(*) FROM " + table + " WHERE " + column + " < ?",
                List.of(before));
    }

    private long total(String table) {
        return store.sql().count("SELECT COUNT(*) FROM " + table, List.of());
    }

    @Test
    void theSpanCapDeletesTheOldestHoursUntilTheCountIsUnderIt() {
        fill();
        Marks.Mark old = store.marks().create("before", null, "an old moment", hour(0) + 1);
        assertThat(total("span")).isEqualTo(HOURS * PER_HOUR);
        assertThat(total("tingle")).isPositive();
        assertThat(total("metric_point")).isEqualTo(HOURS);

        new Sweeper(store.sql(), 24, 100, () -> NOW).sweep();

        // 250 spans, 50 an hour: hours 0, 1 and 2 go and 100 spans are left.
        assertThat(total("span")).isEqualTo(100);
        assertThat(total("trace")).isEqualTo(100);

        long cut = hour(3);
        assertThat(count("span", "start_ms", cut)).as("the oldest hours went first").isZero();
        assertThat(count("trace", "start_ms", cut)).isZero();
        assertThat(count("log", "at_ms", cut)).isZero();
        assertThat(count("tingle", "at_ms", cut)).isZero();
        assertThat(count("metric_point", "at_ms", cut)).isZero();
        assertThat(total("log")).isEqualTo(100);
        assertThat(total("tingle")).isEqualTo(100);
        assertThat(total("metric_point")).isEqualTo(2);

        assertThat(store.marks().list(50)).as("marks go by the time retention alone")
                .extracting(Marks.Mark::id).contains(old.id());
    }

    @Test
    void aCapOfZeroIsNoCapAtAll() {
        fill();

        new Sweeper(store.sql(), 24, 0, () -> NOW).sweep();

        assertThat(total("span")).isEqualTo(HOURS * PER_HOUR);
        assertThat(total("log")).isEqualTo(HOURS * PER_HOUR);
        assertThat(total("metric_point")).isEqualTo(HOURS);
    }

    /**
     * A catalog row goes by the time retention, because a catalog older than the
     * retention describes a schema no window can show any more (storage.adoc#retention).
     */
    @Test
    void aCatalogRowOlderThanTheRetentionGoesAndAFreshOneStays() {
        catalog("ITEMS", NOW - 48 * HOUR);
        catalog("MOVEMENTS", NOW);
        assertThat(total("db_table")).isEqualTo(2);

        new Sweeper(store.sql(), 24, 0, () -> NOW).sweep();

        assertThat(store.sql().query("SELECT table_name FROM db_table", List.of(),
                rs -> rs.getString(1))).containsExactly("MOVEMENTS");

        store.clear();
        assertThat(total("db_table")).as("DELETE /api/data empties it too").isZero();
    }

    private void catalog(String table, long seen) {
        Batch batch = new Batch();
        batch.add(new Batch.Catalog("orders", "PUBLIC", table, "H2",
                "[{\"name\":\"PRIMARY_KEY_8\",\"unique\":true,\"columns\":[\"ID\"]}]", seen));
        store.submit(batch);
        store.writer().awaitIdle(5_000);
    }

    /**
     * A service that has run for longer than the retention keeps its newest start
     * mark, the only one the writer will not write again; an older start and a
     * named mark go by age as before, and so does everything but that start on a
     * clear.
     */
    @Test
    void eachServicesNewestStartMarkOutlivesTheRetentionAndAClear() {
        Marks marks = new Marks(store.sql());
        long old = NOW - 30 * HOUR;
        marks.create(Marks.START, "orders", "pid 1", old - HOUR);
        marks.create(Marks.START, "orders", "pid 2", old);
        marks.create(Marks.START, "billing", "pid 3", old);
        marks.create("before", null, null, old);

        new Sweeper(store.sql(), 24, Sweeper.DEFAULT_RETENTION_SPANS, () -> NOW).sweep();

        assertThat(store.sql().query("SELECT note FROM mark ORDER BY note", List.of(), rs -> rs.getString(1)))
                .containsExactly("pid 2", "pid 3");

        marks.create("after", null, null, NOW);
        store.database().deleteAll();

        assertThat(store.sql().query("SELECT note FROM mark ORDER BY note", List.of(), rs -> rs.getString(1)))
                .containsExactly("pid 2", "pid 3");
    }

    /** The retention keeps what is exactly as old as it, and takes what is a millisecond older. */
    @Test
    void theRetentionCutoffIsExact() {
        long cutoff = NOW - 24 * HOUR;
        store.marks().create("older", null, null, cutoff - 1);
        store.marks().create("exact", null, null, cutoff);
        catalog("OLDER", cutoff - 1);
        catalog("EXACT", cutoff);

        new Sweeper(store.sql(), 24, 0, () -> NOW).sweep();

        assertThat(store.marks().list(50)).extracting(Marks.Mark::name).containsExactly("exact");
        assertThat(store.sql().query("SELECT table_name FROM db_table", List.of(),
                rs -> rs.getString(1))).containsExactly("EXACT");
    }
}
