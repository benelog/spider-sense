package net.benelog.spidersense.query;

import java.util.List;
import java.util.Map;

/**
 * The shapes the JSON API answers with, one record per named object in api.md.
 *
 * <p>They live together because they are one vocabulary, not one per file: the
 * endpoint list, the service page and the trace list all speak it, and reading
 * the contract in one screen is worth more than the file boundaries.
 *
 * <p>Every duration is milliseconds; every instant is epoch milliseconds. A
 * {@code Long} rather than a {@code long} means "may be absent", which the wire
 * format writes as {@code null}.
 */
public final class Stats {

    private Stats() {
    }

    /**
     * The RED numbers over a window, with the response-time histogram beside them.
     *
     * <p>{@code histogram} is the five counts {@link ResponseBuckets} describes and
     * {@code apdex} is the score they imply, {@code null} when nothing was
     * requested.
     */
    public record Totals(long requests, long errors, double errorRate, double rps,
            double p50Ms, double p95Ms, double p99Ms, double maxMs, long[] histogram, Double apdex) {

        public static final Totals EMPTY =
                new Totals(0, 0, 0, 0, 0, 0, 0, 0, ResponseBuckets.empty(), null);
    }

    /**
     * Aligned arrays, one entry per bucket, oldest first. A bucket with no
     * requests writes {@code null} for its percentiles rather than a zero, which
     * would draw a line down to the axis.
     */
    public record Buckets(long[] t, long[] requests, long[] errors,
            double[] p50Ms, double[] p95Ms, double[] p99Ms, long[][] histogram) {
    }

    public record ServiceSummary(String name, String language, boolean embedded,
            long firstSeen, long lastSeen, Totals totals, long[] sparkline, boolean hasJvm) {
    }

    public record EndpointStats(String endpointId, String service, String method, String route,
            String name, String kind, long calls, long errors, double errorRate, double rps,
            double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs, double totalMs,
            long[] histogram, Double apdex, Map<String, Long> statusCodes) {
    }

    /** Which endpoint issued a query, and how often. */
    public record Caller(String endpoint, String service, long calls) {
    }

    public record QueryStats(String queryId, String service, String system, String namespace,
            String operation, String table, String statement, long calls, long errors,
            double avgMs, double p50Ms, double p95Ms, double maxMs, double totalMs, long slowCalls,
            List<Caller> callers, long lastSeen) {
    }

    public record EndpointCount(String name, long count) {
    }

    public record ErrorSample(String traceId, String spanId, long at, String message, String stacktrace) {
    }

    public record ErrorGroup(String errorId, String service, String type, String message, long count,
            long firstSeen, long lastSeen, List<EndpointCount> endpoints, ErrorSample sample) {
    }

    public record TraceSummary(String traceId, long start, double durationMs, String rootName,
            String rootService, String rootKind, List<String> services, int spanCount, int errorCount,
            int dbCount, Long httpStatus, boolean slow, boolean error) {
    }

    /** An outbound call one service makes, grouped by what it calls. */
    public record Dependency(String kind, String target, long calls, long errors,
            double avgMs, double p95Ms) {
    }

    /**
     * The topology of a window: what calls what, as the service map draws it.
     *
     * <p>A node with no edge is not listed, so the map is the traffic that
     * happened rather than the inventory of everything ever seen.
     */
    public record ServiceMap(List<Node> nodes, List<Edge> edges) {
    }

    /**
     * One node of the map.
     *
     * <p>A service node carries the {@link ServiceSummary} numbers, so the map and
     * the service page cannot disagree; every other node carries the numbers of the
     * calls made to it. The unused half is zero, which is what the wire leaves out.
     *
     * @param kind {@code user}, {@code service}, {@code db}, {@code http},
     *        {@code messaging} or {@code rpc}
     */
    public record Node(String id, String kind, String name, Totals totals, boolean hasJvm,
            long calls, long errors, double avgMs, double p95Ms) {

        public static Node user() {
            return new Node("user", "user", "Clients", null, false, 0, 0, 0, 0);
        }

        public static Node service(ServiceSummary summary) {
            return new Node("svc:" + summary.name(), "service", summary.name(), summary.totals(),
                    summary.hasJvm(), 0, 0, 0, 0);
        }

        public static Node target(String kind, String target, long calls, long errors,
                double avgMs, double p95Ms) {
            return new Node(kind + ":" + target, kind, target, null, false, calls, errors, avgMs, p95Ms);
        }

        public boolean isService() {
            return totals != null;
        }
    }

    public record Edge(String from, String to, long calls, long errors, double avgMs, double p95Ms) {
    }

    /** One dot on the scatter. Flags: 1 error, 2 slow, 4 contains a slow query. */
    public record ScatterPoint(long start, double durationMs, String service, String endpoint,
            String traceId, int flags) {

        public static final int ERROR = 1;
        public static final int SLOW = 2;
        public static final int SLOW_QUERY = 4;
    }
}
