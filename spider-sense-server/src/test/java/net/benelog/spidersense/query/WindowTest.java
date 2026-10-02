package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WindowTest {

    @Test
    void theBucketWidthFollowsFromTheRange() {
        assertThat(Window.bucketMs(60_000)).isEqualTo(5_000);
        assertThat(Window.bucketMs(5 * 60_000)).isEqualTo(5_000);
        assertThat(Window.bucketMs(15 * 60_000)).isEqualTo(15_000);
        assertThat(Window.bucketMs(60 * 60_000)).isEqualTo(60_000);
        assertThat(Window.bucketMs(6 * 3_600_000)).isEqualTo(300_000);
        assertThat(Window.bucketMs(24 * 3_600_000L)).isEqualTo(30 * 60_000L);
    }

    @Test
    void theBucketWidthKeepsWideningPastADaySoNoWindowHoldsMoreThanTheCap() {
        assertThat(Window.bucketMs(365 * 86_400_000L)).isEqualTo(6 * 86_400_000L);
        long[] ranges = {1, 59_999, 5 * 60_000 + 1, 6 * 3_600_000L + 1, 72 * 86_400_000L + 1,
                365 * 86_400_000L, Window.LATEST - 1, Window.LATEST};
        for (long range : ranges) {
            for (long from : new long[]{0, 7_777, 1_700_000_000_123L}) {
                long to = Math.min(Window.LATEST, from + range);
                Window window = Window.of(from, to);
                assertThat(window.bucketCount()).as("range %d from %d", range, from)
                        .isBetween(1, Window.MAX_BUCKETS);
                assertThat(window.bucketStarts()).hasSize(window.bucketCount());
            }
        }
    }

    @Test
    void aWindowBeforeTheEpochOrPastTheYear9999IsTheCallersMistake() {
        assertThatThrownBy(() -> Window.of(-1, 1_000))
                .isInstanceOf(Selectors.BadSelector.class).hasMessageContaining("before the epoch");
        assertThatThrownBy(() -> Window.of(Long.MIN_VALUE, Long.MAX_VALUE))
                .isInstanceOf(Selectors.BadSelector.class);
        // Nanoseconds pasted where milliseconds belong.
        assertThatThrownBy(() -> Window.of(1_790_940_000_000L, 1_790_940_000_050_000_000L))
                .isInstanceOf(Selectors.BadSelector.class).hasMessageContaining("milliseconds");
        assertThatThrownBy(() -> Window.of(0, 100_000_000_000_000_000L))
                .isInstanceOf(Selectors.BadSelector.class);
        assertThat(Window.of(0, Window.LATEST).to()).isEqualTo(Window.LATEST);
    }

    @Test
    void bucketsAreAlignedToTheEpochSoTheyDoNotShimmerAsTheWindowSlides() {
        Window window = Window.of(1_000_007_000L, 1_000_007_000L + 15 * 60_000L);

        assertThat(window.bucketMs()).isEqualTo(15_000);
        assertThat(window.alignedFrom()).isEqualTo(1_000_005_000L);
        assertThat(window.alignedFrom() % 15_000).isZero();
        assertThat(window.bucketStarts()[0]).isEqualTo(window.alignedFrom());
    }

    @Test
    void everyBucketStartIsOneWidthApartAndCoversTheWindow() {
        Window window = Window.of(1_700_000_000_000L, 1_700_000_000_000L + 10 * 60_000L);
        long[] starts = window.bucketStarts();

        assertThat(starts).hasSize(window.bucketCount());
        for (int i = 1; i < starts.length; i++) {
            assertThat(starts[i] - starts[i - 1]).isEqualTo(window.bucketMs());
        }
        assertThat(starts[starts.length - 1]).isGreaterThanOrEqualTo(window.to() - window.bucketMs());
    }

    @Test
    void anInstantMapsToItsBucketAndOutsideTheWindowToNothing() {
        Window window = Window.of(1_700_000_000_000L, 1_700_000_000_000L + 60_000L);

        assertThat(window.indexOf(window.alignedFrom())).isZero();
        assertThat(window.indexOf(window.alignedFrom() + window.bucketMs())).isEqualTo(1);
        assertThat(window.indexOf(window.alignedFrom() - 1)).isEqualTo(-1);
        assertThat(window.indexOf(window.to() + 10 * window.bucketMs())).isEqualTo(-1);
    }

    @Test
    void aSqlBucketNumberMapsToTheSlotItsInstantMapsTo() {
        Window window = Window.of(1_700_000_007_000L, 1_700_000_007_000L + 15 * 60_000L);

        assertThat(window.bucketExpression()).isEqualTo("start_ms / 15000");
        for (long at : new long[]{window.from(), window.alignedFrom(), window.from() + 123_456, window.to()}) {
            // What H2 computes for start_ms / 15000 over a positive start.
            long bucketNumber = at / window.bucketMs();
            assertThat(window.slotOf(bucketNumber)).isEqualTo(window.indexOf(at));
        }
        long first = window.alignedFrom() / window.bucketMs();
        assertThat(window.slotOf(first)).isZero();
        assertThat(window.slotOf(first - 1)).isEqualTo(-1);
        assertThat(window.slotOf(first + window.bucketCount() - 1)).isEqualTo(window.bucketCount() - 1);
        assertThat(window.slotOf(first + window.bucketCount())).isEqualTo(-1);
    }
}
