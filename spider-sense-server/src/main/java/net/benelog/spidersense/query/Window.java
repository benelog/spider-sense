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
    private static final long DAY = 24 * HOUR;

    /** Widths a person reads without doing arithmetic. */
    private static final long[] NICE_WIDTHS = {
            SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND,
            MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
            HOUR, 2 * HOUR, 6 * HOUR, 12 * HOUR, DAY};

    public static final long DEFAULT_RANGE_MS = 15 * MINUTE;

    /**
     * The last instant a window may reach: the end of the year 9999. A time past it is
     * not a moment anyone means but a unit mistake, nanoseconds or microseconds pasted where
     * the API takes milliseconds.
     */
    public static final long LATEST = 253_402_300_799_999L;

    /** The most buckets {@link #bucketMs} lets a window of any length hold (api.adoc#conventions). */
    public static final int MAX_BUCKETS = 75;

    /**
     * The window from {@code from} to {@code to}, or to {@code from} when {@code to} is earlier.
     *
     * @throws Selectors.BadSelector when it starts before the epoch or ends past {@link #LATEST}:
     *         a {@code 400}, since its length would overflow or its buckets would not fit in
     *         memory
     */
    public static Window of(long from, long to) {
        long end = Math.max(from, to);
        if (from < 0) {
            throw new Selectors.BadSelector("from resolves to " + from + ", which is before the epoch");
        }
        if (end > LATEST) {
            throw new Selectors.BadSelector("to resolves to " + end + ", which is past the year 9999:"
                    + " times are epoch milliseconds, not microseconds or nanoseconds");
        }
        return new Window(from, end, bucketMs(end - from));
    }

    /**
     * The bucket width the API reports as {@code window.bucketMs}.
     *
     * <p>Past a range of 72 days even a day is too narrow, so the width grows by whole days
     * and the count stays under {@link #MAX_BUCKETS} however long the window is.
     */
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
        return (target + DAY - 1) / DAY * DAY;
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
        return Math.toIntExact(Math.max(1, span / bucketMs + 1));
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

    /**
     * The SQL that numbers the bucket a span starts in, to group by and to hand back
     * to {@link #slotOf}: the width is the window's own, so it is written as a literal.
     */
    public String bucketExpression() {
        return "start_ms / " + bucketMs;
    }

    /**
     * The index into {@link #bucketStarts} of a bucket numbered by
     * {@link #bucketExpression}, or -1 when it falls outside the window.
     */
    public int slotOf(long bucketNumber) {
        long slot = bucketNumber - alignedFrom() / bucketMs;
        return slot >= 0 && slot < bucketCount() ? (int) slot : -1;
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
