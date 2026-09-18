package worker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker with no web server.
 *
 * <p>Every other Spider Sense example answers HTTP requests, which makes the
 * questions about endpoints. This one answers nothing. It opens a pool, seeds a
 * database, and runs four scheduled jobs forever, so the questions are about
 * jobs, pools, threads and logs instead.
 */
public final class WorkerApp {

    private static final Logger log = LoggerFactory.getLogger(WorkerApp.class);

    private WorkerApp() {
    }

    public static void main(String[] args) {
        Settings settings = Settings.fromSystemProperties();
        if (Settings.once()) {
            Result result = runOnce(settings);
            log.info("Single pass done: {} reminders acknowledged, report {} bytes",
                    result.leakedThreads(), result.reportBytes());
            System.exit(0);
        }
        schedule(settings);
    }

    /** What one pass over every job left behind, for the tests to look at. */
    public record Result(long reportBytes, int leakedThreads) {
    }

    /**
     * Runs every job once, in order, on the calling thread, and closes everything.
     *
     * <p>This is the entry point the tests use, which is why it takes its
     * settings as an argument and never calls {@link System#exit}. A failure in
     * one job is logged and the next job still runs, exactly as the scheduler
     * would have it.
     */
    public static Result runOnce(Settings settings) {
        try (HikariDataSource pool = Database.pool(settings.jdbcUrl())) {
            Database.createSchema(pool);
            new Seeder(pool, settings.seedEvents()).seed();
            Jobs jobs = new Jobs(pool, new ReminderGateway(4711), settings);
            try {
                run("reconcile-balances", jobs::reconcileBalances);
                run("send-reminders", jobs::sendReminders);
                run("archive-events", jobs::archiveEvents);
                run("rebuild-report", jobs::rebuildReport);
                return new Result(jobs.lastReportBytes(), jobs.leakedThreads());
            } finally {
                jobs.close();
            }
        }
    }

    /** The real thing: two scheduler threads, four schedules, and no way out but Ctrl-C. */
    private static void schedule(Settings settings) {
        HikariDataSource pool = Database.pool(settings.jdbcUrl());
        Database.createSchema(pool);
        new Seeder(pool, settings.seedEvents()).seed();

        Jobs jobs = new Jobs(pool, new ReminderGateway(), settings);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, named("worker-scheduler-"));

        scheduler.scheduleAtFixedRate(guarded("reconcile-balances", jobs::reconcileBalances), 5, 15, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(guarded("send-reminders", jobs::sendReminders), 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(guarded("archive-events", jobs::archiveEvents), 0, 10, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(guarded("rebuild-report", jobs::rebuildReport), 10, 20, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Stopping the scheduler");
            scheduler.shutdownNow();
            jobs.close();
            pool.close();
        }, "worker-shutdown"));

        log.info("batch-worker is running: no HTTP port, only jobs "
                + "(reconcile-balances every 15s, send-reminders every 5s, archive-events every 10s, rebuild-report every 20s). "
                + "Ctrl-C to stop.");
    }

    /** A job that throws must not take the schedule with it, so the failure becomes a line in the log. */
    private static Runnable guarded(String name, Job job) {
        return () -> {
            try {
                job.run();
            } catch (Exception e) {
                log.error("Job {} failed", name, e);
            }
        };
    }

    private static void run(String name, Job job) {
        guarded(name, job).run();
    }

    /** A job body: anything may go wrong in one, and the wrapper is what catches it. */
    @FunctionalInterface
    private interface Job {
        void run() throws Exception;
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + counter.incrementAndGet());
    }
}
