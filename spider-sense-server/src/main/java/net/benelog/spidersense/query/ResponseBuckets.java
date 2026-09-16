package net.benelog.spidersense.query;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The response-time scale every page shares: the bucket bounds, the histogram
 * counted against them, and the Apdex that histogram implies.
 *
 * <p>The bounds are {@code [T/4, T, 4T]} around the server's slow-request
 * threshold, so the four buckets read as "fast", "still within the threshold",
 * "tolerable" and "frustrating" — Pinpoint's response summary.
 * They come from the server rather than from the client so that every service,
 * every endpoint and the legend built from {@code /api/status} share one scale.
 *
 * <p>The histogram is five counts: the non-error requests of each bucket, then
 * the errors, whatever their duration. The five sum to the request count.
 */
public record ResponseBuckets(long slowRequestMs) {

    /** The four duration buckets plus the error slot. */
    public static final int SLOTS = 5;

    /** The three bounds in milliseconds, as {@code /api/status} reports them. */
    public long[] bounds() {
        return new long[]{slowRequestMs / 4, slowRequestMs, slowRequestMs * 4};
    }

    private long[] boundsNanos() {
        long[] bounds = bounds();
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] *= 1_000_000L;
        }
        return bounds;
    }

    /**
     * The four counting columns to add to an aggregate over spans, aliased
     * {@code h0} to {@code h3}.
     *
     * <p>The bounds are server constants rather than request parameters, so they
     * are written into the statement as literals, the way the slow-query threshold
     * already is.
     */
    public String columns() {
        long[] ns = boundsNanos();
        return "SUM(CASE WHEN NOT error AND duration_ns <= " + ns[0] + " THEN 1 ELSE 0 END) AS h0,"
                + " SUM(CASE WHEN NOT error AND duration_ns > " + ns[0]
                + " AND duration_ns <= " + ns[1] + " THEN 1 ELSE 0 END) AS h1,"
                + " SUM(CASE WHEN NOT error AND duration_ns > " + ns[1]
                + " AND duration_ns <= " + ns[2] + " THEN 1 ELSE 0 END) AS h2,"
                + " SUM(CASE WHEN NOT error AND duration_ns > " + ns[2] + " THEN 1 ELSE 0 END) AS h3";
    }

    /** The five counts of one aggregate row; the error count is the row's own. */
    public static long[] histogram(ResultSet rs, long errors) throws SQLException {
        return new long[]{rs.getLong("h0"), rs.getLong("h1"), rs.getLong("h2"), rs.getLong("h3"),
                errors};
    }

    /**
     * Satisfied up to {@code T}, tolerating up to {@code 4T} at half weight,
     * errors frustrated; {@code null} rather than zero when nothing was requested.
     */
    public static Double apdex(long[] histogram, long requests) {
        if (requests <= 0) {
            return null;
        }
        return (histogram[0] + histogram[1] + histogram[2] / 2.0) / requests;
    }

    public static long[] empty() {
        return new long[SLOTS];
    }

    /** One array of counts per duration bucket; the errors of a time bucket are its own. */
    public static long[][] emptySeries(int points) {
        return new long[SLOTS - 1][points];
    }
}
