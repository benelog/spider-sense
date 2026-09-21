package net.benelog.spidersense.store;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * Retention: rows older than {@code spidersense.retention.hours} go, and one row
 * cap guards the clock (storage.md, "Retention").
 *
 * <p>Time is the retention a person reasons about ("what happened this
 * afternoon"), so it decides first. The span cap only stops a load test from
 * turning a day of data into a file nobody wanted: after the time sweep, while
 * there are more than {@code spidersense.retention.spans} spans, the oldest hour
 * of everything goes, one hour at a time, so a cap crossed by a little costs a
 * little.
 *
 * <p>The cap is by rows rather than by file size because an H2 file does not
 * shrink when rows go: a size read after a delete would say the same number and
 * ask for the next hour, until nothing was left.
 *
 * <p>Daemon thread: in agent mode the monitored application's {@code main} must
 * still be able to return.
 */
public final class Sweeper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Sweeper.class.getName());
    private static final long FIRST_RUN_SECONDS = 60;
    private static final long INTERVAL_SECONDS = 300;
    private static final long HOUR_MS = 3_600_000L;

    /** storage.md: the cap the server keeps the file under when nobody says otherwise. */
    public static final long DEFAULT_RETENTION_SPANS = 1_000_000L;

    /**
     * At most two days of hours in one sweep. A pass that deletes nothing already
     * ends the loop; this is the second bound, so that a clock jump or a row with
     * an absurd timestamp can never make the sweeper spin.
     */
    private static final int MAX_PASSES = 48;

    /**
     * The time column each table is swept by.
     *
     * <p>{@code db_table} is in it because a catalog older than the retention
     * describes a run no window can show any more (storage.md): its indexes are
     * those of a schema that may since have changed.
     */
    private static final String[][] TABLE_AND_COLUMN = {
            {"span", "start_ms"}, {"trace", "start_ms"}, {"log", "at_ms"},
            {"metric_point", "at_ms"}, {"tingle", "at_ms"}, {"mark", "at_ms"},
            {"db_table", "seen_ms"}};

    /**
     * What the span cap deletes: everything the window shows, marks and catalog
     * rows excepted. A mark is a name a person gave a moment and is a row of
     * nothing, and a catalog row is one row per table; both go by the time
     * retention alone.
     */
    private static final String[][] CAPPED_TABLE_AND_COLUMN = {
            {"span", "start_ms"}, {"trace", "start_ms"}, {"log", "at_ms"},
            {"metric_point", "at_ms"}, {"tingle", "at_ms"}};

    private static final String ORPHAN_SERIES =
            "DELETE FROM metric_series WHERE id NOT IN (SELECT series_id FROM metric_point)";

    private final Sql sql;
    private final int retentionHours;
    private final long retentionSpans;
    private final ScheduledExecutorService scheduler;

    /** The cap at its documented default. */
    public Sweeper(Sql sql, int retentionHours) {
        this(sql, retentionHours, DEFAULT_RETENTION_SPANS);
    }

    /** @param retentionSpans the most {@code span} rows kept; {@code 0} or less is no cap */
    public Sweeper(Sql sql, int retentionHours, long retentionSpans) {
        this.sql = sql;
        this.retentionHours = retentionHours;
        this.retentionSpans = retentionSpans;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spider-sense-sweeper");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Sweeper start() {
        // The future is the handle of a task that never completes; sweepQuietly
        // already logs what it swallows, so there is nothing to check here.
        var unused = scheduler.scheduleWithFixedDelay(this::sweepQuietly, FIRST_RUN_SECONDS,
                INTERVAL_SECONDS, TimeUnit.SECONDS);
        return this;
    }

    /**
     * Deletes everything the retention rules no longer keep.
     *
     * @return the number of rows deleted
     */
    public int sweep() {
        long cutoff = System.currentTimeMillis() - retentionHours * HOUR_MS;
        int deleted = 0;
        for (String[] table : TABLE_AND_COLUMN) {
            deleted += sql.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " < ?",
                    List.of(cutoff));
        }
        deleted += sql.update(ORPHAN_SERIES, List.of());
        deleted += sweepToCap();
        return deleted;
    }

    /**
     * The span cap: while there are too many spans, the oldest hour goes.
     *
     * <p>The loop ends when the count is under the cap, when a pass deleted nothing
     * (an empty table, or every span inside the same hour) or after
     * {@value #MAX_PASSES} passes, whichever comes first.
     */
    private int sweepToCap() {
        if (retentionSpans <= 0) {
            return 0;
        }
        int deleted = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            long spans = sql.count("SELECT COUNT(*) FROM span", List.of());
            if (spans <= retentionSpans) {
                break;
            }
            Long oldest = oldestSpan();
            if (oldest == null) {
                break;
            }
            long cut = oldest + HOUR_MS;
            int pruned = 0;
            for (String[] table : CAPPED_TABLE_AND_COLUMN) {
                pruned += sql.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " < ?",
                        List.of(cut));
            }
            if (pruned == 0) {
                break;
            }
            pruned += sql.update(ORPHAN_SERIES, List.of());
            deleted += pruned;
            LOG.log(System.Logger.Level.INFO,
                    "Spider Sense span cap: " + spans + " spans is over " + retentionSpans
                            + ", deleted " + pruned + " rows older than " + cut);
        }
        return deleted;
    }

    /** {@code MIN(start_ms)}, or null when there is no span at all. */
    private @Nullable Long oldestSpan() {
        Long oldest = sql.queryOne("SELECT MIN(start_ms) FROM span", List.of(), rs -> rs.getLong(1));
        // MIN over an empty table is one row holding NULL, which JDBC reads as 0,
        // and no span ever started at the epoch: 0 is "there is no span".
        return oldest == null || oldest == 0 ? null : oldest;
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Spider Sense retention sweep failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
