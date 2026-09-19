package net.benelog.spidersense.store;

/**
 * One point of one series.
 *
 * <p>Gauges and sums fill {@code value}; histograms fill the rest. An
 * exponential histogram is kept as a histogram without buckets — count, sum, min
 * and max are what the UI draws, and reconstructing the exponential buckets to
 * throw them away again would be work for nothing.
 *
 * @param bucketCounts null unless this is a histogram with explicit buckets
 * @param bounds       the explicit bucket boundaries, one shorter than {@code bucketCounts}
 */
public record MetricPoint(
        long at,
        double value,
        long count,
        double sum,
        double min,
        double max,
        long[] bucketCounts,
        double[] bounds) {

    public static MetricPoint number(long at, double value) {
        return new MetricPoint(at, value, 0, 0, 0, 0, null, null);
    }

    public static MetricPoint histogram(long at, long count, double sum, double min, double max,
            long[] bucketCounts, double[] bounds) {
        double mean = count == 0 ? 0 : sum / count;
        return new MetricPoint(at, mean, count, sum, min, max, bucketCounts, bounds);
    }

    public boolean hasBuckets() {
        return bucketCounts != null && bounds != null && bucketCounts.length == bounds.length + 1;
    }

    /**
     * The value at a percentile, interpolated inside the bucket that crosses it.
     * Returns {@link Double#NaN} when the point carries no buckets to look in,
     * which includes a histogram whose one bucket has no bound: Micrometer's
     * bridged timers arrive that way, with every value in one bucket over
     * everything, and there is no width to interpolate inside.
     */
    public double percentile(double fraction) {
        if (!hasBuckets() || count == 0 || bounds.length == 0) {
            return Double.NaN;
        }
        double target = fraction * count;
        long cumulative = 0;
        for (int i = 0; i < bucketCounts.length; i++) {
            long inBucket = bucketCounts[i];
            if (cumulative + inBucket >= target && inBucket > 0) {
                double low = i == 0 ? Math.min(min, bounds[0]) : bounds[i - 1];
                double high = i == bounds.length ? Math.max(max, bounds[bounds.length - 1]) : bounds[i];
                double within = (target - cumulative) / inBucket;
                return low + (high - low) * Math.min(1.0, Math.max(0.0, within));
            }
            cumulative += inBucket;
        }
        return max;
    }
}
