package net.benelog.spidersense.query;

/**
 * The SQL fragments over the {@code span} table more than one read writes: what a job
 * is, how errors are counted, the p95, and a duration past a threshold.
 *
 * <p>A definition the manual gives once, such as "a job is a root {@code INTERNAL}
 * span", is one string here rather than one per statement that needs it.
 */
final class SpanSql {

    /** A run of a job: a root {@code INTERNAL} span (design.adoc#endpoint-identity). */
    static final String JOB = job("");

    /** The failed spans of a group, as an aggregate. */
    static final String ERRORS = "SUM(CASE WHEN error THEN 1 ELSE 0 END)";

    /** The nearest-rank p95 of a group's durations, in nanoseconds. */
    static final String P95 = "PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns)";

    private SpanSql() {
    }

    /** {@link #JOB} with each column prefixed by a table alias, as {@code r.}. */
    static String job(String prefix) {
        return prefix + "parent_span_id IS NULL AND " + prefix + "kind = 'INTERNAL'";
    }

    /** {@code duration_ns} past a threshold given in milliseconds. */
    static String slowerThan(long ms) {
        return slowerThan("duration_ns", ms);
    }

    /**
     * A duration in nanoseconds, a column or an aggregate, past a threshold given in
     * milliseconds; the threshold is a server constant, so it is written as a literal.
     */
    static String slowerThan(String durationNs, long ms) {
        return durationNs + " > " + ms * 1_000_000L;
    }
}
