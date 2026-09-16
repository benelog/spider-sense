package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import net.benelog.spidersense.query.MetricQueries.SeriesData;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.MetricSeriesNames;
import net.benelog.spidersense.store.ServiceInfo;

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
 */
public record JvmView(String service, Runtime runtime, Memory heap, Memory nonHeap, List<Pool> pools,
        List<Gc> gc, Threads threads, Cpu cpu, Classes classes,
        List<ConnectionPool> connectionPools) {

    public record Runtime(String jvm, Long pid, String host, Long cpuCount) {
    }

    /** {@code limit} is empty for non-heap, which has none. */
    public record Memory(long[] t, double[] used, double[] committed, double[] limit) {
    }

    public record Pool(String name, String type, long[] t, double[] used) {
    }

    /** Per bucket rather than per point: a cumulative histogram's deltas are what a chart shows. */
    public record Gc(String name, String action, long[] t, long[] count, double[] durationMs) {
    }

    public record Threads(long[] t, double[] count, double[] daemon) {
    }

    public record Cpu(long[] t, double[] utilization, double[] systemLoad1m) {
    }

    public record Classes(long[] t, double[] loaded) {
    }

    /** One JDBC pool; {@code max} and {@code pending} are null per point when unreported. */
    public record ConnectionPool(String name, long[] t, double[] used, double[] idle, double[] max,
            double[] pending) {
    }

    private static final long[] NO_TIME = {};
    private static final double[] NO_VALUES = {};

    public static JvmView of(MetricQueries metrics, ServiceInfo service, String name, Window window) {
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

    private static Runtime runtime(MetricQueries metrics, ServiceInfo service, String name, Window window) {
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

    private static List<Pool> pools(List<SeriesData> used) {
        List<Pool> pools = new ArrayList<>();
        for (SeriesData series : used) {
            String name = series.attribute(MetricSeriesNames.POOL_NAME);
            if (name == null) {
                continue;
            }
            long[] t = new long[series.points().size()];
            double[] values = new double[t.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = series.points().get(i).at();
                values[i] = series.points().get(i).value();
            }
            pools.add(new Pool(name, series.attribute(MetricSeriesNames.MEMORY_TYPE), t, values));
        }
        pools.sort((a, b) -> a.name().compareTo(b.name()));
        return pools;
    }

    /**
     * One entry per collector and action, bucketed: for a cumulative histogram the
     * value a person wants is "how many collections in this bucket and how long
     * did they take", which is the difference between consecutive points.
     */
    private static List<Gc> gc(List<SeriesData> series, Window window) {
        long[] t = window.bucketStarts();
        List<Gc> collectors = new ArrayList<>();
        for (SeriesData data : series) {
            long[] counts = new long[t.length];
            double[] durations = new double[t.length];
            List<MetricPoint> points = data.points();
            boolean cumulative = !"DELTA".equals(data.temporality());
            for (int i = 0; i < points.size(); i++) {
                MetricPoint point = points.get(i);
                long countDelta = point.count();
                double sumDelta = point.sum();
                if (cumulative) {
                    if (i == 0) {
                        continue;
                    }
                    countDelta -= points.get(i - 1).count();
                    sumDelta -= points.get(i - 1).sum();
                }
                int bucket = window.indexOf(point.at());
                if (bucket >= 0 && countDelta >= 0) {
                    counts[bucket] += countDelta;
                    durations[bucket] += Math.max(0, sumDelta);
                }
            }
            collectors.add(new Gc(data.attribute(MetricSeriesNames.GC_NAME),
                    data.attribute(MetricSeriesNames.GC_ACTION), t, counts, durations));
        }
        collectors.sort((a, b) -> String.valueOf(a.name()).compareTo(String.valueOf(b.name())));
        return collectors;
    }

    private static Threads threads(List<SeriesData> series) {
        TreeMap<Long, Double> total = sum(series);
        TreeMap<Long, Double> daemon = sum(matching(series, Map.of(MetricSeriesNames.THREAD_DAEMON, "true")));
        long[] t = timeline(total, daemon);
        return new Threads(t, align(total, t), align(daemon, t));
    }

    private static Cpu cpu(List<SeriesData> utilization, List<SeriesData> load) {
        TreeMap<Long, Double> utilisationByTime = sum(utilization);
        TreeMap<Long, Double> loadByTime = sum(load);
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
     */
    private static List<ConnectionPool> connectionPools(MetricQueries metrics, String service,
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

    /** Series exported together share their timestamps, so summing on the instant is exact. */
    private static TreeMap<Long, Double> sum(List<SeriesData> series) {
        TreeMap<Long, Double> byTime = new TreeMap<>();
        for (SeriesData data : series) {
            for (MetricPoint point : data.points()) {
                byTime.merge(point.at(), point.value(), Double::sum);
            }
        }
        return byTime;
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

    private static String string(Map<String, Object> resource, String key) {
        Object value = resource.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> resource, String key) {
        Object value = resource.get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    /** Kept so an empty view is one expression. */
    public static JvmView empty(String service) {
        return new JvmView(service, new Runtime(null, null, null, null),
                new Memory(NO_TIME, NO_VALUES, NO_VALUES, NO_VALUES),
                new Memory(NO_TIME, NO_VALUES, NO_VALUES, NO_VALUES),
                List.of(), List.of(), new Threads(NO_TIME, NO_VALUES, NO_VALUES),
                new Cpu(NO_TIME, NO_VALUES, NO_VALUES), new Classes(NO_TIME, NO_VALUES), List.of());
    }
}
