package net.benelog.spidersense.store;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Retention: rows older than {@code spidersense.retention.hours} go.
 *
 * <p>Time is the only cap. The earlier design counted spans, which meant a busy
 * minute could push out an hour that was still on screen; a clock is what a person
 * reasons about ("what happened this afternoon"), and at a few requests a second
 * a day of data is a file in the low hundreds of megabytes.
 *
 * <p>Daemon thread: in agent mode the monitored application's {@code main} must
 * still be able to return.
 */
public final class Sweeper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Sweeper.class.getName());
    private static final long FIRST_RUN_SECONDS = 60;
    private static final long INTERVAL_SECONDS = 300;

    /** The time column each table is swept by. */
    private static final String[][] TABLE_AND_COLUMN = {
            {"span", "start_ms"}, {"trace", "start_ms"}, {"log", "at_ms"},
            {"metric_point", "at_ms"}, {"tingle", "at_ms"}};

    private final Sql sql;
    private final int retentionHours;
    private final ScheduledExecutorService scheduler;

    public Sweeper(Sql sql, int retentionHours) {
        this.sql = sql;
        this.retentionHours = retentionHours;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spider-sense-sweeper");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Sweeper start() {
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, FIRST_RUN_SECONDS, INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        return this;
    }

    /** @return the number of rows deleted */
    public int sweep() {
        long cutoff = System.currentTimeMillis() - retentionHours * 3600_000L;
        int deleted = 0;
        for (String[] table : TABLE_AND_COLUMN) {
            deleted += sql.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " < ?",
                    List.of(cutoff));
        }
        deleted += sql.update(
                "DELETE FROM metric_series WHERE id NOT IN (SELECT series_id FROM metric_point)", List.of());
        return deleted;
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
