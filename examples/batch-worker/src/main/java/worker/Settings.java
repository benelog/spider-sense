package worker;

/**
 * Everything the worker reads from the outside, in one place.
 *
 * <p>The application builds it from system properties; the tests build it by
 * hand and hand it to {@link WorkerApp#runOnce(Settings)}, which is why nothing
 * here reads a property on its own.
 *
 * @param jdbcUrl        where the events live
 * @param seedEvents     how many events the first start writes
 * @param leakMax        how many acknowledgement threads {@code send-reminders} may leak before it stops leaking;
 *                       0 leaks none, which is what a test JVM wants
 * @param archiveHoldMs  the shortest time an archive slice holds its connection while it "uploads"; the longest is twice it
 */
public record Settings(String jdbcUrl, int seedEvents, int leakMax, long archiveHoldMs) {

    public static final String DEFAULT_URL = "jdbc:h2:~/db/spider-sense/worker;AUTO_SERVER=TRUE";
    public static final int DEFAULT_EVENTS = 300_000;
    public static final int DEFAULT_LEAK_MAX = 150;
    public static final long DEFAULT_ARCHIVE_HOLD_MS = 800;

    /** The settings of a real run: {@code worker.db}, {@code worker.seed.events}, {@code worker.leak.max}, {@code worker.archive.holdMs}. */
    public static Settings fromSystemProperties() {
        return new Settings(
                System.getProperty("worker.db", DEFAULT_URL),
                Integer.getInteger("worker.seed.events", DEFAULT_EVENTS),
                Integer.getInteger("worker.leak.max", DEFAULT_LEAK_MAX),
                Long.getLong("worker.archive.holdMs", DEFAULT_ARCHIVE_HOLD_MS));
    }

    /** True when {@code -Dworker.once=true} asked for a single pass over every job. */
    public static boolean once() {
        return Boolean.getBoolean("worker.once");
    }
}
