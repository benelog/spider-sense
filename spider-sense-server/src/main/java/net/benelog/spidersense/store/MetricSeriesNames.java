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

    /**
     * The JDBC connection-pool metrics, in the spelling the agent still emits and
     * in the stable one that replaces it.
     *
     * <p>Both generations are read, the older one first, because which of them an
     * application reports depends on the agent it happens to run with.
     */
    public static final String POOL_CONNECTIONS = "db.client.connections.usage";
    public static final String POOL_CONNECTIONS_MAX = "db.client.connections.max";
    public static final String POOL_CONNECTIONS_PENDING = "db.client.connections.pending_requests";
    public static final String POOL_STATE = "state";
    public static final String POOL_CONNECTIONS_NAME = "pool.name";

    public static final String CONNECTION_COUNT = "db.client.connection.count";
    public static final String CONNECTION_MAX = "db.client.connection.max";
    public static final String CONNECTION_PENDING = "db.client.connection.pending_requests";
    public static final String CONNECTION_STATE = "db.client.connection.state";
    public static final String CONNECTION_POOL_NAME = "db.client.connection.pool.name";

    /** The two states a pooled connection is counted in. */
    public static final String STATE_USED = "used";
    public static final String STATE_IDLE = "idle";

    public static final String MEMORY_TYPE = "jvm.memory.type";
    public static final String POOL_NAME = "jvm.memory.pool.name";
    public static final String GC_NAME = "jvm.gc.name";
    public static final String GC_ACTION = "jvm.gc.action";
    public static final String THREAD_DAEMON = "jvm.thread.daemon";

    private MetricSeriesNames() {
    }
}
