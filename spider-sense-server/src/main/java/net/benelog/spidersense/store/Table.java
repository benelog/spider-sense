package net.benelog.spidersense.store;

import org.jspecify.annotations.Nullable;

/**
 * The tables {@link Schema} creates, apart from {@code meta}, and what each of the
 * store's deletes does to them.
 *
 * <p>Which tables a schema bump drops, which the retention sweeps by age, which
 * the span cap prunes and which {@code DELETE /api/data} empties are answered here
 * together (storage.adoc#retention), so a table a new version adds is one
 * constant rather than an entry in four lists that must be kept in step.
 *
 * <p>The order is the order of every loop over them: a table is dropped, swept
 * and emptied after the ones before it, which matters where one delete reads
 * another table, as {@code trace}'s empty does {@code span}.
 */
enum Table {

    SPAN("span", "start_ms", true, "DELETE FROM span"),
    TRACE("trace", "start_ms", true, "DELETE FROM trace WHERE trace_id NOT IN (SELECT trace_id FROM span)"),
    LOG("log", "at_ms", true, "DELETE FROM log"),
    METRIC_POINT("metric_point", "at_ms", true, "DELETE FROM metric_point"),
    /** Swept by the orphan sweep once its points are gone, never by age or by a statement of its own. */
    METRIC_SERIES("metric_series", null, false, null),
    /** Metadata, describing an instrument: neither the retention nor {@code DELETE /api/data} deletes it. */
    METRIC("metric", null, false, null),
    TINGLE("tingle", "at_ms", true, "DELETE FROM tingle"),
    /**
     * A name a person gave a moment, and a row of nothing, so the span cap leaves
     * it; each service's newest start mark outlives the retention and the empty,
     * since {@code since=start} needs it for as long as that process runs.
     */
    MARK("mark", "at_ms", false, "DELETE FROM mark o WHERE " + Marks.NOT_NEWEST_START) {
        @Override
        String deleteOlder() {
            return "DELETE FROM mark o WHERE o.at_ms < ? AND " + Marks.NOT_NEWEST_START;
        }
    },
    /** Never swept by time, since a known finding stays known, but emptied with everything else. */
    ACK("ack", null, false, "DELETE FROM ack"),
    /**
     * A catalog older than the retention describes a run no window can show any
     * more; one row per table, so the span cap leaves it.
     */
    DB_TABLE("db_table", "seen_ms", false, "DELETE FROM db_table"),
    /** Metadata, describing a service: neither the retention nor {@code DELETE /api/data} deletes it. */
    SERVICE("service", null, false, null);

    private final String sqlName;
    private final @Nullable String timeColumn;
    private final boolean cappedBySpans;
    private final @Nullable String clear;

    /**
     * @param timeColumn    the column the retention sweeps the table by, or null when it does not
     * @param cappedBySpans whether the span cap prunes the table's oldest hour along with the spans'
     * @param clear         what {@code DELETE /api/data} runs on it, or null when it leaves it alone
     */
    Table(String sqlName, @Nullable String timeColumn, boolean cappedBySpans, @Nullable String clear) {
        this.sqlName = sqlName;
        this.timeColumn = timeColumn;
        this.cappedBySpans = cappedBySpans;
        this.clear = clear;
    }

    /** The table's name in SQL. */
    String sqlName() {
        return sqlName;
    }

    /** Whether the retention deletes the table's rows by age. */
    boolean sweptByAge() {
        return timeColumn != null;
    }

    /** Whether the span cap prunes the table's oldest hour. */
    boolean cappedBySpans() {
        return cappedBySpans;
    }

    /** What {@code DELETE /api/data} runs on the table, or null when it leaves it alone. */
    @Nullable String clear() {
        return clear;
    }

    /**
     * The delete of the rows older than one parameter, a cutoff in epoch milliseconds.
     *
     * @throws IllegalStateException for a table that is not swept by age
     */
    String deleteOlder() {
        if (timeColumn == null) {
            throw new IllegalStateException(sqlName + " is not swept by age");
        }
        return "DELETE FROM " + sqlName + " WHERE " + timeColumn + " < ?";
    }
}
