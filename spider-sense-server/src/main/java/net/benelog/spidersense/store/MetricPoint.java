package net.benelog.spidersense.store;

import org.jspecify.annotations.Nullable;

/**
 * One point of one series.
 *
 * <p>Gauges and sums fill {@code value}; histograms fill the rest. An
 * exponential histogram is kept as a histogram without buckets — count, sum, min
 * and max are what the UI draws, and reconstructing the exponential buckets to
 * throw them away again would be work for nothing.
 *
 * @param min          a histogram's smallest value, NaN when the sender did not report it
 * @param max          a histogram's largest value, NaN when the sender did not report it
 * @param bucketCounts null unless this is a histogram with explicit buckets
 * @param bounds       the explicit bucket boundaries, one shorter than {@code bucketCounts}
 */
// Arrays rather than lists: a histogram's counts and bounds are read positionally
// and by index in percentile(), and boxing every bucket would buy nothing.
@SuppressWarnings("ArrayRecordComponent")
public record MetricPoint(
        long at,
        double value,
        long count,
        double sum,
        double min,
        double max,
        long @Nullable [] bucketCounts,
        double @Nullable [] bounds) {

    public static MetricPoint number(long at, double value) {
        return new MetricPoint(at, value, 0, 0, 0, 0, null, null);
    }

    public static MetricPoint histogram(long at, long count, double sum, double min, double max,
            long @Nullable [] bucketCounts, double @Nullable [] bounds) {
        double mean = count == 0 ? 0 : sum / count;
        return new MetricPoint(at, mean, count, sum, min, max, bucketCounts, bounds);
    }

    public boolean hasBuckets() {
        long[] counts = bucketCounts;
        double[] edges = bounds;
        return counts != null && edges != null && counts.length == edges.length + 1;
    }

    /**
     * The value at a percentile, interpolated inside the bucket that crosses it.
     * Returns {@link Double#NaN} when the point carries no buckets to look in,
     * which includes a histogram whose one bucket has no bound: Micrometer's
     * bridged timers arrive that way, with every value in one bucket over
     * everything, and there is no width to interpolate inside.
     */
    public double percentile(double fraction) {
        long[] counts = bucketCounts;
        double[] edges = bounds;
        if (counts == null || edges == null || counts.length != edges.length + 1
                || count == 0 || edges.length == 0) {
            return Double.NaN;
        }
        double target = fraction * count;
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            long inBucket = counts[i];
            if (cumulative + inBucket >= target && inBucket > 0) {
                // An unreported min or max (NaN) leaves the outer buckets at zero and the last bound.
                double low = i == 0 ? Math.min(Double.isNaN(min) ? 0 : min, edges[0]) : edges[i - 1];
                double high = i == edges.length
                        ? Math.max(Double.isNaN(max) ? edges[edges.length - 1] : max, edges[edges.length - 1])
                        : edges[i];
                double within = (target - cumulative) / inBucket;
                return low + (high - low) * Math.min(1.0, Math.max(0.0, within));
            }
            cumulative += inBucket;
        }
        return max;
    }
}
