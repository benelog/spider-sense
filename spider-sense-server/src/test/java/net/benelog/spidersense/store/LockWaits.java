package net.benelog.spidersense.store;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Waits for what a lock-ordering test means by "still waiting": a session of
 * the database blocked on a lock another one holds, as H2 reports it.
 *
 * <p>A fixed pause would pass for the wrong reason on a slow machine, where the
 * waiting thread has not reached the lock yet, and cost its length on a fast one.
 *
 * <p>H2 names the blocking session of a statement waiting for a row another
 * transaction has locked, such as an {@code UPDATE} or a {@code SELECT ... FOR
 * UPDATE}. A {@code MERGE} whose key another transaction has inserted and not
 * committed is not reported that way: it tries again in a loop, still running,
 * until that transaction ends or the lock timeout passes. For those the wait is
 * for the statement to be under way in another session.
 */
final class LockWaits {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LockWaits() {
    }

    /**
     * Returns once {@code sessions} sessions of the database wait for a lock.
     *
     * @throws AssertionError when that does not happen within ten seconds
     */
    static void awaitBlocked(Sql sql, int sessions) {
        awaitUntil(() -> blocked(sql) >= sessions, sessions + " session(s) blocked on a lock");
    }

    /**
     * Returns once another session is running a statement that starts with
     * {@code prefix}, which a {@code MERGE} waiting for an uncommitted key keeps doing.
     *
     * @throws AssertionError when that does not happen within ten seconds
     */
    static void awaitRunning(Sql sql, String prefix) {
        awaitUntil(() -> sql.count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"
                + " WHERE SESSION_ID <> SESSION_ID() AND EXECUTING_STATEMENT LIKE ?",
                List.of(prefix + "%")) > 0, "a session running " + prefix);
    }

    /**
     * Returns once {@code thread} waits to enter a monitor, such as the writer's
     * flush lock.
     *
     * @throws AssertionError when that does not happen within ten seconds
     */
    static void awaitBlocked(Thread thread) {
        awaitUntil(() -> thread.getState() == Thread.State.BLOCKED, thread.getName() + " blocked on a monitor");
    }

    private static long blocked(Sql sql) {
        return sql.count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",
                List.of());
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }
}
