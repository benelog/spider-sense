package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.store.SpanRecord;

/**
 * Self time, the time split and the waterfall order over hand-built spans, as
 * findings.adoc#time and #hot-span define them: no store, no ingest.
 */
class TimeSplitTest {

    private static final long MS = 1_000_000L;

    private static SpanRecord span(String id, String parent, String name, String kind, long startMs,
            long durationMs, Map<String, Object> attributes) {
        return new SpanRecord("t1", id, parent, "orders", name, kind, startMs * MS,
                (startMs + durationMs) * MS, "UNSET", null, attributes, List.of(), "test");
    }

    private static SpanRecord internal(String id, String parent, String name, long startMs,
            long durationMs) {
        return span(id, parent, name, "INTERNAL", startMs, durationMs, Map.of());
    }

    private static SpanRecord query(String id, String parent, String table, long startMs,
            long durationMs) {
        return span(id, parent, "SELECT " + table, "CLIENT", startMs, durationMs,
                Map.of("db.system", "h2", "db.operation", "SELECT", "db.sql.table", table));
    }

    private static SpanRecord call(String id, String parent, String url, long startMs, long durationMs) {
        return span(id, parent, "GET", "CLIENT", startMs, durationMs,
                Map.of("http.request.method", "GET", "url.full", url,
                        "server.address", "localhost", "server.port", 8081L));
    }

    @Test
    void selfTimeIsTheDurationLessTheDirectChildrenNeverBelowZero() {
        List<SpanRecord> spans = List.of(
                internal("a", null, "root", 0, 100),
                internal("b", "a", "work", 10, 60),
                internal("c", "b", "inner", 20, 30),
                // Async work that outlives its parent leaves the parent no time of its own.
                internal("d", "c", "async", 25, 50));

        Map<String, Long> self = Queries.selfNanos(spans);

        assertThat(self).containsEntry("a", 40 * MS)
                .containsEntry("b", 30 * MS)
                .containsEntry("c", 0L)
                .containsEntry("d", 50 * MS);
    }

    @Test
    void aChildWhoseParentWasNotReadTakesNothingFromAnyone() {
        List<SpanRecord> spans = List.of(
                internal("a", null, "root", 0, 100),
                internal("x", "gone", "orphan", 10, 40));

        assertThat(Queries.selfNanos(spans)).containsEntry("a", 100 * MS).containsEntry("x", 40 * MS);
    }

    @Test
    void aSpanWhoseParentWasCutOffByTheReadIsATopSpan() {
        // "x" belongs to a parent another service owns: it is this service's own entry.
        Queries.TimeSplit split = Queries.TimeSplit.of(List.of(
                internal("a", null, "root", 0, 100),
                query("q", "a", "orders", 10, 20),
                internal("x", "gone", "consumer", 200, 100),
                query("r", "x", "items", 210, 50)));

        // Two top spans of 100 ms: 200 ms, of which 70 is database and 130 their own.
        assertThat(split.breakdown()).containsEntry("db", 70.0 / 200)
                .containsEntry("self", 130.0 / 200)
                .containsEntry("http", 0.0)
                .containsEntry("internal", 0.0);
    }

    @Test
    void theSharesSumToOneAndArePrintedInTheirOrder() {
        Queries.TimeSplit split = Queries.TimeSplit.of(List.of(
                internal("a", null, "GET /orders", 0, 100),
                query("q1", "a", "orders", 5, 10),
                query("q2", "a", "orders", 20, 10),
                call("h", "a", "http://localhost:8081/api/books/42", 40, 30),
                internal("i", "a", "render", 75, 20)));

        assertThat(split.breakdown().keySet()).containsExactly("db", "http", "internal", "self");
        double sum = 0;
        for (double share : split.breakdown().values()) {
            sum += share;
        }
        assertThat(sum).isCloseTo(1.0, offset(1e-9));
        assertThat(split.breakdown()).containsEntry("db", 0.2).containsEntry("self", 0.3);
    }

    @Test
    void theHotSpansAreTheLargestSelfTimesByNameWithTiesByName() {
        Queries.TimeSplit split = Queries.TimeSplit.of(List.of(
                internal("a", null, "GET /orders", 0, 100),
                query("q1", "a", "orders", 5, 10),
                query("q2", "a", "orders", 20, 10),
                call("h", "a", "http://localhost:8081/api/books/42", 40, 20),
                internal("i", "a", "render", 75, 20),
                internal("j", "a", "audit", 96, 1)));

        assertThat(split.hotSpans()).extracting(Queries.HotSpan::name)
                // 20 ms each: the call, named as n-plus-one-http names it, the statements
                // summed under their summary, and render; the name breaks the tie.
                .containsExactly("GET localhost:8081/api/books/?", "SELECT orders", "render");
        Queries.HotSpan statements = split.hotSpans().get(1);
        assertThat(statements.count()).isEqualTo(2);
        assertThat(statements.category()).isEqualTo("db");
        assertThat(statements.selfMs()).isEqualTo(20.0);
        assertThat(statements.share()).isEqualTo(0.2);
    }

    @Test
    void noSpansOrNoTopSpanTimeIsNoSplit() {
        assertThat(Queries.TimeSplit.of(List.of())).isEqualTo(Queries.TimeSplit.NONE);
        assertThat(Queries.TimeSplit.of(List.of(internal("a", null, "root", 0, 0))))
                .isEqualTo(Queries.TimeSplit.NONE);
    }

    @Test
    void theWaterfallPutsParentsBeforeChildrenAndSiblingsByStartThenId() {
        List<SpanRecord> sorted = Queries.sorted(List.of(
                internal("c2", "b", "second", 30, 5),
                internal("c1", "b", "first", 20, 5),
                internal("b", null, "root", 0, 100),
                internal("c0", "b", "tied", 30, 5),
                internal("o", "gone", "orphan", 10, 5)));

        assertThat(sorted).extracting(SpanRecord::spanId).containsExactly("b", "c1", "c0", "c2", "o");
    }
}
