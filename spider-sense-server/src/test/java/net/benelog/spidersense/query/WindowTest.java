package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

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
}
