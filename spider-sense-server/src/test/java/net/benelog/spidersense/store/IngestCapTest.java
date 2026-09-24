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
    void aDroppedTraceStaysDroppedInTheNextSecond() {
        IngestCap one = new IngestCap(1L, clock::get);
        assertThat(one.accept(traceId(1))).isTrue();
        assertThat(one.accept(traceId(2))).as("trace 2's child is over the cap").isFalse();

        clock.addAndGet(1000);

        assertThat(one.accept(traceId(2))).as("trace 2's parent, a second later").isFalse();
        assertThat(one.droppedSpans()).isEqualTo(2);
        assertThat(one.accept(traceId(3))).as("the dropped span took nothing from the new second")
                .isTrue();
        assertThat(one.accept(traceId(1))).as("an accepted trace still adds spans above the cap")
                .isTrue();
    }

    @Test
    void aBurstOfDroppedTracesDoesNotPushOutTheAcceptedOnes() {
        for (int n = 1; n <= 10; n++) {
            cap.accept(traceId(n));
        }
        for (int n = 11; n <= 20_010; n++) {
            assertThat(cap.accept(traceId(n))).isFalse();
        }

        for (int n = 1; n <= 10; n++) {
            assertThat(cap.accept(traceId(n))).as("trace " + n + " is still being stored").isTrue();
        }
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
