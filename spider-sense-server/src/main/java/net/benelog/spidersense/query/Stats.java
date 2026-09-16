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

    /** The RED numbers over a window. */
    public record Totals(long requests, long errors, double errorRate, double rps,
            double p50Ms, double p95Ms, double p99Ms, double maxMs) {

        public static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Aligned arrays, one entry per bucket, oldest first. A bucket with no
     * requests writes {@code null} for its percentiles rather than a zero, which
     * would draw a line down to the axis.
     */
    public record Buckets(long[] t, long[] requests, long[] errors,
            double[] p50Ms, double[] p95Ms, double[] p99Ms) {
    }

    public record ServiceSummary(String name, String language, boolean embedded,
            long firstSeen, long lastSeen, Totals totals, long[] sparkline, boolean hasJvm) {
    }

    public record EndpointStats(String endpointId, String service, String method, String route,
            String name, String kind, long calls, long errors, double errorRate, double rps,
            double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs, double totalMs,
            Map<String, Long> statusCodes) {
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

    /** One dot on the XLog scatter. Flags: 1 error, 2 slow, 4 contains a slow query. */
    public record XlogPoint(long start, double durationMs, String service, String endpoint,
            String traceId, int flags) {

        public static final int ERROR = 1;
        public static final int SLOW = 2;
        public static final int SLOW_QUERY = 4;
    }
}
