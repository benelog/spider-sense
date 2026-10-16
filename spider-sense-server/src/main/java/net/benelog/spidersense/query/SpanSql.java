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

    /**
     * Not a span of an ignored endpoint's request: neither the ignored entry span itself nor a
     * span of a trace whose root is one (configuration.adoc#ignored-endpoints).
     *
     * <p>The root is the trace summary's, one seek away, so the condition costs two index
     * lookups a row rather than a scan of the trace; that is why it sits on the reads of the
     * rules, whose rows are failures, outbound calls, log records and the statements the
     * {@code slow-query} rule and {@code check} judge, and not on every aggregate of the pages.
     */
    static final String NOT_IGNORED = "NOT (" + ignored("") + ") AND NOT EXISTS (SELECT 1 FROM trace t"
            + " JOIN span r ON r.trace_id = t.trace_id AND r.span_id = t.root_span_id"
            + " WHERE t.trace_id = span.trace_id AND " + ignored("r.") + ")";

    /** The p95 of a group's durations, in nanoseconds: one of them, as {@link Queries#percentile} picks it. */
    static final String P95 = "PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns)";

    private SpanSql() {
    }

    /** {@link #JOB} with each column prefixed by a table alias, as {@code r.}. */
    static String job(String prefix) {
        return prefix + "parent_span_id IS NULL AND " + prefix + "kind = 'INTERNAL'";
    }

    /**
     * An entry span that {@code spidersense.ignore.endpoints} took out: one {@code SpanRecord#isEntry}
     * calls an entry span and the store wrote with {@code entry} false, which only the ignore
     * list does (design.adoc#endpoint-identity).
     */
    static String ignored(String prefix) {
        return "NOT " + prefix + "entry AND (" + prefix + "kind IN ('SERVER', 'CONSUMER') OR ("
                + prefix + "parent_span_id IS NULL AND " + prefix + "kind IN ('CLIENT', 'PRODUCER') AND "
                + prefix + "db_system IS NULL))";
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
