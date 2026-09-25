package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.jspecify.annotations.Nullable;

/** The {@code trace} row as its spans make it, without a database. */
class TraceSummaryTest {

    private static final String TRACE = "a".repeat(32);
    private static final long AT_MS = 1_700_000_000_000L;
    private static final long AT_NS = AT_MS * 1_000_000L;

    private static TraceSummary.Span span(String id, @Nullable String parent, String service, long offsetNs,
            long durationNs) {
        return new TraceSummary.Span(TRACE, id, parent, service, "span " + id, null, "SERVER",
                (AT_NS + offsetNs) / 1_000_000L, AT_NS + offsetNs, durationNs, false, false, null);
    }

    @Test
    void theRootIsTheSpanWithoutAParent() {
        TraceSummary trace = TraceSummary.of(List.of(
                span("child", "root", "orders", 1_000, 10),
                span("root", null, "orders", 0, 100)), 500);

        assertThat(trace.rootSpanId()).isEqualTo("root");
        assertThat(trace.rootName()).isEqualTo("span root");
        assertThat(trace.spanCount()).isEqualTo(2);
    }

    /** The caller's spans have not arrived: the first span whose parent is missing stands in. */
    @Test
    void aSpanWhoseParentIsMissingIsARoot() {
        TraceSummary trace = TraceSummary.of(List.of(
                span("late", "gone", "bookstore", 5_000, 10),
                span("early", "gone", "bookstore", 1_000, 10),
                span("child", "early", "bookstore", 2_000, 10)), 500);

        assertThat(trace.rootSpanId()).isEqualTo("early");
    }

    @Test
    void ofTwoRootsTheEarlierOneWins() {
        TraceSummary trace = TraceSummary.of(List.of(
                span("second", null, "orders", 2_000, 10),
                span("first", null, "billing", 1_000, 10)), 500);

        assertThat(trace.rootSpanId()).isEqualTo("first");
        assertThat(trace.rootService()).isEqualTo("billing");
    }

    /** Every span names another as its parent: nothing is a root, and the first span stands in. */
    @Test
    void withoutARootTheFirstSpanStandsIn() {
        TraceSummary trace = TraceSummary.of(List.of(
                span("b", "a", "orders", 2_000, 10),
                span("a", "b", "orders", 1_000, 10)), 500);

        assertThat(trace.rootSpanId()).isEqualTo("b");
    }

    /** The endpoint names the root when it has one, as it names a request. */
    @Test
    void theRootIsNamedByItsEndpoint() {
        TraceSummary.Span root = new TraceSummary.Span(TRACE, "root", null, "orders", "GET", "GET /orders",
                "SERVER", AT_MS, AT_NS, 10, false, false, 200L);

        TraceSummary trace = TraceSummary.of(List.of(root), 500);

        assertThat(trace.rootName()).isEqualTo("GET /orders");
        assertThat(trace.httpStatus()).isEqualTo(200L);
    }

    /**
     * The duration is taken in nanoseconds, the end column in milliseconds from each
     * span's own millisecond start: a span starting 0.9 ms into a millisecond and
     * lasting 0.2 ms ends in the same millisecond it started, by that column.
     */
    @Test
    void theDurationIsInNanosecondsAndTheEndInMilliseconds() {
        TraceSummary trace = TraceSummary.of(List.of(
                span("root", null, "orders", 0, 1_000_000),
                span("child", "root", "orders", 900_000, 1_200_000)), 500);

        assertThat(trace.startMs()).isEqualTo(AT_MS);
        assertThat(trace.durationNs()).isEqualTo(2_100_000);
        assertThat(trace.endMs()).isEqualTo(AT_MS + 1);
    }

    @Test
    void errorsAndDatabaseSpansAreCounted() {
        TraceSummary.Span failed = new TraceSummary.Span(TRACE, "root", null, "orders", "GET", null, "SERVER",
                AT_MS, AT_NS, 10, true, false, 500L);
        TraceSummary.Span query = new TraceSummary.Span(TRACE, "db", "root", "orders", "SELECT", null, "CLIENT",
                AT_MS, AT_NS, 5, false, true, null);

        TraceSummary trace = TraceSummary.of(List.of(failed, query), 500);

        assertThat(trace.errorCount()).isEqualTo(1);
        assertThat(trace.dbCount()).isEqualTo(1);
        assertThat(trace.error()).isTrue();
    }

    /** The services are named once each, in order, and leave out the names past the column. */
    @Test
    void theServicesLeaveOutTheNamesPastTheirColumn() {
        List<TraceSummary.Span> spans = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String service = "%03d".formatted(i) + "s".repeat(197);
            spans.add(span("s" + i, i == 0 ? null : "s0", service, i, 10));
            spans.add(span("t" + i, "s0", service, i, 10));
        }

        TraceSummary trace = TraceSummary.of(spans, 500);

        assertThat(trace.services().length()).isLessThanOrEqualTo(TraceSummary.SERVICES_MAX);
        List<String> services = AttrJson.decodeStrings(trace.services());
        assertThat(services).hasSize(20).doesNotHaveDuplicates();
        assertThat(services.get(0)).startsWith("000");
    }

    @Test
    void aTraceIsSlowOnlyPastTheThreshold() {
        assertThat(TraceSummary.of(List.of(span("root", null, "orders", 0, 500_000_000L)), 500).slow())
                .as("at the threshold").isFalse();
        assertThat(TraceSummary.of(List.of(span("root", null, "orders", 0, 500_000_001L)), 500).slow())
                .as("a nanosecond past it").isTrue();
    }

    @Test
    void aTraceWithoutSpansHasNoSummary() {
        assertThatThrownBy(() -> TraceSummary.of(List.of(), 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
