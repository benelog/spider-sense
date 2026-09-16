package net.benelog.spidersense.query;

/**
 * The time window every read endpoint works in, and the buckets its charts use.
 *
 * <p>The bucket width follows from the range so the UI never has to ask for one:
 * fifteen minutes of traffic is readable at fifteen seconds a bucket, six hours
 * is not. Buckets are aligned to the epoch rather than to {@code from}, so the
 * same bucket holds the same seconds however the window was asked for and a
 * chart does not shimmer as the window slides.
 */
public record Window(long from, long to, long bucketMs) {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;

    /** Widths a person reads without doing arithmetic. */
    private static final long[] NICE_WIDTHS = {
            SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND,
            MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
            HOUR, 2 * HOUR, 6 * HOUR, 12 * HOUR, 24 * HOUR};

    public static final long DEFAULT_RANGE_MS = 15 * MINUTE;

    public static Window of(long from, long to) {
        long end = Math.max(from, to);
        return new Window(from, end, bucketMs(end - from));
    }

    /** The bucket width the API reports as {@code window.bucketMs}. */
    public static long bucketMs(long rangeMs) {
        if (rangeMs <= 5 * MINUTE) {
            return 5 * SECOND;
        }
        if (rangeMs <= 15 * MINUTE) {
            return 15 * SECOND;
        }
        if (rangeMs <= HOUR) {
            return MINUTE;
        }
        if (rangeMs <= 6 * HOUR) {
            return 5 * MINUTE;
        }
        long target = rangeMs / 72;
        for (long width : NICE_WIDTHS) {
            if (width >= target) {
                return width;
            }
        }
        return NICE_WIDTHS[NICE_WIDTHS.length - 1];
    }

    public long rangeMs() {
        return Math.max(0, to - from);
    }

    public double rangeSeconds() {
        return Math.max(1, rangeMs()) / 1000.0;
    }

    /** {@code from} rounded down to a bucket boundary. */
    public long alignedFrom() {
        return Math.floorDiv(from, bucketMs) * bucketMs;
    }

    public int bucketCount() {
        long span = to - alignedFrom();
        return (int) Math.max(1, span / bucketMs + 1);
    }

    /** The start of every bucket, oldest first — the {@code t} array of every series. */
    public long[] bucketStarts() {
        long start = alignedFrom();
        long[] starts = new long[bucketCount()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = start + i * bucketMs;
        }
        return starts;
    }

    /** The bucket an instant falls in, or -1 when it falls outside the window. */
    public int indexOf(long at) {
        long offset = at - alignedFrom();
        if (offset < 0) {
            return -1;
        }
        int index = (int) (offset / bucketMs);
        return index < bucketCount() ? index : -1;
    }
}
