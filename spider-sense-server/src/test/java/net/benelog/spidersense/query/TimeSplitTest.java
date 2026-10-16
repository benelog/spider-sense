package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    /** The spans with no parent among them: the top spans of one request read whole. */
    private static Predicate<SpanRecord> rootsOf(List<SpanRecord> spans) {
        Set<String> ids = new HashSet<>();
        for (SpanRecord span : spans) {
            ids.add(span.spanId());
        }
        return span -> span.parentSpanId() == null || !ids.contains(span.parentSpanId());
    }

    /** The split of spans whose top spans are the ones with no parent among them, and no entry span below. */
    private static Queries.TimeSplit split(List<SpanRecord> spans) {
        return Queries.TimeSplit.of(spans, rootsOf(spans), span -> false);
    }

    /** The self times of {@link #split}, by span id. */
    private static Map<String, Long> selfOf(List<SpanRecord> spans) {
        Map<String, Long> self = new HashMap<>();
        Queries.TimeSplit.selfUnderTheTops(spans, rootsOf(spans), span -> false)
                .forEach((span, nanos) -> self.put(span.spanId(), nanos));
        return self;
    }

    @Test
    void selfTimeIsTheDurationLessTheTimeTheDirectChildrenCover() {
        List<SpanRecord> spans = List.of(
                internal("a", null, "root", 0, 100),
                internal("b", "a", "work", 10, 60),
                internal("c", "b", "inner", 20, 30),
                // Async work that outlives its parent counts only the part inside it: 25 to 50.
                internal("d", "c", "async", 25, 50));

        Map<String, Long> self = selfOf(spans);

        assertThat(self).containsEntry("a", 40 * MS)
                .containsEntry("b", 30 * MS)
                .containsEntry("c", 5 * MS)
                .containsEntry("d", 25 * MS);
    }

    @Test
    void childrenThatRunAtOnceTakeTheirOverlapOnce() {
        // 10 to 70 and 20 to 80 cover 70 ms of the parent's 100, not 120.
        List<SpanRecord> spans = List.of(
                internal("p", null, "root", 0, 100),
                internal("a", "p", "left", 10, 60),
                internal("b", "p", "right", 20, 60),
                // Inside "a" altogether, so it covers nothing "a" has not.
                internal("c", "p", "nested", 30, 10),
                // Wholly after the parent ends: nothing of the parent's.
                internal("d", "p", "after", 150, 10));

        assertThat(selfOf(spans)).containsEntry("p", 30 * MS);
    }

    @Test
    void aChildWhoseParentWasNotReadTakesNothingFromAnyone() {
        List<SpanRecord> spans = List.of(
                internal("a", null, "root", 0, 100),
                internal("x", "gone", "orphan", 10, 40));

        assertThat(selfOf(spans)).containsEntry("a", 100 * MS).containsEntry("x", 40 * MS);
    }

    @Test
    void aSpanWhoseParentWasCutOffByTheReadIsATopSpan() {
        // "x" belongs to a parent another service owns: it is this service's own entry.
        Queries.TimeSplit split = split(List.of(
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

    /**
     * POST /orders publishes a message its own listener consumes: two requests of one
     * service in one trace, each the top span of its own split, and neither the other's.
     */
    private static List<SpanRecord> selfConsumed() {
        return List.of(
                span("post", null, "POST /orders", "SERVER", 0, 30, Map.of("http.request.method", "POST")),
                query("insert", "post", "orders", 2, 10),
                span("publish", "post", "orders publish", "PRODUCER", 15, 3,
                        Map.of("messaging.system", "kafka")),
                span("process", "publish", "orders process", "CONSUMER", 20, 900,
                        Map.of("messaging.system", "kafka")),
                query("update", "process", "orders", 21, 5),
                internal("mail", "process", "Mailer.send", 30, 880));
    }

    private static boolean isEntry(SpanRecord span) {
        return "SERVER".equals(span.kind()) || "CONSUMER".equals(span.kind());
    }

    @Test
    void anEntrySpanBelowTheTopSpanIsARequestOfItsOwn() {
        Queries.TimeSplit producer = Queries.TimeSplit.of(selfConsumed(),
                span -> span.spanId().equals("post"), TimeSplitTest::isEntry);
        Queries.TimeSplit consumer = Queries.TimeSplit.of(selfConsumed(),
                span -> span.spanId().equals("process"), TimeSplitTest::isEntry);

        // The request's own 30 ms: the insert, the publish, and its own code; not the listener's.
        assertThat(producer.breakdown().get("db")).isCloseTo(10.0 / 30, offset(1e-9));
        assertThat(producer.breakdown().get("internal")).isCloseTo(3.0 / 30, offset(1e-9));
        assertThat(producer.breakdown().get("self")).isCloseTo(17.0 / 30, offset(1e-9));
        assertThat(producer.hotSpans()).extracting(Queries.HotSpan::name)
                .containsExactly("SELECT orders", "orders publish");
        // The listener's 900 ms, which runs on after the publish ends and is not cut to it.
        assertThat(consumer.breakdown().get("db")).isCloseTo(5.0 / 900, offset(1e-9));
        assertThat(consumer.breakdown().get("internal")).isCloseTo(880.0 / 900, offset(1e-9));
        assertThat(consumer.breakdown().get("self")).isCloseTo(15.0 / 900, offset(1e-9));
        assertThat(consumer.hotSpans().get(0).name()).isEqualTo("Mailer.send");
        assertThat(consumer.hotSpans().get(0).selfMs()).isEqualTo(880.0);
    }

    /**
     * A traced load generator's request is a root {@code CLIENT} span with nothing under it in
     * its own service: its time is the wait on the call, not code of its own.
     */
    @Test
    void aTopSpanThatIsAnOutboundCallIsTheWaitOnTheCall() {
        List<SpanRecord> spans = List.of(call("get", null, "http://localhost:8082/api/report", 0, 805));

        Queries.TimeSplit split = Queries.TimeSplit.of(spans, span -> true, span -> true);

        assertThat(split.breakdown()).containsEntry("http", 1.0).containsEntry("self", 0.0);
        assertThat(split.hotSpans()).extracting(Queries.HotSpan::name)
                .containsExactly("GET localhost:8081/api/report");
        assertThat(split.hotSpans().get(0).selfMs()).isEqualTo(805.0);
    }

    /**
     * Two requests from an SDK that numbers its spans per trace, so both traces hold a
     * span 01 and a span 02: each trace's statement stays under its own request.
     */
    @Test
    void spanIdsThatRepeatAcrossTracesKeepEachTracesSpansInIt() {
        List<SpanRecord> spans = new ArrayList<>();
        for (long at : new long[] {0, 10_000}) {
            String trace = "t" + at;
            spans.add(new SpanRecord(trace, "01", null, "orders", "GET /x", "SERVER", at * MS,
                    (at + 1000) * MS, "UNSET", null, Map.of("http.request.method", "GET"), List.of(), "test"));
            spans.add(new SpanRecord(trace, "02", "01", "orders", "SELECT item", "CLIENT", (at + 50) * MS,
                    (at + 950) * MS, "UNSET", null,
                    Map.of("db.system", "h2", "db.operation", "SELECT", "db.sql.table", "item"), List.of(), "test"));
        }

        Queries.TimeSplit split = Queries.TimeSplit.of(spans, span -> span.spanId().equals("01"),
                TimeSplitTest::isEntry);

        assertThat(split.breakdown().get("db")).isCloseTo(0.9, offset(1e-9));
        assertThat(split.breakdown().get("self")).isCloseTo(0.1, offset(1e-9));
        assertThat(split.hotSpans().get(0).selfMs()).isEqualTo(1800.0);
        assertThat(split.hotSpans().get(0).count()).isEqualTo(2);
    }

    @Test
    void theSharesSumToOneAndArePrintedInTheirOrder() {
        Queries.TimeSplit split = split(List.of(
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
    void aChildThatOutlivesItsParentCountsOnlyTheTimeInsideIt() {
        // An @Async audit started at 50 ms runs 300 ms, long past the response at 100 ms.
        Queries.TimeSplit split = split(List.of(
                internal("a", null, "GET /orders", 0, 100),
                query("q", "a", "orders", 10, 20),
                internal("x", "a", "OrderService.audit", 50, 300),
                // Its own child outlives it too; only 90 to 100 is inside the request.
                query("y", "x", "audit_log", 90, 400)));

        assertThat(sum(split)).isCloseTo(1.0, offset(1e-9));
        assertThat(split.breakdown()).containsEntry("db", 0.3).containsEntry("internal", 0.4)
                .containsEntry("self", 0.3);
        assertThat(split.hotSpans().get(0).name()).isEqualTo("OrderService.audit");
        assertThat(split.hotSpans().get(0).selfMs()).isEqualTo(40.0);
        assertThat(split.hotSpans().get(0).share()).isEqualTo(0.4);
    }

    @Test
    void childrenThatRunAtOnceStillSplitTheTimeIntoSharesThatSumToOne() {
        // Two calls at once: 100 ms of calls in 50 ms of the request, and 50 ms of its own.
        Queries.TimeSplit split = split(List.of(
                internal("a", null, "GET /orders", 0, 100),
                call("h1", "a", "http://localhost:8081/api/books/1", 10, 50),
                call("h2", "a", "http://localhost:8081/api/books/2", 10, 50)));

        assertThat(sum(split)).isCloseTo(1.0, offset(1e-9));
        assertThat(split.breakdown().get("http")).isCloseTo(2.0 / 3, offset(1e-9));
        assertThat(split.breakdown().get("self")).isCloseTo(1.0 / 3, offset(1e-9));
        assertThat(split.hotSpans().get(0).share()).isCloseTo(2.0 / 3, offset(1e-9));
    }

    private static double sum(Queries.TimeSplit split) {
        double sum = 0;
        for (double share : split.breakdown().values()) {
            sum += share;
        }
        return sum;
    }

    @Test
    void theHotSpansAreTheLargestSelfTimesByNameWithTiesByName() {
        Queries.TimeSplit split = split(List.of(
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
        assertThat(split(List.of())).isEqualTo(Queries.TimeSplit.NONE);
        assertThat(split(List.of(internal("a", null, "root", 0, 0))))
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

    @Test
    void aParentCycleEntersTheOrderAtItsFirstMember() {
        List<SpanRecord> sorted = Queries.sorted(List.of(
                internal("r", null, "root", 0, 100),
                // Its own parent, with a child under it.
                internal("l", "l", "loop", 10, 20),
                internal("k", "l", "under loop", 12, 5),
                // Each other's parent; "z" hangs under "x" and starts before both, but is not
                // on the cycle, so the cycle's first member "y" is the root.
                internal("x", "y", "x", 35, 10),
                internal("y", "x", "y", 32, 5),
                internal("z", "x", "z", 30, 2)));

        assertThat(sorted).extracting(SpanRecord::spanId).containsExactly("r", "l", "k", "y", "x", "z");
    }

    @Test
    void aChainDeeperThanTheCallStackGoesIsOrderedAllTheSame() throws InterruptedException {
        // A recursive method under @WithSpan: one span per level, 20,000 levels.
        int depth = 20_000;
        List<SpanRecord> spans = new ArrayList<>();
        for (int i = depth - 1; i >= 0; i--) {
            spans.add(internal("s" + i, i == 0 ? null : "s" + (i - 1), "level", i, depth - i));
        }

        List<SpanRecord> sorted = onASmallStack(() -> Queries.sorted(spans));

        assertThat(sorted).hasSize(depth);
        for (int i = 0; i < depth; i++) {
            assertThat(sorted.get(i).spanId()).isEqualTo("s" + i);
        }
    }

    /** What {@code work} returns on a thread of 256 KiB stack, a quarter of a Jetty worker's. */
    static <T> T onASmallStack(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }
}
