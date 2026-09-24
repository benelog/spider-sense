package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** storage.adoc#ingest-cap: whole traces survive a burst, and the rest is counted. */
class IngestCapTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final IngestCap cap = new IngestCap(10L, clock::get);

    private static String traceId(int n) {
        return "%032x".formatted(n);
    }

    @Test
    void theCapIsTheCountAfterCountingSoTheTenthSpanOfASecondIsKept() {
        for (int n = 1; n <= 10; n++) {
            assertThat(cap.accept(traceId(n))).as("span " + n).isTrue();
        }
        assertThat(cap.accept(traceId(11))).as("the eleventh trace is new").isFalse();
        assertThat(cap.droppedSpans()).isEqualTo(1);
    }

    @Test
    void aSpanOfAnAlreadyAcceptedTraceIsKeptAboveTheCap() {
        for (int n = 1; n <= 30; n++) {
            cap.accept(traceId(n));
        }
        assertThat(cap.droppedSpans()).isEqualTo(20);

        for (int n = 1; n <= 10; n++) {
            assertThat(cap.accept(traceId(n))).as("trace " + n + " is already being stored").isTrue();
        }
        assertThat(cap.accept(traceId(31))).isFalse();
        assertThat(cap.droppedSpans()).isEqualTo(21);
    }

    @Test
    void theCounterStartsAgainEveryWallClockSecond() {
        for (int n = 1; n <= 30; n++) {
            cap.accept(traceId(n));
        }
        assertThat(cap.droppedSpans()).isEqualTo(20);

        clock.addAndGet(1000);

        assertThat(cap.accept(traceId(100))).isTrue();
        assertThat(cap.droppedSpans()).as("the new second starts from nothing").isEqualTo(20);
    }

    @Test
    void anUnsetOrZeroCapDropsNothing() {
        IngestCap none = IngestCap.none();
        IngestCap zero = new IngestCap(0L, clock::get);
        for (int n = 1; n <= 1000; n++) {
            assertThat(none.accept(traceId(n))).isTrue();
            assertThat(zero.accept(traceId(n))).isTrue();
        }
        assertThat(none.droppedSpans()).isZero();
        assertThat(none.maxSpansPerSecond()).isNull();
        assertThat(zero.droppedSpans()).isZero();
        assertThat(zero.maxSpansPerSecond()).as("0 is not a cap of zero, it is no cap").isNull();
    }
}
