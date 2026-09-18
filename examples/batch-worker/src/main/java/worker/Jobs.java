package worker;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The four jobs, and the four mistakes they make.
 *
 * <p>Every job is a public method annotated with {@link WithSpan} and called
 * from a scheduler thread with nothing above it, so under the OpenTelemetry
 * agent each run is a root {@code INTERNAL} span. Spider Sense calls such a
 * span a job, never a request: it is not an endpoint, not in the Apdex, and the
 * only finding that can name it is {@code slow-job}.
 */
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    /** How many slices {@code archive-events} cuts the id range into, against a pool of three. */
    public static final int ARCHIVE_SLICES = 8;
    /** How many overdrawn accounts one reminder run takes on. */
    public static final int REMINDER_BATCH = 20;

    private final DataSource dataSource;
    private final ReminderGateway gateway;
    private final Settings settings;
    private final ExecutorService archivePool;
    private final AtomicInteger leakedThreads = new AtomicInteger();
    private volatile long lastReportBytes;

    public Jobs(DataSource dataSource, ReminderGateway gateway, Settings settings) {
        this.dataSource = dataSource;
        this.gateway = gateway;
        this.settings = settings;
        this.archivePool = Executors.newFixedThreadPool(ARCHIVE_SLICES, named("archive-"));
    }

    /** The size of the last report, in bytes; 0 until {@code rebuild-report} has run. */
    public long lastReportBytes() {
        return lastReportBytes;
    }

    /** How many acknowledgement threads have been leaked so far. */
    public int leakedThreads() {
        return leakedThreads.get();
    }

    // ------------------------------------------------------------------
    // reconcile-balances
    // ------------------------------------------------------------------

    /**
     * Recomputes every balance from the events, the slowest way that still works.
     *
     * <p>The group-by scans all of {@code events}, because there is no index on
     * {@code account_id}. The write back is one {@code update} per account, sent
     * one at a time inside a single transaction: two thousand round trips where
     * one batch would do. Both halves are the point — this is the job that
     * earns {@code slow-job}.
     */
    @WithSpan("reconcile-balances")
    public void reconcileBalances() throws SQLException {
        long start = System.currentTimeMillis();
        Map<Integer, BigDecimal> sums = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select account_id, sum(amount) from events group by account_id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                sums.put(rows.getInt(1), rows.getBigDecimal(2));
            }
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "update accounts set balance = ? where id = ?")) {
            connection.setAutoCommit(false);
            for (Map.Entry<Integer, BigDecimal> account : sums.entrySet()) {
                update.setBigDecimal(1, account.getValue());
                update.setInt(2, account.getKey());
                update.executeUpdate();
            }
            connection.commit();
        }
        log.info("reconciled {} accounts in {} ms", sums.size(), System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------
    // send-reminders
    // ------------------------------------------------------------------

    /**
     * Mails the twenty most neglected overdrawn accounts, and never quite finishes.
     *
     * <p>Two things are wrong here on purpose. A send that fails is caught per
     * account and logged at ERROR, so the job itself succeeds and the span is
     * fine: only the log knows anything went wrong, which is exactly what a
     * {@code log-error} finding is for. And every send that succeeds starts a
     * non-daemon thread that waits for an acknowledgement nobody ever sends, so
     * the thread count climbs every five seconds until the cap.
     */
    @WithSpan("send-reminders")
    public void sendReminders() throws SQLException {
        record Target(long id, String name) {
        }
        List<Target> targets = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id, name from accounts where balance < 0 order by reminded_at nulls first limit "
                             + REMINDER_BATCH);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                targets.add(new Target(rows.getLong(1), rows.getString(2)));
            }
        }
        int sent = 0;
        int failed = 0;
        for (Target target : targets) {
            try {
                gateway.send(target.id(), target.name());
                markReminded(target.id());
                awaitAcknowledgement(target.id());
                sent++;
            } catch (IOException e) {
                failed++;
                log.error("Reminder to account {} failed", target.id(), e);
            }
        }
        log.info("sent {} reminders, {} failed, {} threads waiting for an acknowledgement",
                sent, failed, leakedThreads.get());
    }

    private void markReminded(long accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update accounts set reminded_at = ? where id = ?")) {
            statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    /**
     * Starts a thread that waits on a latch nobody counts down.
     *
     * <p>It is the leak a delivery callback becomes when the callback never
     * arrives. The cap keeps the JVM from running out of threads, so the
     * process survives to be measured.
     */
    private void awaitAcknowledgement(long accountId) {
        if (leakedThreads.get() >= settings.leakMax()) {
            return;
        }
        int n = leakedThreads.incrementAndGet();
        CountDownLatch acknowledgement = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                acknowledgement.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "reminder-ack-" + n);
        waiter.setDaemon(false);
        waiter.start();
    }

    // ------------------------------------------------------------------
    // archive-events
    // ------------------------------------------------------------------

    /**
     * Uploads the events to cold storage in eight parallel slices.
     *
     * <p>Eight tasks want a connection, the pool has three, and each task holds
     * the one it gets for a second or more while it pretends to upload. The
     * slices at the back of the queue wait, and the last of them sometimes wait
     * longer than the pool's two-second connection timeout and fail. Holding a
     * connection across a network call is the mistake; {@code pool-exhausted}
     * is what it looks like from the outside.
     */
    @WithSpan("archive-events")
    public void archiveEvents() throws Exception {
        long maxId = maxEventId();
        long sliceSize = Math.max(1, maxId / ARCHIVE_SLICES);
        List<Future<Void>> futures = new ArrayList<>(ARCHIVE_SLICES);
        for (int i = 0; i < ARCHIVE_SLICES; i++) {
            long from = 1 + i * sliceSize;
            long to = i == ARCHIVE_SLICES - 1 ? maxId : from + sliceSize - 1;
            futures.add(archivePool.submit(() -> {
                archiveSlice(from, to);
                return null;
            }));
        }
        Exception firstFailure = null;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (firstFailure == null) {
                    firstFailure = e.getCause() instanceof Exception cause ? cause : e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** One slice: take a connection, read it, then hold the connection while "uploading". */
    @WithSpan("archive-slice")
    public void archiveSlice(long from, long to) throws SQLException, InterruptedException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*), sum(amount) from events where id between ? and ?")) {
            statement.setLong(1, from);
            statement.setLong(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
            // The connection is still open. That is the mistake.
            Thread.sleep(settings.archiveHoldMs()
                    + ThreadLocalRandom.current().nextLong(settings.archiveHoldMs() + 1));
        }
    }

    private long maxEventId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select max(id) from events");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    // ------------------------------------------------------------------
    // rebuild-report
    // ------------------------------------------------------------------

    /**
     * Builds the whole CSV in memory and throws it away.
     *
     * <p>Nothing reads the report; the job exists to stream every row out of
     * the database and to hand the garbage collector a few tens of megabytes
     * every twenty seconds, so the JVM page has something to draw.
     */
    @WithSpan("rebuild-report")
    public void rebuildReport() throws SQLException {
        long start = System.currentTimeMillis();
        StringBuilder csv = new StringBuilder(1 << 20);
        csv.append("id,account_id,amount,kind,occurred_at\n");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id, account_id, amount, kind, occurred_at from events order by id")) {
            statement.setFetchSize(1_000);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    csv.append(rows.getLong(1)).append(',')
                            .append(rows.getInt(2)).append(',')
                            .append(rows.getBigDecimal(3)).append(',')
                            .append(rows.getString(4)).append(',')
                            .append(rows.getTimestamp(5)).append('\n');
                }
            }
        }
        lastReportBytes = csv.length();
        log.info("rebuilt the report: {} MB in {} ms",
                String.format("%.1f", lastReportBytes / (1024.0 * 1024.0)), System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------

    /** Stops the archive pool; the leaked threads are left where they are, on purpose. */
    public void close() {
        archivePool.shutdownNow();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
