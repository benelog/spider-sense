package net.benelog.spidersense.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The percentile a histogram point estimates from its buckets. */
class MetricPointTest {

    @Test
    void percentileInterpolatesInsideTheBucketThatCrossesIt() {
        MetricPoint point = MetricPoint.histogram(0, 10, 100, 1, 50,
                new long[] {2, 6, 2}, new double[] {5, 20});
        // 95% of 10 is 9.5: past the first two buckets (8), 1.5 of 2 into the last one,
        // which runs from 20 up to the max.
        assertEquals(20 + (50 - 20) * 0.75, point.percentile(0.95), 1e-9);
    }

    @Test
    void aBucketWithoutBoundsHasNoPercentile() {
        // Micrometer's bridged timers: one bucket over everything, no bound to interpolate in.
        MetricPoint point = MetricPoint.histogram(0, 3, 0.058, 0.001, 0.054, new long[] {3}, new double[0]);
        assertTrue(point.hasBuckets());
        assertTrue(Double.isNaN(point.percentile(0.95)));
    }

    @Test
    void anEmptyPointHasNoPercentile() {
        MetricPoint point = MetricPoint.histogram(0, 0, 0, 0, 0, new long[] {0, 0}, new double[] {1});
        assertTrue(Double.isNaN(point.percentile(0.5)));
    }

    /** Min and max are optional in OTLP; without them the outer buckets run from 0 and to the last bound. */
    @Test
    void anUnreportedMinAndMaxLeaveTheOuterBucketsAtZeroAndTheLastBound() {
        MetricPoint point = MetricPoint.histogram(0, 10, 100, Double.NaN, Double.NaN,
                new long[] {2, 6, 2}, new double[] {5, 20});
        assertEquals(20, point.percentile(0.95), 1e-9);
        assertEquals(5 * 0.5, point.percentile(0.1), 1e-9);
    }
}
