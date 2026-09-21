package net.benelog.spidersense.query;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

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
    // Arrays rather than lists: the histogram is five fixed counts the UI reads
    // positionally, and the wire format is a JSON array either way (docs/api.md).
    @SuppressWarnings("ArrayRecordComponent")
    public record Totals(long requests, long errors, double errorRate, double rps,
            double p50Ms, double p95Ms, double p99Ms, double maxMs, long[] histogram,
            @Nullable Double apdex) {

        public static final Totals EMPTY =
                new Totals(0, 0, 0, 0, 0, 0, 0, 0, ResponseBuckets.empty(), null);
    }

    /**
     * Aligned arrays, one entry per bucket, oldest first. A bucket with no
     * requests writes {@code null} for its percentiles rather than a zero, which
     * would draw a line down to the axis.
     */
    // Arrays rather than lists: these are one chart's aligned series, written to JSON
    // as they are, and boxing every sample would buy nothing (docs/api.md).
    @SuppressWarnings("ArrayRecordComponent")
    public record Buckets(long[] t, long[] requests, long[] errors,
            double[] p50Ms, double[] p95Ms, double[] p99Ms, long[][] histogram) {
    }

    // Arrays rather than lists: the sparkline and the histogram are aligned series the
    // UI reads positionally, and the wire format is a JSON array either way (docs/api.md).
    @SuppressWarnings("ArrayRecordComponent")
    public record ServiceSummary(String name, @Nullable String language, boolean embedded,
            long firstSeen, long lastSeen, Totals totals, long[] sparkline, boolean hasJvm) {
    }

    // Arrays rather than lists: the histogram is five fixed counts the UI reads
    // positionally, and the wire format is a JSON array either way (docs/api.md).
    @SuppressWarnings("ArrayRecordComponent")
    public record EndpointStats(String endpointId, String service, @Nullable String method,
            @Nullable String route, String name, @Nullable String kind, long calls, long errors,
            double errorRate, double rps, double avgMs, double p50Ms, double p95Ms, double p99Ms,
            double maxMs, double totalMs, long[] histogram, @Nullable Double apdex,
            Map<String, Long> statusCodes) {
    }

    /** Which endpoint issued a query, and how often. */
    public record Caller(String endpoint, String service, long calls) {
    }

    /**
     * One statement over the window, with the endpoints that issued it.
     *
     * @param schema the index catalog of the tables the statement names, or null
     *        when there is none to vouch for (agent.md, "The schema block")
     */
    public record QueryStats(String queryId, String service, @Nullable String system,
            @Nullable String namespace, @Nullable String operation, @Nullable String table,
            String statement, long calls, long errors,
            double avgMs, double p50Ms, double p95Ms, double maxMs, double totalMs, long slowCalls,
            List<Caller> callers, long lastSeen, @Nullable SchemaBlock schema) {
    }

    public record EndpointCount(String name, long count) {
    }

    public record ErrorSample(String traceId, String spanId, long at, @Nullable String message,
            @Nullable String stacktrace) {
    }

    public record ErrorGroup(String errorId, String service, @Nullable String type,
            @Nullable String message, long count,
            long firstSeen, long lastSeen, List<EndpointCount> endpoints, @Nullable ErrorSample sample) {
    }

    public record TraceSummary(String traceId, long start, double durationMs, String rootName,
            String rootService, @Nullable String rootKind, List<String> services, int spanCount,
            int errorCount, int dbCount, @Nullable Long httpStatus, boolean slow, boolean error) {
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
    public record Node(String id, String kind, String name, @Nullable Totals totals, boolean hasJvm,
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
