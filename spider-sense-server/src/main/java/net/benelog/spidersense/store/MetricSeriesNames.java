package net.benelog.spidersense.store;

/**
 * The OpenTelemetry Java agent's stable JVM metric and attribute names.
 *
 * <p>Named here rather than written into the query code so that a rename in a
 * future semantic-conventions release is one edit, and so the JVM page says which
 * metrics it is curated from.
 */
public final class MetricSeriesNames {

    public static final String MEMORY_USED = "jvm.memory.used";
    public static final String MEMORY_COMMITTED = "jvm.memory.committed";
    public static final String MEMORY_LIMIT = "jvm.memory.limit";
    public static final String GC_DURATION = "jvm.gc.duration";
    public static final String THREAD_COUNT = "jvm.thread.count";
    public static final String CLASS_COUNT = "jvm.class.count";
    public static final String CPU_UTILIZATION = "jvm.cpu.recent_utilization";
    public static final String CPU_COUNT = "jvm.cpu.count";
    /** Not every platform reports a load average; the series may simply be absent. */
    public static final String SYSTEM_LOAD_1M = "jvm.system.cpu.load_1m";

    public static final String MEMORY_TYPE = "jvm.memory.type";
    public static final String POOL_NAME = "jvm.memory.pool.name";
    public static final String GC_NAME = "jvm.gc.name";
    public static final String GC_ACTION = "jvm.gc.action";
    public static final String THREAD_DAEMON = "jvm.thread.daemon";

    private MetricSeriesNames() {
    }
}
