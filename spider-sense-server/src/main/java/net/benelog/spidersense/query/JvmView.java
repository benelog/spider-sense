package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.DoubleBinaryOperator;

import net.benelog.spidersense.query.MetricQueries.SeriesData;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.MetricSeriesNames;
import net.benelog.spidersense.store.ServiceInfo;
import org.jspecify.annotations.Nullable;

/**
 * The curated JVM page, assembled from the OpenTelemetry Java agent's stable
 * runtime metrics.
 *
 * <p>Those metrics arrive split by pool, by thread state, by collector. Nobody
 * wants to read eight Eden/Survivor/Old series to answer "is the heap filling
 * up", so the pools are summed into heap and non-heap totals here, once, and the
 * per-pool detail is offered beside them rather than instead of them.
 *
 * <p>Everything is empty rather than absent when a service sent no JVM metrics:
 * the UI draws empty charts, which is the honest answer for a service that is not
 * a JVM.
 *
 * <p>A series is one process's (storage.adoc#writer), so a service that two processes
 * export at once, an application and its tests, is the sum of the two: each
 * process's collections are differenced on their own, and each process's gauges count
 * at every instant of either with their latest value (api.adoc#jvm).
 */
public record JvmView(@Nullable String service, Runtime runtime, Memory heap, Memory nonHeap,
        List<Pool> pools,
        List<Gc> gc, Threads threads, Cpu cpu, Classes classes,
        List<ConnectionPool> connectionPools) {

    // Runtime is the name api.adoc#jvm gives this object and the UI reads; renaming it to
    // avoid the clash with java.lang.Runtime would change the published shape.
    @SuppressWarnings("AvoidCommonTypeNames")
    public record Runtime(@Nullable String jvm, @Nullable Long pid, @Nullable String host,
            @Nullable Long cpuCount) {
    }

    /** {@code limit} is empty for non-heap, which has none. */
    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Memory(long[] t, double[] used, double[] committed, double[] limit) {
    }

    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Pool(String name, @Nullable String type, long[] t, double[] used) {
    }

    /** Per bucket rather than per point: a cumulative histogram's deltas are what a chart shows. */
    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Gc(@Nullable String name, @Nullable String action, long[] t, long[] count,
            double[] durationMs) {
    }

    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Threads(long[] t, double[] count, double[] daemon) {
    }

    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Cpu(long[] t, double[] utilization, double[] systemLoad1m) {
    }

    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record Classes(long[] t, double[] loaded) {
    }

    /** One JDBC pool; {@code max} and {@code pending} are null per point when unreported. */
    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (api.adoc).
    @SuppressWarnings("ArrayRecordComponent")
    public record ConnectionPool(String name, long[] t, double[] used, double[] idle, double[] max,
            double[] pending) {
    }

    private static final long[] NO_TIME = {};
    private static final double[] NO_VALUES = {};

    public static JvmView of(MetricQueries metrics, @Nullable ServiceInfo service, String name,
            Window window) {
        List<SeriesData> used = metrics.series(MetricSeriesNames.MEMORY_USED, name, Map.of(), window);
        List<SeriesData> committed = metrics.series(MetricSeriesNames.MEMORY_COMMITTED, name, Map.of(), window);
        List<SeriesData> limit = metrics.series(MetricSeriesNames.MEMORY_LIMIT, name, Map.of(), window);

        return new JvmView(name,
                runtime(metrics, service, name, window),
                memory(used, committed, limit, "heap"),
                memory(used, committed, List.of(), "non_heap"),
                pools(used),
                gc(metrics.series(MetricSeriesNames.GC_DURATION, name, Map.of(), window), window),
                threads(metrics.series(MetricSeriesNames.THREAD_COUNT, name, Map.of(), window)),
                cpu(metrics.series(MetricSeriesNames.CPU_UTILIZATION, name, Map.of(), window),
                        metrics.series(MetricSeriesNames.SYSTEM_LOAD_1M, name, Map.of(), window)),
                classes(metrics.series(MetricSeriesNames.CLASS_COUNT, name, Map.of(), window)),
                connectionPools(metrics, name, window));
    }

    private static Runtime runtime(MetricQueries metrics, @Nullable ServiceInfo service, String name,
            Window window) {
        Map<String, Object> resource = service == null ? Map.of() : service.resource();
        String runtimeName = string(resource, "process.runtime.name");
        String version = string(resource, "process.runtime.version");
        String description = string(resource, "process.runtime.description");
        String jvm = runtimeName != null && version != null ? runtimeName + " " + version : description;
        MetricPoint cpuCount = metrics.latest(MetricSeriesNames.CPU_COUNT, name, window);
        return new Runtime(jvm, number(resource, "process.pid"), string(resource, "host.name"),
                cpuCount == null ? null : (long) cpuCount.value());
    }

    private static Memory memory(List<SeriesData> used, List<SeriesData> committed,
            List<SeriesData> limit, String type) {
        Map<String, String> filter = Map.of(MetricSeriesNames.MEMORY_TYPE, type);
        TreeMap<Long, Double> usedByTime = sum(matching(used, filter));
        TreeMap<Long, Double> committedByTime = sum(matching(committed, filter));
        TreeMap<Long, Double> limitByTime = sum(matching(limit, filter));
        long[] t = timeline(usedByTime, committedByTime, limitByTime);
        return new Memory(t, align(usedByTime, t), align(committedByTime, t),
                limitByTime.isEmpty() ? NO_VALUES : align(limitByTime, t));
    }

    /**
     * The heap of one service, summed over its pools.
     *
     * <p>Public for the same reason {@link #connectionPools} is: a
     * {@code heap-pressure} finding asks the JVM page's question of the JVM page's
     * series (findings.adoc#heap-pressure), and two readings of "how full is the heap" would be two
     * answers.
     */
    public static Memory heap(MetricQueries metrics, String service, Window window) {
        return memory(metrics.series(MetricSeriesNames.MEMORY_USED, service, Map.of(), window),
                metrics.series(MetricSeriesNames.MEMORY_COMMITTED, service, Map.of(), window),
                metrics.series(MetricSeriesNames.MEMORY_LIMIT, service, Map.of(), window), "heap");
    }

    /** The thread counts of one service; a {@code thread-growth} finding reads these. */
    public static Threads threads(MetricQueries metrics, String service, Window window) {
        return threads(metrics.series(MetricSeriesNames.THREAD_COUNT, service, Map.of(), window));
    }

    /** One entry per pool, sorted by name, with the processes that report it added as the heap's are. */
    private static List<Pool> pools(List<SeriesData> used) {
        Map<String, SeriesData> named = new TreeMap<>();
        for (SeriesData series : used) {
            String name = series.attribute(MetricSeriesNames.POOL_NAME);
            if (name != null) {
                named.putIfAbsent(name, series);
            }
        }
        List<Pool> pools = new ArrayList<>(named.size());
        named.forEach((name, first) -> {
            TreeMap<Long, Double> byTime = sum(matching(used, Map.of(MetricSeriesNames.POOL_NAME, name)));
            long[] t = timeline(byTime);
            pools.add(new Pool(name, first.attribute(MetricSeriesNames.MEMORY_TYPE), t, align(byTime, t)));
        });
        return pools;
    }

    /**
     * One entry per collector and action, bucketed: for a cumulative histogram the
     * value a person wants is "how many collections in this bucket and how long
     * did they take", which is the difference between consecutive points.
     *
     * <p>The differences are each process's own, added in the buckets once taken: one
     * process's total differenced against another's reads as hundreds of collections.
     */
    private static List<Gc> gc(List<SeriesData> series, Window window) {
        long[] t = window.bucketStarts();
        List<Gc> collectors = new ArrayList<>();
        for (List<SeriesData> collector : byCollector(series)) {
            long[] counts = new long[t.length];
            double[] durations = new double[t.length];
            for (SeriesData data : collector) {
                double toMillis = data.toMillis();
                for (SeriesData.Interval interval : data.intervals()) {
                    int bucket = window.indexOf(interval.at());
                    if (bucket >= 0) {
                        counts[bucket] += interval.count();
                        durations[bucket] += Math.max(0, interval.sum()) * toMillis;
                    }
                }
            }
            SeriesData first = collector.get(0);
            collectors.add(new Gc(first.attribute(MetricSeriesNames.GC_NAME),
                    first.attribute(MetricSeriesNames.GC_ACTION), t, counts, durations));
        }
        collectors.sort((a, b) -> String.valueOf(a.name()).compareTo(String.valueOf(b.name())));
        return collectors;
    }

    /**
     * The series of {@code jvm.gc.duration} by collector and action, each list holding the
     * series of every process that exported it, in the order their first series came: one
     * collector is one {@code gc-pause} finding as it is one entry of the chart
     * (findings.adoc#gc-pause).
     */
    public static Collection<List<SeriesData>> byCollector(List<SeriesData> series) {
        Map<String, List<SeriesData>> collectors = new LinkedHashMap<>();
        for (SeriesData data : series) {
            collectors.computeIfAbsent(data.attribute(MetricSeriesNames.GC_NAME) + "\0"
                    + data.attribute(MetricSeriesNames.GC_ACTION), key -> new ArrayList<>()).add(data);
        }
        return collectors.values();
    }

    private static Threads threads(List<SeriesData> series) {
        TreeMap<Long, Double> total = sum(series);
        TreeMap<Long, Double> daemon = sum(matching(series, Map.of(MetricSeriesNames.THREAD_DAEMON, "true")));
        long[] t = timeline(total, daemon);
        return new Threads(t, align(total, t), align(daemon, t));
    }

    private static Cpu cpu(List<SeriesData> utilization, List<SeriesData> load) {
        TreeMap<Long, Double> utilisationByTime = sum(utilization);
        // The machine's load, which every process reads alike: the highest, never a sum.
        TreeMap<Long, Double> loadByTime = across(load, Math::max);
        long[] t = timeline(utilisationByTime, loadByTime);
        return new Cpu(t, align(utilisationByTime, t),
                loadByTime.isEmpty() ? NO_VALUES : align(loadByTime, t));
    }

    /**
     * The data-source panel: used, idle, maximum and waiting connections of every
     * JDBC pool the agent instruments.
     *
     * <p>Two generations of semantic conventions name these metrics differently and
     * an application reports one or the other, so the older spelling is asked for
     * first and the stable one second. A pool that reports no maximum and no queue
     * writes a null per point rather than a shorter array, so every array of a pool
     * lines up with its own timeline.
     *
     * <p>Public because a {@code pool-exhausted} finding asks the same question of
     * the same series (findings.adoc#pool-exhausted), and two readings of "what did the pool do" would
     * be two answers.
     */
    public static List<ConnectionPool> connectionPools(MetricQueries metrics, String service,
            Window window) {
        List<SeriesData> usage =
                metrics.series(MetricSeriesNames.POOL_CONNECTIONS, service, Map.of(), window);
        List<SeriesData> max =
                metrics.series(MetricSeriesNames.POOL_CONNECTIONS_MAX, service, Map.of(), window);
        List<SeriesData> pending =
                metrics.series(MetricSeriesNames.POOL_CONNECTIONS_PENDING, service, Map.of(), window);
        String stateKey = MetricSeriesNames.POOL_STATE;
        String poolKey = MetricSeriesNames.POOL_CONNECTIONS_NAME;
        if (usage.isEmpty()) {
            usage = metrics.series(MetricSeriesNames.CONNECTION_COUNT, service, Map.of(), window);
            max = metrics.series(MetricSeriesNames.CONNECTION_MAX, service, Map.of(), window);
            pending = metrics.series(MetricSeriesNames.CONNECTION_PENDING, service, Map.of(), window);
            stateKey = MetricSeriesNames.CONNECTION_STATE;
            poolKey = MetricSeriesNames.CONNECTION_POOL_NAME;
        }
        TreeSet<String> names = new TreeSet<>();
        for (SeriesData data : usage) {
            String name = data.attribute(poolKey);
            if (name != null) {
                names.add(name);
            }
        }
        List<ConnectionPool> pools = new ArrayList<>();
        for (String name : names) {
            TreeMap<Long, Double> used = sum(matching(usage,
                    Map.of(poolKey, name, stateKey, MetricSeriesNames.STATE_USED)));
            TreeMap<Long, Double> idle = sum(matching(usage,
                    Map.of(poolKey, name, stateKey, MetricSeriesNames.STATE_IDLE)));
            TreeMap<Long, Double> limit = sum(matching(max, Map.of(poolKey, name)));
            TreeMap<Long, Double> waiting = sum(matching(pending, Map.of(poolKey, name)));
            long[] t = timeline(used, idle, limit, waiting);
            pools.add(new ConnectionPool(name, t, align(used, t), align(idle, t), align(limit, t),
                    align(waiting, t)));
        }
        return pools;
    }

    private static Classes classes(List<SeriesData> series) {
        TreeMap<Long, Double> loaded = sum(series);
        long[] t = timeline(loaded);
        return new Classes(t, align(loaded, t));
    }

    // --- shared helpers ---

    private static List<SeriesData> matching(List<SeriesData> series, Map<String, String> filter) {
        List<SeriesData> matched = new ArrayList<>();
        for (SeriesData data : series) {
            boolean ok = true;
            for (var entry : filter.entrySet()) {
                if (!entry.getValue().equals(data.attribute(entry.getKey()))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched.add(data);
            }
        }
        return matched;
    }

    /**
     * Series added up on one timeline: the series of a process on its own instants, which is
     * exact since a process exports its series together, and then the processes as
     * {@link #across} adds them.
     */
    private static TreeMap<Long, Double> sum(List<SeriesData> series) {
        return across(series, Double::sum);
    }

    /**
     * The processes of the series at every instant of any of them, combined by {@code combine}.
     *
     * <p>A process counts at an instant with its latest value, from its first point to one
     * export interval past its last: two processes that export one service name at different
     * moments, an application and its tests, then add up rather than take turns, and a process
     * restarted is not counted beside the one that replaced it. One whose first point comes
     * within an interval of the earliest point of all was running before it, so it counts from
     * there with that first value, and the timeline does not open on one process alone.
     */
    private static TreeMap<Long, Double> across(List<SeriesData> series, DoubleBinaryOperator combine) {
        Map<String, TreeMap<Long, Double>> byProcess = new LinkedHashMap<>();
        for (SeriesData data : series) {
            TreeMap<Long, Double> own = byProcess.computeIfAbsent(String.valueOf(data.process()),
                    process -> new TreeMap<>());
            for (MetricPoint point : data.points()) {
                own.merge(point.at(), point.value(), Double::sum);
            }
        }
        byProcess.values().removeIf(TreeMap::isEmpty);
        if (byProcess.size() < 2) {
            return byProcess.isEmpty() ? new TreeMap<>() : byProcess.values().iterator().next();
        }
        TreeSet<Long> instants = new TreeSet<>();
        for (TreeMap<Long, Double> own : byProcess.values()) {
            instants.addAll(own.keySet());
        }
        long earliest = instants.first();
        List<Run> runs = new ArrayList<>(byProcess.size());
        for (TreeMap<Long, Double> own : byProcess.values()) {
            long interval = interval(own);
            long first = own.firstKey();
            runs.add(new Run(own, first - earliest <= interval ? earliest : first, own.lastKey() + interval,
                    own.values().iterator().next()));
        }
        TreeMap<Long, Double> combined = new TreeMap<>();
        for (long at : instants) {
            Double value = null;
            for (Run run : runs) {
                if (at < run.from() || at > run.to()) {
                    continue;
                }
                Map.Entry<Long, Double> latest = run.values().floorEntry(at);
                double own = latest == null ? run.first() : latest.getValue();
                value = value == null ? own : combine.applyAsDouble(value, own);
            }
            if (value != null) {
                combined.put(at, value);
            }
        }
        return combined;
    }

    /**
     * One process's values and the instants it counts between.
     *
     * @param first its first value, which it counts with before its first point
     */
    private record Run(TreeMap<Long, Double> values, long from, long to, double first) {
    }

    /** How often a process exports: the median gap between its points, zero for a single one. */
    private static long interval(TreeMap<Long, Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        long[] gaps = new long[values.size() - 1];
        long previous = values.firstKey();
        int i = 0;
        for (long at : values.tailMap(previous, false).keySet()) {
            gaps[i++] = at - previous;
            previous = at;
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    @SafeVarargs
    private static long[] timeline(Map<Long, Double>... series) {
        TreeSet<Long> instants = new TreeSet<>();
        for (Map<Long, Double> each : series) {
            instants.addAll(each.keySet());
        }
        if (instants.isEmpty()) {
            return NO_TIME;
        }
        long[] t = new long[instants.size()];
        int i = 0;
        for (Long instant : instants) {
            t[i++] = instant;
        }
        return t;
    }

    private static double[] align(Map<Long, Double> byTime, long[] t) {
        double[] values = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            Double value = byTime.get(t[i]);
            values[i] = value == null ? Double.NaN : value;
        }
        return values;
    }

    private static @Nullable String string(Map<String, Object> resource, String key) {
        Object value = resource.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static @Nullable Long number(Map<String, Object> resource, String key) {
        Object value = resource.get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    /** Kept so an empty view is one expression. */
    public static JvmView empty(@Nullable String service) {
        return new JvmView(service, new Runtime(null, null, null, null),
                new Memory(NO_TIME, NO_VALUES, NO_VALUES, NO_VALUES),
                new Memory(NO_TIME, NO_VALUES, NO_VALUES, NO_VALUES),
                List.of(), List.of(), new Threads(NO_TIME, NO_VALUES, NO_VALUES),
                new Cpu(NO_TIME, NO_VALUES, NO_VALUES), new Classes(NO_TIME, NO_VALUES), List.of());
    }
}
