package net.benelog.spidersense.query;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.Ids;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.ServiceInfo;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.Tingle;
import net.benelog.spidersense.store.Tingles;
import org.jspecify.annotations.Nullable;

/**
 * Every read the JSON API makes, as SQL over the window.
 *
 * <p>One class rather than one per page, because the statements share their
 * vocabulary: "entry spans in the window", "grouped by endpoint", "per bucket".
 * Splitting them by URL would scatter that and hide how similar they are.
 *
 * <p>Percentiles are H2's {@code PERCENTILE_DISC}, which is nearest-rank over the
 * actual durations — the same definition the earlier in-memory version used, so
 * the numbers the UI shows did not change when the storage did.
 */
public final class Queries {

    /** A guard on the one query that reads whole rows rather than an aggregate. */
    private static final int MAX_DEPENDENCY_ROWS = 20_000;

    private static final String PERCENTILES = """
            PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_ns) AS p50_ns,
            PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns) AS p95_ns,
            PERCENTILE_DISC(0.99) WITHIN GROUP (ORDER BY duration_ns) AS p99_ns""";

    private final Sql sql;
    private final Tingles tingles;
    private final ServiceRegistry services;
    private final ResponseBuckets responseBuckets;
    private final Catalog catalog;

    public Queries(Sql sql, Tingles tingles, ServiceRegistry services) {
        this.sql = sql;
        this.tingles = tingles;
        this.services = services;
        this.responseBuckets = new ResponseBuckets(tingles.slowRequestMs());
        this.catalog = new Catalog(sql);
    }

    /** The index catalog, for the rules that answer over the same tables (findings.adoc#schema). */
    public Catalog catalog() {
        return catalog;
    }

    /** The scale the histograms are counted on, which {@code /api/status} also reports. */
    public ResponseBuckets responseBuckets() {
        return responseBuckets;
    }

    // --- filters -------------------------------------------------------------

    /** Everything {@code GET /api/traces} can be narrowed by. */
    public record TraceFilter(Window window, @Nullable String service, @Nullable String endpointId,
            @Nullable Long minMs, @Nullable Long maxMs, @Nullable String status, @Nullable String q,
            @Nullable Long before, int limit) {
    }

    /** Everything {@code GET /api/logs} can be narrowed by. */
    public record LogFilter(Window window, @Nullable String service, @Nullable String severity,
            @Nullable String q, @Nullable String traceId, @Nullable Long before, int limit) {
    }

    /** One trace, as {@code GET /api/traces/{traceId}} answers it. */
    public record TraceDetail(String traceId, long start, long end, double durationMs,
            List<String> services, List<SpanRecord> spans, List<LogRecord> logs) {
    }

    // --- totals and series ---------------------------------------------------

    public Stats.Totals totals(Window window, @Nullable String service) {
        Clause where = entryWindow(window, service);
        String query = "SELECT COUNT(*) AS calls, SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors,"
                + " MAX(duration_ns) AS max_ns, " + responseBuckets.columns() + ", " + PERCENTILES
                + " FROM span WHERE " + where.sql();
        Stats.Totals totals = sql.queryOne(query, where.params(),
                rs -> totals(rs, window.rangeSeconds()));
        return totals == null ? Stats.Totals.EMPTY : totals;
    }

    private static Stats.Totals totals(ResultSet rs, double seconds) throws SQLException {
        long calls = rs.getLong("calls");
        long errors = rs.getLong("errors");
        long[] histogram = ResponseBuckets.histogram(rs, errors);
        return new Stats.Totals(calls, errors,
                calls == 0 ? 0 : (double) errors / calls,
                calls / seconds,
                Rows.ms(rs, "p50_ns"), Rows.ms(rs, "p95_ns"), Rows.ms(rs, "p99_ns"),
                Rows.ms(rs, "max_ns"), histogram, ResponseBuckets.apdex(histogram, calls));
    }

    /** Requests, errors and percentiles per bucket. */
    public Stats.Buckets buckets(Window window, @Nullable String service, @Nullable String endpointId) {
        Clause where = entryWindow(window, service);
        if (endpointId != null) {
            where = where.and("endpoint_id = ?", endpointId);
        }
        long bucket = window.bucketMs();
        String query = "SELECT start_ms / " + bucket + " AS b, COUNT(*) AS calls,"
                + " SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors, " + responseBuckets.columns()
                + ", " + PERCENTILES
                + " FROM span WHERE " + where.sql() + " GROUP BY start_ms / " + bucket;

        long[] t = window.bucketStarts();
        long[] requests = new long[t.length];
        long[] errors = new long[t.length];
        double[] p50 = new double[t.length];
        double[] p95 = new double[t.length];
        double[] p99 = new double[t.length];
        long[][] histogram = ResponseBuckets.emptySeries(t.length);
        long first = window.alignedFrom() / bucket;
        sql.forEach(query, where.params(), rs -> {
            int i = (int) (rs.getLong("b") - first);
            if (i >= 0 && i < t.length) {
                requests[i] = rs.getLong("calls");
                errors[i] = rs.getLong("errors");
                p50[i] = Rows.ms(rs, "p50_ns");
                p95[i] = Rows.ms(rs, "p95_ns");
                p99[i] = Rows.ms(rs, "p99_ns");
                long[] counts = ResponseBuckets.histogram(rs, errors[i]);
                for (int slot = 0; slot < histogram.length; slot++) {
                    histogram[slot][i] = counts[slot];
                }
            }
        });
        return new Stats.Buckets(t, requests, errors, p50, p95, p99, histogram);
    }

    // --- services ------------------------------------------------------------

    public List<Stats.ServiceSummary> services(Window window) {
        Map<String, Stats.Totals> byService = new HashMap<>();
        Clause where = entryWindow(window, null);
        sql.forEach("SELECT service, COUNT(*) AS calls, SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors,"
                + " MAX(duration_ns) AS max_ns, " + responseBuckets.columns() + ", " + PERCENTILES
                + " FROM span WHERE " + where.sql() + " GROUP BY service", where.params(), rs ->
                        byService.put(rs.getString("service"), totals(rs, window.rangeSeconds())));

        Map<String, long[]> sparklines = sparklines(window);
        Set<String> withJvm = new HashSet<>(sql.query(
                "SELECT DISTINCT service FROM metric_series WHERE name LIKE 'jvm.%'",
                List.of(), rs -> rs.getString(1)));

        List<Stats.ServiceSummary> summaries = new ArrayList<>();
        for (ServiceInfo service : services.all()) {
            summaries.add(new Stats.ServiceSummary(service.name(), service.language(), service.embedded(),
                    service.firstSeen(), service.lastSeen(),
                    byService.getOrDefault(service.name(), Stats.Totals.EMPTY),
                    sparklines.getOrDefault(service.name(), new long[window.bucketCount()]),
                    withJvm.contains(service.name())));
        }
        return summaries;
    }

    public Stats.@Nullable ServiceSummary service(String name, Window window) {
        for (Stats.ServiceSummary summary : services(window)) {
            if (summary.name().equals(name)) {
                return summary;
            }
        }
        return null;
    }

    private Map<String, long[]> sparklines(Window window) {
        long bucket = window.bucketMs();
        long first = window.alignedFrom() / bucket;
        int count = window.bucketCount();
        Clause where = entryWindow(window, null);
        Map<String, long[]> sparklines = new HashMap<>();
        sql.forEach("SELECT service, start_ms / " + bucket + " AS b, COUNT(*) AS calls FROM span WHERE "
                + where.sql() + " GROUP BY service, start_ms / " + bucket, where.params(), rs -> {
                    int i = (int) (rs.getLong("b") - first);
                    if (i >= 0 && i < count) {
                        sparklines.computeIfAbsent(rs.getString("service"), s -> new long[count])[i] =
                                rs.getLong("calls");
                    }
                });
        return sparklines;
    }

    /** The resource attributes of a service, string values only, sorted by key. */
    public Map<String, String> resource(String name) {
        ServiceInfo service = services.get(name);
        if (service == null) {
            return Map.of();
        }
        Map<String, String> resource = new TreeMap<>();
        service.resource().forEach((key, value) -> resource.put(key, String.valueOf(value)));
        return resource;
    }

    // --- endpoints -----------------------------------------------------------

    public List<Stats.EndpointStats> endpoints(Window window, @Nullable String service,
            @Nullable String endpointId) {
        return endpoints(window, service, endpointId, true);
    }

    /**
     * The same, with or without the status codes, which are a second scan of the
     * window's entry spans and which no finding reads.
     */
    List<Stats.EndpointStats> endpoints(Window window, @Nullable String service,
            @Nullable String endpointId, boolean withStatusCodes) {
        Clause where = entryWindow(window, service).and("endpoint_id IS NOT NULL");
        if (endpointId != null) {
            where = where.and("endpoint_id = ?", endpointId);
        }
        String query = "SELECT endpoint_id, service, MAX(endpoint) AS name, MAX(http_method) AS method,"
                + " MAX(http_route) AS route, MAX(kind) AS kind, COUNT(*) AS calls,"
                + " SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors, SUM(duration_ns) AS total_ns,"
                + " MAX(duration_ns) AS max_ns, " + responseBuckets.columns() + ", " + PERCENTILES
                + " FROM span WHERE " + where.sql()
                + " GROUP BY endpoint_id, service ORDER BY total_ns DESC";

        Map<String, Map<String, Long>> statusCodes = withStatusCodes ? statusCodes(where) : Map.of();
        double seconds = window.rangeSeconds();
        return sql.query(query, where.params(), rs -> {
            String id = rs.getString("endpoint_id");
            long calls = rs.getLong("calls");
            long errors = rs.getLong("errors");
            double totalMs = Rows.ms(rs, "total_ns");
            long[] histogram = ResponseBuckets.histogram(rs, errors);
            String method = rs.getString("method");
            String name = rs.getString("name");
            return new Stats.EndpointStats(id, rs.getString("service"), method,
                    SpanRecord.endpointRoute(method, rs.getString("route"), name), name,
                    rs.getString("kind"), calls, errors,
                    calls == 0 ? 0 : (double) errors / calls, calls / seconds,
                    calls == 0 ? 0 : totalMs / calls,
                    Rows.ms(rs, "p50_ns"), Rows.ms(rs, "p95_ns"), Rows.ms(rs, "p99_ns"),
                    Rows.ms(rs, "max_ns"), totalMs,
                    histogram, ResponseBuckets.apdex(histogram, calls),
                    statusCodes.getOrDefault(id, Map.of()));
        });
    }

    /** The database work the requests of one endpoint did: calls and time, summed. */
    public record DbWork(long calls, double totalMs) {

        public static final DbWork NONE = new DbWork(0, 0);
    }

    /**
     * How much database work each endpoint's requests did, by endpoint id.
     *
     * <p>The join is "a database span of the same trace and the same service"
     * (storage.adoc): the database work of a downstream service belongs to that
     * service's own endpoint, not to the one that called it. A finding's
     * {@code dbShare} and a comparison's {@code dbCallsPerRequest} are the same
     * question, so they are one statement.
     *
     * <p>{@code d} is read by its trace: left to itself, H2 reads it by service and
     * instant, every span of the service in the window once per entry span.
     */
    public Map<String, DbWork> databaseWork(Window window, @Nullable String service) {
        return databaseWork(window, service, null);
    }

    /**
     * The same, for these endpoints only, or for every endpoint when
     * {@code endpointIds} is null.
     *
     * <p>A {@code slow-endpoint} finding needs the endpoints that crossed the
     * threshold and nothing else, and the join costs every trace it reads.
     */
    public Map<String, DbWork> databaseWork(Window window, @Nullable String service,
            @Nullable Collection<String> endpointIds) {
        if (endpointIds != null && endpointIds.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to(),
                window.from(), window.to()));
        String where = "e.entry AND e.endpoint_id IS NOT NULL AND e.start_ms BETWEEN ? AND ?"
                + " AND d.start_ms BETWEEN ? AND ?";
        if (service != null) {
            where = where + " AND e.service = ?";
            params.add(service);
        }
        if (endpointIds != null) {
            where = where + " AND e.endpoint_id IN (" + Sql.placeholders(endpointIds.size()) + ")";
            params.addAll(endpointIds);
        }
        Map<String, DbWork> work = new HashMap<>();
        sql.forEach("SELECT e.endpoint_id AS id, COUNT(*) AS calls, SUM(d.duration_ns) AS total_ns"
                + " FROM span e JOIN span d USE INDEX (span_trace) ON d.trace_id = e.trace_id AND d.service = e.service"
                + " AND d.query_id IS NOT NULL WHERE " + where + " GROUP BY e.endpoint_id",
                params, rs ->
                        work.put(rs.getString("id"),
                                new DbWork(rs.getLong("calls"), Rows.ms(rs, "total_ns"))));
        return work;
    }

    /**
     * How much database work each job's runs did, by {@code service\0name}.
     *
     * <p>The same join as {@link #databaseWork}, over the root {@code INTERNAL} spans
     * a {@code slow-job} finding is about (storage.adoc): a job is not an endpoint, so
     * it is keyed by the service and the span name rather than by an endpoint id.
     *
     * <p>Only the jobs of one service named {@code names} are joined, which are the
     * ones a {@code slow-job} finding is about: the runs are then read by the
     * service's {@code (service, start_ms)} index rather than out of every span of
     * the window.
     */
    public Map<String, DbWork> jobDatabaseWork(Window window, String service,
            Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to(),
                window.from(), window.to(), service));
        String where = "r.parent_span_id IS NULL AND r.kind = 'INTERNAL'"
                + " AND r.start_ms BETWEEN ? AND ? AND d.start_ms BETWEEN ? AND ? AND r.service = ?";
        where = where + " AND r.name IN (" + Sql.placeholders(names.size()) + ")";
        params.addAll(names);
        Map<String, DbWork> work = new HashMap<>();
        sql.forEach("SELECT r.service AS service, r.name AS name, COUNT(d.id) AS calls,"
                + " SUM(d.duration_ns) AS total_ns"
                + " FROM span r JOIN span d USE INDEX (span_trace) ON d.trace_id = r.trace_id AND d.service = r.service"
                + " AND d.query_id IS NOT NULL WHERE " + where + " GROUP BY r.service, r.name",
                params, rs ->
                        work.put(rs.getString("service") + "\0" + rs.getString("name"),
                                new DbWork(rs.getLong("calls"), Rows.ms(rs, "total_ns"))));
        return work;
    }

    private Map<String, Map<String, Long>> statusCodes(Clause where) {
        Map<String, Map<String, Long>> byEndpoint = new HashMap<>();
        sql.forEach("SELECT endpoint_id, http_status, COUNT(*) AS calls FROM span WHERE " + where.sql()
                + " AND http_status IS NOT NULL GROUP BY endpoint_id, http_status", where.params(), rs ->
                        byEndpoint.computeIfAbsent(rs.getString("endpoint_id"), id -> new TreeMap<>())
                                .put(String.valueOf(rs.getInt("http_status")), rs.getLong("calls")));
        return byEndpoint;
    }

    // --- queries -------------------------------------------------------------

    public List<Stats.QueryStats> queries(Window window, @Nullable String service,
            @Nullable String sort, int limit, @Nullable String queryId) {
        return queries(window, service, sort, limit, queryId, () -> Ancestry.of(sql, window, service));
    }

    /**
     * The same, with the parent-chain walk handed in, so that the rules of one
     * answer that each need it read the window's spans for it once.
     *
     * <p>The walk must be the one of this window over the traces that have a span
     * of {@code service}, or over every trace when it is null.
     */
    List<Stats.QueryStats> queries(Window window, @Nullable String service, @Nullable String sort,
            int limit, @Nullable String queryId, Supplier<Ancestry> ancestry) {
        return withSchema(withCallers(queryGroups(window, service, sort, limit, queryId), window,
                ancestry));
    }

    /**
     * The query groups alone: the aggregate, with no callers and no schema block.
     *
     * <p>What a finding's state needs from the previous run, where only which groups
     * crossed a threshold matters (findings.adoc#state).
     */
    List<Stats.QueryStats> queryGroups(Window window, @Nullable String service,
            @Nullable String sort, int limit, @Nullable String queryId) {
        Clause where = window(window, service).and("query_id IS NOT NULL");
        if (queryId != null) {
            where = where.and("query_id = ?", queryId);
        }
        String order = switch (sort == null ? "total" : sort) {
            case "avg" -> "SUM(duration_ns) / COUNT(*) DESC";   // an alias cannot sit inside an ORDER BY expression in H2
            case "p95" -> "p95_ns DESC";
            case "max" -> "max_ns DESC";
            case "calls" -> "calls DESC";
            default -> "total_ns DESC";
        };
        long slowNs = tingles.slowQueryMs() * 1_000_000L;
        String query = "SELECT query_id, service, MAX(db_system) AS db_sys, MAX(db_namespace) AS db_ns,"
                + " MAX(db_operation) AS db_op, MAX(db_table) AS db_tbl, MAX(db_statement) AS stmt,"
                + " COUNT(*) AS calls, SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors,"
                + " SUM(duration_ns) AS total_ns, MAX(duration_ns) AS max_ns, MAX(start_ms) AS last_seen,"
                + " SUM(CASE WHEN duration_ns > " + slowNs + " THEN 1 ELSE 0 END) AS slow_calls, "
                + PERCENTILES + " FROM span WHERE " + where.sql()
                + " GROUP BY query_id, service ORDER BY " + order + " LIMIT " + Math.max(1, limit);

        return sql.query(query, where.params(), rs -> {
            long calls = rs.getLong("calls");
            double totalMs = Rows.ms(rs, "total_ns");
            return new Stats.QueryStats(rs.getString("query_id"), rs.getString("service"),
                    rs.getString("db_sys"), rs.getString("db_ns"), rs.getString("db_op"),
                    rs.getString("db_tbl"), rs.getString("stmt"), calls, rs.getLong("errors"),
                    calls == 0 ? 0 : totalMs / calls, Rows.ms(rs, "p50_ns"), Rows.ms(rs, "p95_ns"),
                    Rows.ms(rs, "max_ns"), totalMs, rs.getLong("slow_calls"), List.of(),
                    rs.getLong("last_seen"), null);
        });
    }

    /**
     * The schema block of every group, read from the catalog once per service.
     *
     * <p>A window has a handful of services and a hundred query groups, so the
     * catalog is read per service rather than per statement; the matching itself is
     * Java over what came back (storage.adoc#reads).
     */
    private List<Stats.QueryStats> withSchema(List<Stats.QueryStats> stats) {
        Map<String, Map<String, List<Catalog.Table>>> byService = new HashMap<>();
        List<Stats.QueryStats> withSchema = new ArrayList<>(stats.size());
        for (Stats.QueryStats query : stats) {
            SchemaBlock block = SchemaBlock.of(query.statement(),
                    byService.computeIfAbsent(query.service(), catalog::forService));
            withSchema.add(new Stats.QueryStats(query.queryId(), query.service(), query.system(),
                    query.namespace(), query.operation(), query.table(), query.statement(),
                    query.calls(), query.errors(), query.avgMs(), query.p50Ms(), query.p95Ms(),
                    query.maxMs(), query.totalMs(), query.slowCalls(), query.callers(),
                    query.lastSeen(), block));
        }
        return withSchema;
    }

    /**
     * The endpoint each query was issued from: the nearest entry span up the
     * parent chain, within the trace.
     *
     * <p>A query id hashes its service (storage.adoc), so every span read here is a
     * span of the groups' service, and the walk over that service's traces reaches
     * every entry span the walk over all of them would.
     */
    private List<Stats.QueryStats> withCallers(List<Stats.QueryStats> stats, Window window,
            Supplier<Ancestry> ancestors) {
        if (stats.isEmpty()) {
            return stats;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Stats.QueryStats query : stats) {
            ids.add(query.queryId());
        }
        Ancestry ancestry = ancestors.get();
        Map<String, Map<String, long[]>> callers = new HashMap<>();
        Map<String, Map<String, String>> callerService = new HashMap<>();
        Clause where = window(window, null)
                .and("query_id IN (" + Sql.placeholders(ids.size()) + ")", ids.toArray());
        sql.forEach("SELECT query_id, trace_id, span_id, service FROM span WHERE " + where.sql(),
                where.params(), rs -> {
                    String queryId = rs.getString("query_id");
                    Ancestry.Entry entry = ancestry.entryOf(rs.getString("span_id"));
                    String name = entry == null ? "(no endpoint)" : entry.endpoint();
                    callers.computeIfAbsent(queryId, id -> new LinkedHashMap<>())
                            .computeIfAbsent(name, n -> new long[1])[0]++;
                    callerService.computeIfAbsent(queryId, id -> new HashMap<>())
                            .putIfAbsent(name, entry == null ? rs.getString("service") : entry.service());
                });

        List<Stats.QueryStats> withCallers = new ArrayList<>(stats.size());
        for (Stats.QueryStats query : stats) {
            List<Stats.Caller> list = new ArrayList<>();
            callers.getOrDefault(query.queryId(), Map.of()).forEach((name, count) ->
                    list.add(new Stats.Caller(name,
                            callerService.getOrDefault(query.queryId(), Map.of())
                                    .getOrDefault(name, query.service()), count[0])));
            list.sort((a, b) -> Long.compare(b.calls(), a.calls()));
            withCallers.add(new Stats.QueryStats(query.queryId(), query.service(), query.system(),
                    query.namespace(), query.operation(), query.table(), query.statement(), query.calls(),
                    query.errors(), query.avgMs(), query.p50Ms(), query.p95Ms(), query.maxMs(),
                    query.totalMs(), query.slowCalls(), list, query.lastSeen(), query.schema()));
        }
        return withCallers;
    }

    /** Calls and p95 per bucket for one query group. */
    public Stats.Buckets queryBuckets(Window window, String queryId) {
        return groupBuckets(window, "query_id = ?", queryId);
    }

    /** Occurrences per bucket for one error group. */
    public Stats.Buckets errorBuckets(Window window, String errorId) {
        return groupBuckets(window, "error_id = ?", errorId);
    }

    /** The most points an error group's {@code series} has: a sparkline, not a chart. */
    public static final int ERROR_SERIES_POINTS = 30;

    /**
     * Occurrences per bucket for each of these error groups, as the errors page
     * draws them (api.adoc#error-group, {@code ErrorGroup.series}).
     *
     * <p>The buckets are the window's, the ones the Overview charts, merged
     * {@code ceil(n / 30)} at a time from the oldest, so a sparkline has at most
     * {@value #ERROR_SERIES_POINTS} points whatever the range. A group with no
     * occurrence in the window gets zeros.
     */
    public Map<String, long[]> errorSeries(Window window, List<String> errorIds) {
        Map<String, long[]> series = new HashMap<>();
        if (errorIds.isEmpty()) {
            return series;
        }
        int buckets = window.bucketCount();
        int merge = (buckets + ERROR_SERIES_POINTS - 1) / ERROR_SERIES_POINTS;
        int points = (buckets + merge - 1) / merge;
        for (String id : errorIds) {
            series.put(id, new long[points]);
        }
        long bucket = window.bucketMs();
        long first = window.alignedFrom() / bucket;
        Clause where = window(window, null).and("error")
                .and("error_id IN (" + Sql.placeholders(errorIds.size()) + ")", errorIds.toArray());
        sql.forEach("SELECT error_id, start_ms / " + bucket + " AS b, COUNT(*) AS calls FROM span WHERE "
                + where.sql() + " GROUP BY error_id, start_ms / " + bucket, where.params(), rs -> {
                    int i = (int) (rs.getLong("b") - first);
                    long[] counts = series.get(rs.getString("error_id"));
                    if (counts != null && i >= 0 && i < buckets) {
                        counts[i / merge] += rs.getLong("calls");
                    }
                });
        return series;
    }

    private Stats.Buckets groupBuckets(Window window, String predicate, String id) {
        Clause where = window(window, null).and(predicate, id);
        long bucket = window.bucketMs();
        long first = window.alignedFrom() / bucket;
        long[] t = window.bucketStarts();
        long[] counts = new long[t.length];
        long[] errors = new long[t.length];
        double[] p95 = new double[t.length];
        sql.forEach("SELECT start_ms / " + bucket + " AS b, COUNT(*) AS calls,"
                + " SUM(CASE WHEN error THEN 1 ELSE 0 END) AS errors, " + PERCENTILES
                + " FROM span WHERE " + where.sql() + " GROUP BY start_ms / " + bucket,
                where.params(), rs -> {
                    int i = (int) (rs.getLong("b") - first);
                    if (i >= 0 && i < t.length) {
                        counts[i] = rs.getLong("calls");
                        errors[i] = rs.getLong("errors");
                        p95[i] = Rows.ms(rs, "p95_ns");
                    }
                });
        return new Stats.Buckets(t, counts, errors, new double[t.length], p95, new double[t.length],
                ResponseBuckets.emptySeries(t.length));
    }

    // --- errors --------------------------------------------------------------

    public List<Stats.ErrorGroup> errors(Window window, @Nullable String service, int limit,
            @Nullable String errorId) {
        return errors(window, service, limit, errorId, () -> Ancestry.of(sql, window, service));
    }

    /** The same, with the parent-chain walk handed in, as {@link #queries} takes it. */
    List<Stats.ErrorGroup> errors(Window window, @Nullable String service, int limit,
            @Nullable String errorId, Supplier<Ancestry> ancestry) {
        List<Stats.ErrorGroup> groups = errorGroups(window, service, limit, errorId);
        if (groups.isEmpty()) {
            return groups;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Stats.ErrorGroup group : groups) {
            ids.add(group.errorId());
        }
        Map<String, Stats.ErrorSample> samples = errorSamples(window, ids);
        Map<String, List<Stats.EndpointCount>> endpoints = errorEndpoints(window, ids, ancestry);

        List<Stats.ErrorGroup> complete = new ArrayList<>(groups.size());
        for (Stats.ErrorGroup group : groups) {
            complete.add(new Stats.ErrorGroup(group.errorId(), group.service(), group.type(),
                    group.message(), group.count(), group.firstSeen(), group.lastSeen(),
                    endpoints.getOrDefault(group.errorId(), List.of()), samples.get(group.errorId())));
        }
        return complete;
    }

    /**
     * The error groups alone: the aggregate, with no sample and no endpoints, which
     * is what a finding's state needs from the previous run (findings.adoc#state).
     */
    List<Stats.ErrorGroup> errorGroups(Window window, @Nullable String service, int limit,
            @Nullable String errorId) {
        Clause where = window(window, service).and("error").and("error_id IS NOT NULL");
        if (errorId != null) {
            where = where.and("error_id = ?", errorId);
        }
        String query = "SELECT error_id, service, MAX(error_type) AS type, MAX(error_message) AS message,"
                + " COUNT(*) AS count, MIN(start_ms) AS first_seen, MAX(start_ms) AS last_seen"
                + " FROM span WHERE " + where.sql()
                + " GROUP BY error_id, service ORDER BY count DESC LIMIT " + Math.max(1, limit);

        return sql.query(query, where.params(), rs ->
                new Stats.ErrorGroup(rs.getString("error_id"), rs.getString("service"),
                        rs.getString("type"), Ids.normaliseMessage(rs.getString("message")),
                        rs.getLong("count"), rs.getLong("first_seen"), rs.getLong("last_seen"),
                        List.of(), null));
    }

    private Map<String, Stats.ErrorSample> errorSamples(Window window, Set<String> ids) {
        Clause where = window(window, null)
                .and("error_id IN (" + Sql.placeholders(ids.size()) + ")", ids.toArray());
        String query = "SELECT * FROM (SELECT error_id, trace_id, span_id, start_ms, error_message, events,"
                + " ROW_NUMBER() OVER (PARTITION BY error_id ORDER BY start_ms DESC, id DESC) AS rn"
                + " FROM span WHERE " + where.sql() + ") WHERE rn = 1";
        Map<String, Stats.ErrorSample> samples = new HashMap<>();
        sql.forEach(query, where.params(), rs -> {
            String stacktrace = null;
            for (SpanRecord.SpanEvent event : AttrJson.decodeEvents(rs.getString("events"))) {
                Object trace = event.attributes().get("exception.stacktrace");
                if (trace != null) {
                    stacktrace = String.valueOf(trace);
                }
            }
            samples.put(rs.getString("error_id"), new Stats.ErrorSample(rs.getString("trace_id"),
                    rs.getString("span_id"), rs.getLong("start_ms"), rs.getString("error_message"),
                    stacktrace));
        });
        return samples;
    }

    /** Where each error group occurred; an error id hashes its service, as a query id does. */
    private Map<String, List<Stats.EndpointCount>> errorEndpoints(Window window, Set<String> ids,
            Supplier<Ancestry> ancestors) {
        Ancestry ancestry = ancestors.get();
        Clause where = window(window, null)
                .and("error_id IN (" + Sql.placeholders(ids.size()) + ")", ids.toArray());
        Map<String, Map<String, long[]>> counts = new LinkedHashMap<>();
        sql.forEach("SELECT error_id, span_id FROM span WHERE " + where.sql(), where.params(), rs -> {
            Ancestry.Entry entry = ancestry.entryOf(rs.getString("span_id"));
            counts.computeIfAbsent(rs.getString("error_id"), id -> new LinkedHashMap<>())
                    .computeIfAbsent(entry == null ? "(no endpoint)" : entry.endpoint(),
                            n -> new long[1])[0]++;
        });
        Map<String, List<Stats.EndpointCount>> endpoints = new HashMap<>();
        counts.forEach((errorId, byEndpoint) -> {
            List<Stats.EndpointCount> list = new ArrayList<>();
            byEndpoint.forEach((name, count) -> list.add(new Stats.EndpointCount(name, count[0])));
            list.sort((a, b) -> Long.compare(b.count(), a.count()));
            endpoints.put(errorId, list);
        });
        return endpoints;
    }

    // --- traces --------------------------------------------------------------

    public List<Stats.TraceSummary> traces(TraceFilter filter) {
        Clause where = traceWhere(filter, true);
        return sql.query("SELECT * FROM trace t WHERE " + where.sql()
                + " ORDER BY t.start_ms DESC LIMIT " + Math.max(1, filter.limit()),
                where.params(), Rows::trace);
    }

    public long traceTotal(TraceFilter filter) {
        Clause where = traceWhere(filter, false);
        return sql.count("SELECT COUNT(*) FROM trace t WHERE " + where.sql(), where.params());
    }

    private Clause traceWhere(TraceFilter filter, boolean paging) {
        Window window = filter.window();
        Clause where = new Clause("t.start_ms BETWEEN ? AND ?", window.from(), window.to());
        if (paging && filter.before() != null) {
            where = where.and("t.start_ms < ?", filter.before());
        }
        if (filter.minMs() != null) {
            where = where.and("t.duration_ns >= ? * 1000000", filter.minMs());
        }
        if (filter.maxMs() != null) {
            where = where.and("t.duration_ns <= ? * 1000000", filter.maxMs());
        }
        if ("error".equals(filter.status())) {
            where = where.and("t.error");
        } else if ("ok".equals(filter.status())) {
            where = where.and("NOT t.error");
        }
        if (filter.service() != null) {
            where = where.and("EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id"
                    + " AND s.service = ?)", filter.service());
        }
        if (filter.endpointId() != null) {
            where = where.and("EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id"
                    + " AND s.endpoint_id = ?)", filter.endpointId());
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String like = "%" + filter.q().toLowerCase(Locale.ROOT) + "%";
            where = where.and("EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id"
                    + " AND (LOWER(s.name) LIKE ? OR LOWER(s.attributes) LIKE ?))", like, like);
        }
        return where;
    }

    public @Nullable TraceDetail trace(String traceId) {
        List<SpanRecord> spans = sql.query(
                "SELECT " + Rows.SPAN_COLUMNS + " FROM span WHERE trace_id = ? ORDER BY start_ns",
                List.of(traceId), Rows::span);
        if (spans.isEmpty()) {
            return null;
        }
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        Set<String> serviceNames = new LinkedHashSet<>();
        for (SpanRecord span : spans) {
            start = Math.min(start, span.startNanos());
            end = Math.max(end, span.endNanos());
            serviceNames.add(span.service());
        }
        List<LogRecord> logs = sql.query("SELECT * FROM log WHERE trace_id = ? ORDER BY at_ms",
                List.of(traceId), Rows::log);
        return new TraceDetail(traceId, start / 1_000_000L, end / 1_000_000L,
                Math.max(0, end - start) / 1_000_000.0, List.copyOf(serviceNames), sorted(spans), logs);
    }

    /** Parents before children, each group by start; the waterfall draws in this order. */
    private static List<SpanRecord> sorted(List<SpanRecord> spans) {
        Map<String, List<SpanRecord>> children = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (SpanRecord span : spans) {
            ids.add(span.spanId());
        }
        List<SpanRecord> roots = new ArrayList<>();
        for (SpanRecord span : spans) {
            if (span.parentSpanId() == null || !ids.contains(span.parentSpanId())) {
                roots.add(span);
            } else {
                children.computeIfAbsent(span.parentSpanId(), id -> new ArrayList<>()).add(span);
            }
        }
        roots.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
        List<SpanRecord> ordered = new ArrayList<>(spans.size());
        for (SpanRecord root : roots) {
            append(ordered, root, children);
        }
        return ordered;
    }

    private static void append(List<SpanRecord> ordered, SpanRecord span,
            Map<String, List<SpanRecord>> children) {
        ordered.add(span);
        List<SpanRecord> kids = children.get(span.spanId());
        if (kids == null) {
            return;
        }
        kids.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
        for (SpanRecord kid : kids) {
            append(ordered, kid, children);
        }
    }

    /** The slowest traces that contain a span matching {@code predicate}. */
    public List<Stats.TraceSummary> tracesContaining(Window window, String predicate, String value,
            int limit, boolean slowest) {
        return tracesContaining(window, predicate, List.of(value), limit, slowest);
    }

    /** The same, for a predicate that binds more than one value (a job's service and name). */
    public List<Stats.TraceSummary> tracesContaining(Window window, String predicate,
            List<Object> values, int limit, boolean slowest) {
        List<Object> params = new ArrayList<>(values);
        params.add(window.from());
        params.add(window.to());
        Clause where = new Clause("t.start_ms BETWEEN ? AND ?", window.from(), window.to())
                // An IN over the matching spans rather than an EXISTS per trace: H2 reads
                // the spans by the predicate's index once instead of probing every trace.
                // The span's own window makes that read a range of the (…, start_ms) index,
                // the window's share of the group rather than its whole history (storage.adoc).
                .and("t.trace_id IN (SELECT s.trace_id FROM span s WHERE s." + predicate
                        + " AND s.start_ms BETWEEN ? AND ?)", params.toArray());
        String order = slowest ? "t.duration_ns DESC" : "t.start_ms DESC";
        return sql.query("SELECT * FROM trace t WHERE " + where.sql() + " ORDER BY " + order
                + " LIMIT " + Math.max(1, limit), where.params(), Rows::trace);
    }

    /**
     * The traces of the window among the given ids, slowest first.
     *
     * <p>{@link #tracesContaining} with a {@code trace_id IN} predicate answers the
     * same, but H2 runs its {@code EXISTS} once per trace of the window; the ids
     * are {@code trace}'s own key, so this reads only their rows.
     */
    public List<Stats.TraceSummary> slowestOf(Window window, Collection<String> traceIds, int limit) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(traceIds);
        params.add(window.from());
        params.add(window.to());
        return sql.query("SELECT * FROM trace t WHERE t.trace_id IN (" + Sql.placeholders(traceIds.size())
                + ") AND t.start_ms BETWEEN ? AND ? ORDER BY t.duration_ns DESC LIMIT " + Math.max(1, limit),
                params, Rows::trace);
    }

    // --- scatter -------------------------------------------------------------

    public List<Stats.ScatterPoint> scatter(Window window, @Nullable String service,
            @Nullable String endpointId, int limit) {
        Clause where = entryWindow(window, service);
        if (endpointId != null) {
            where = where.and("endpoint_id = ?", endpointId);
        }
        List<Object[]> rows = sql.query(
                "SELECT start_ms, duration_ns, service, endpoint, name, trace_id, error, slow"
                        + " FROM span WHERE " + where.sql() + " ORDER BY start_ms DESC LIMIT "
                        + Math.max(1, limit),
                where.params(), rs -> new Object[]{rs.getLong("start_ms"), rs.getLong("duration_ns"),
                        rs.getString("service"),
                        rs.getString("endpoint") != null ? rs.getString("endpoint") : rs.getString("name"),
                        rs.getString("trace_id"), rs.getBoolean("error"), rs.getBoolean("slow")});

        Set<String> traceIds = new HashSet<>();
        for (Object[] row : rows) {
            traceIds.add((String) row[4]);
        }
        Set<String> withSlowQuery = traceIds.isEmpty() ? Set.of() : new HashSet<>(sql.query(
                "SELECT DISTINCT trace_id FROM span WHERE db_statement IS NOT NULL AND slow"
                        + " AND trace_id IN (" + Sql.placeholders(traceIds.size()) + ")",
                Arrays.asList(traceIds.toArray()), rs -> rs.getString(1)));

        List<Stats.ScatterPoint> points = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            int flags = 0;
            if ((Boolean) row[5]) {
                flags |= Stats.ScatterPoint.ERROR;
            }
            if ((Boolean) row[6]) {
                flags |= Stats.ScatterPoint.SLOW;
            }
            if (withSlowQuery.contains((String) row[4])) {
                flags |= Stats.ScatterPoint.SLOW_QUERY;
            }
            points.add(new Stats.ScatterPoint((Long) row[0], Rows.ms((Long) row[1]), (String) row[2],
                    (String) row[3], (String) row[4], flags));
        }
        return points;
    }

    // --- tingles, logs, dependencies -----------------------------------------

    public List<Tingle> tingles(Window window, int limit) {
        return sql.query("SELECT * FROM tingle WHERE at_ms BETWEEN ? AND ?"
                        + " ORDER BY at_ms DESC, id DESC LIMIT " + Math.max(1, limit),
                List.of(window.from(), window.to()),
                rs -> new Tingle(rs.getString("kind"), rs.getLong("at_ms"), rs.getString("service"),
                        rs.getString("title"), rs.getString("detail"), rs.getString("trace_id"),
                        rs.getString("span_id"), rs.getDouble("duration_ms")));
    }

    public List<LogRecord> logs(LogFilter filter) {
        Clause where = logWhere(filter, true);
        return sql.query("SELECT * FROM log WHERE " + where.sql()
                + " ORDER BY at_ms DESC, id DESC LIMIT " + Math.max(1, filter.limit()),
                where.params(), Rows::log);
    }

    public long logTotal(LogFilter filter) {
        Clause where = logWhere(filter, false);
        return sql.count("SELECT COUNT(*) FROM log WHERE " + where.sql(), where.params());
    }

    private Clause logWhere(LogFilter filter, boolean paging) {
        Window window = filter.window();
        Clause where = new Clause("at_ms BETWEEN ? AND ?", window.from(), window.to());
        if (paging && filter.before() != null) {
            where = where.and("at_ms < ?", filter.before());
        }
        if (filter.service() != null) {
            where = where.and("service = ?", filter.service());
        }
        if (filter.traceId() != null) {
            where = where.and("trace_id = ?", filter.traceId());
        }
        if (filter.severity() != null) {
            where = where.and("severity_number >= ?",
                    (long) LogRecord.severityFloor(filter.severity()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String like = "%" + filter.q().toLowerCase(Locale.ROOT) + "%";
            where = where.and("(LOWER(body) LIKE ? OR LOWER(attributes) LIKE ?)", like, like);
        }
        return where;
    }

    /** What one service calls: its outbound spans grouped by target. */
    public List<Stats.Dependency> dependencies(String service, Window window) {
        Clause where = window(window, service)
                .and("NOT entry")
                .and("category IN ('http', 'db', 'messaging', 'rpc')");
        List<SpanRecord> spans = sql.query("SELECT " + Rows.SPAN_COLUMNS + " FROM span WHERE "
                + where.sql() + " LIMIT " + MAX_DEPENDENCY_ROWS, where.params(), Rows::span);

        Map<String, List<SpanRecord>> byTarget = new LinkedHashMap<>();
        for (SpanRecord span : spans) {
            byTarget.computeIfAbsent(span.category() + "\0" + target(span), k -> new ArrayList<>())
                    .add(span);
        }
        List<Stats.Dependency> dependencies = new ArrayList<>();
        byTarget.forEach((key, group) -> {
            String[] parts = key.split("\0", 2);
            CallStats stats = CallStats.of(group);
            dependencies.add(new Stats.Dependency(parts[0], parts[1], stats.calls(), stats.errors(),
                    stats.avgMs(), stats.p95Ms()));
        });
        dependencies.sort((a, b) -> Long.compare(b.calls(), a.calls()));
        return dependencies;
    }

    /**
     * What an outbound span called, as the dependency list and the service map name
     * it: a host and port, a database, a queue, an RPC service.
     *
     * <p>Public because a {@code slow-external} finding groups by the same value
     * (findings.adoc#slow-external), and two readings of "what did this call go to" would be two
     * different names for one dependency.
     */
    public static String target(SpanRecord span) {
        return switch (span.category()) {
            case "db" -> {
                String system = span.dbSystem() == null ? "db" : span.dbSystem();
                String namespace = span.dbNamespace();
                yield namespace == null ? system : system + ":" + namespace;
            }
            case "messaging" -> {
                String destination = span.attr("messaging.destination.name", "messaging.destination");
                yield destination == null ? String.valueOf(span.attr("messaging.system")) : destination;
            }
            case "rpc" -> {
                String rpcService = span.attr("rpc.service");
                yield rpcService == null ? hostPort(span) : rpcService;
            }
            default -> hostPort(span);
        };
    }

    private static String hostPort(SpanRecord span) {
        String host = span.serverAddress();
        if (host == null) {
            return "unknown";
        }
        Long port = span.serverPort();
        return port == null ? host : host + ":" + port;
    }

    /**
     * {@code GET localhost:8081/api/books/?}: what two outbound calls have to share
     * to be the same call (findings.adoc#repeated-call).
     *
     * <p>The span's name, the dependency {@link #target}, and the path and query of
     * {@code url.full} with every run of digits replaced, which is what a loop
     * varies. The host is taken from the target rather than from the URL, so the port
     * stays a port rather than becoming {@code ?}, and the status is left out because
     * the same call can answer differently for two items.
     *
     * <p>Public and shared, because an {@code n-plus-one-http} finding groups on it
     * and the aggregated hot spans name an outbound call with it: one call has one
     * name wherever it is reported.
     */
    public static String callName(SpanRecord span) {
        String target = target(span);
        String url = span.attr("url.full");
        if (url == null) {
            return span.name() + " " + target;
        }
        return span.name() + " " + target + Ids.normaliseDigits(pathOf(url));
    }

    /** The path and query of an absolute URL, or the whole of it when it is not one. */
    private static String pathOf(String url) {
        try {
            URI parsed = URI.create(url);
            String path = parsed.getRawPath();
            if (path == null) {
                return url;
            }
            String query = parsed.getRawQuery();
            return query == null ? path : path + "?" + query;
        } catch (IllegalArgumentException notAUrl) {
            return url;
        }
    }

    /**
     * The outbound HTTP calls of the window, row by row.
     *
     * <p>Read rather than aggregated because the group a {@code slow-external}
     * finding is about is {@code (service, target, span name)} and the target lives
     * in the attributes rather than in a column ({@link #target}); the same cap as
     * the dependency scan applies, for the same reason.
     */
    public List<SpanRecord> outboundHttp(Window window, @Nullable String service) {
        Clause where = window(window, service).and("kind = 'CLIENT'").and("category = 'http'");
        return sql.query("SELECT " + Rows.SPAN_COLUMNS + " FROM span WHERE " + where.sql()
                + " LIMIT " + MAX_DEPENDENCY_ROWS, where.params(), Rows::span);
    }

    /** Nearest-rank, the same definition {@code PERCENTILE_DISC} uses. */
    static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    // --- where the time went -------------------------------------------------

    /** One summary a set of traces spent self time in (findings.adoc#time). */
    public record HotSpan(String name, String category, double selfMs, double share, long count) {
    }

    /**
     * Where a set of traces spent their time: the three hottest summaries and the
     * four shares.
     *
     * @param hotSpans  the largest {@code selfMs} first, at most three, empty when
     *        there is nothing to read
     * @param breakdown {@code db}, {@code http}, {@code internal} and {@code self},
     *        summing to 1; empty when {@code hotSpans} is
     */
    public record TimeSplit(List<HotSpan> hotSpans, Map<String, Double> breakdown) {

        public static final TimeSplit NONE = new TimeSplit(List.of(), Map.of());
    }

    /** How many summaries a {@link TimeSplit} names (findings.adoc#time). */
    private static final int HOT_SPANS = 3;

    /** The four shares of a breakdown, in the order they are written (findings.adoc#time). */
    private static final List<String> BUCKETS = List.of("db", "http", "internal", "self");

    private static final double[] EMPTY_SUM = new double[2];

    /**
     * The aggregated answer to "where did the time go", over one sample of traces.
     *
     * <p>One trace is an anecdote, so a {@code slow-endpoint} and a {@code slow-job}
     * ask it over the twenty slowest traces they have (findings.adoc#time),
     * and the endpoint page asks it over the twenty it already shows. It is
     * read out of the spans rather than out of an aggregate, because a summary is
     * built from the attributes and not from a column, which is why the sample is
     * bounded rather than the window.
     *
     * <p>Only the spans of {@code service} count, the rule {@link #databaseWork}
     * already uses: the database work of a downstream service belongs to that
     * service's own endpoint, and leaving its server span out is what makes the wait
     * on an outbound call land in {@code http} where the caller can see it.
     */
    public TimeSplit timeSplit(Window window, String service, List<String> traceIds) {
        if (traceIds.isEmpty()) {
            return TimeSplit.NONE;
        }
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to(), service));
        params.addAll(traceIds);
        List<SpanRecord> spans = sql.query("SELECT " + Rows.SPAN_COLUMNS + " FROM span"
                        + " WHERE start_ms BETWEEN ? AND ? AND service = ? AND trace_id IN ("
                        + Sql.placeholders(traceIds.size()) + ") LIMIT " + MAX_DEPENDENCY_ROWS,
                params, Rows::span);
        if (spans.isEmpty()) {
            return TimeSplit.NONE;
        }

        Set<String> known = new HashSet<>();
        for (SpanRecord span : spans) {
            known.add(span.spanId());
        }
        Map<String, Long> childNanos = new HashMap<>();
        for (SpanRecord span : spans) {
            String parent = span.parentSpanId();
            if (parent != null && known.contains(parent)) {
                childNanos.merge(parent, span.durationNanos(), Long::sum);
            }
        }

        double totalMs = 0;
        // Insertion order is the order the JSON and the text print, so it is written out
        // rather than taken from a Map.of, whose iteration order is not specified.
        Map<String, Double> shares = new LinkedHashMap<>();
        for (String bucket : BUCKETS) {
            shares.put(bucket, 0.0);
        }
        Map<String, double[]> hottest = new LinkedHashMap<>();
        Map<String, String[]> named = new LinkedHashMap<>();
        for (SpanRecord span : spans) {
            double selfMs = Math.max(0, span.durationNanos()
                    - childNanos.getOrDefault(span.spanId(), 0L)) / 1_000_000.0;
            String parent = span.parentSpanId();
            if (parent == null || !known.contains(parent)) {
                // A top span: the entry span of a request, the root span of a job. Its own
                // self time is the request's own code, and its duration is what the shares
                // are taken over.
                totalMs += span.durationMillis();
                shares.merge("self", selfMs, Double::sum);
                continue;
            }
            String bucket = switch (span.category()) {
                case "db", "http" -> span.category();
                default -> "internal";
            };
            shares.merge(bucket, selfMs, Double::sum);
            // An outbound call is named as an n-plus-one-http names it, so the same call
            // reads the same in both; everything else is its summary, digits replaced.
            String name = "http".equals(span.category()) && "CLIENT".equals(span.kind())
                    ? callName(span) : Ids.normaliseDigits(span.summary());
            String key = span.category() + "\0" + name;
            named.putIfAbsent(key, new String[]{name, span.category()});
            double[] summed = hottest.computeIfAbsent(key, k -> new double[2]);
            summed[0] += selfMs;
            summed[1]++;
        }
        if (totalMs <= 0) {
            return TimeSplit.NONE;
        }

        double total = totalMs;
        shares.replaceAll((bucket, ms) -> Math.min(1, ms / total));
        List<HotSpan> hotSpans = new ArrayList<>();
        // Keyed by category and summary, but named by the summary alone: two categories
        // never produce the same one, and the key is not what a reader wants to see.
        named.forEach((key, name) -> {
            double[] summed = hottest.getOrDefault(key, EMPTY_SUM);
            hotSpans.add(new HotSpan(name[0], name[1], summed[0],
                    Math.min(1, summed[0] / total), (long) summed[1]));
        });
        hotSpans.sort(Comparator.comparingDouble(HotSpan::selfMs).reversed()
                .thenComparing(HotSpan::name));
        return new TimeSplit(
                List.copyOf(hotSpans.subList(0, Math.min(HOT_SPANS, hotSpans.size()))),
                Collections.unmodifiableMap(shares));
    }

    // --- the service map -----------------------------------------------------

    /**
     * The topology of the window: who called whom, as the map draws it.
     *
     * <p>Four reads make it. The self-join over parent and child spans finds the
     * calls between two traced services; the entry spans with no stored parent are
     * the traffic from outside, which becomes the {@code user} node; the outbound
     * spans are the databases, hosts and queues nobody traces. An outbound span
     * whose child is another service's entry span is already one of the first kind,
     * so it is dropped from the third: a call to {@code localhost:8081} shows as a
     * call to the bookstore when the bookstore is traced too.
     *
     * <p>A service node's numbers are the {@link Stats.ServiceSummary} numbers, so
     * the map and the service page can never disagree.
     */
    public Stats.ServiceMap map(Window window) {
        Map<String, Stats.ServiceSummary> summaries = new LinkedHashMap<>();
        Set<String> named = new LinkedHashSet<>();
        for (Stats.ServiceSummary summary : services(window)) {
            summaries.put(summary.name(), summary);
            if (summary.totals().requests() > 0) {
                named.add(summary.name());
            }
        }
        List<Stats.Edge> edges = new ArrayList<>(serviceEdges(window, named));
        edges.addAll(userEdges(window));
        List<Stats.Node> targets = new ArrayList<>();
        edges.addAll(targetEdges(window, targets));
        // A service with no request of its own, a worker whose jobs still call a
        // database, is on the map through the edges it starts: one node per service.
        for (Stats.Edge edge : edges) {
            if (edge.from().startsWith("svc:")) {
                named.add(edge.from().substring("svc:".length()));
            }
        }

        Map<String, Stats.Node> nodes = new LinkedHashMap<>();
        nodes.put("user", Stats.Node.user());
        for (String name : named) {
            Stats.ServiceSummary summary = summaries.getOrDefault(name,
                    new Stats.ServiceSummary(name, null, false, 0, 0, Stats.Totals.EMPTY,
                            new long[window.bucketCount()], false));
            nodes.put("svc:" + name, Stats.Node.service(summary));
        }
        for (Stats.Node target : targets) {
            nodes.put(target.id(), target);
        }

        Set<String> connected = new HashSet<>();
        for (Stats.Edge edge : edges) {
            connected.add(edge.from());
            connected.add(edge.to());
        }
        List<Stats.Node> listed = new ArrayList<>();
        for (Stats.Node node : nodes.values()) {
            if (connected.contains(node.id())) {
                listed.add(node);
            }
        }
        listed.sort(Queries::byNodeOrder);
        edges.sort((a, b) -> Long.compare(b.calls(), a.calls()));
        return new Stats.ServiceMap(listed, edges);
    }

    /** The user first, then the services by name, then everything they call. */
    private static int byNodeOrder(Stats.Node a, Stats.Node b) {
        int rank = Integer.compare(rank(a), rank(b));
        return rank != 0 ? rank : a.name().compareTo(b.name());
    }

    private static int rank(Stats.Node node) {
        if ("user".equals(node.kind())) {
            return 0;
        }
        return node.isService() ? 1 : 2;
    }

    /** One edge per pair of services whose spans are parent and child in a trace. */
    private List<Stats.Edge> serviceEdges(Window window, Set<String> named) {
        List<Stats.Edge> edges = sql.query(
                "SELECT p.service AS caller, c.service AS callee, COUNT(*) AS calls,"
                        + " SUM(CASE WHEN c.error THEN 1 ELSE 0 END) AS errors,"
                        + " SUM(c.duration_ns) AS total_ns,"
                        + " PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY c.duration_ns) AS p95_ns"
                        + " FROM span c JOIN span p ON p.trace_id = c.trace_id"
                        + " AND p.span_id = c.parent_span_id"
                        + " WHERE c.start_ms BETWEEN ? AND ? AND c.entry AND p.service <> c.service"
                        + " GROUP BY p.service, c.service",
                List.of(window.from(), window.to()), rs -> {
                    long calls = rs.getLong("calls");
                    return new Stats.Edge("svc:" + rs.getString("caller"),
                            "svc:" + rs.getString("callee"), calls, rs.getLong("errors"),
                            calls == 0 ? 0 : Rows.ms(rs, "total_ns") / calls, Rows.ms(rs, "p95_ns"));
                });
        for (Stats.Edge edge : edges) {
            // The caller is on the map even when it served no request of its own.
            named.add(edge.from().substring("svc:".length()));
        }
        return edges;
    }

    /** Entry spans that nothing traced started: the traffic from outside. */
    private List<Stats.Edge> userEdges(Window window) {
        return sql.query("SELECT c.service AS callee, COUNT(*) AS calls,"
                        + " SUM(CASE WHEN c.error THEN 1 ELSE 0 END) AS errors,"
                        + " SUM(c.duration_ns) AS total_ns,"
                        + " PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY c.duration_ns) AS p95_ns"
                        + " FROM span c WHERE c.start_ms BETWEEN ? AND ? AND c.entry"
                        + " AND c.kind IN ('SERVER', 'CONSUMER')"
                        + " AND (c.parent_span_id IS NULL OR NOT EXISTS (SELECT 1 FROM span p"
                        + " WHERE p.trace_id = c.trace_id AND p.span_id = c.parent_span_id))"
                        + " GROUP BY c.service",
                List.of(window.from(), window.to()), rs -> {
                    long calls = rs.getLong("calls");
                    return new Stats.Edge("user", "svc:" + rs.getString("callee"), calls,
                            rs.getLong("errors"),
                            calls == 0 ? 0 : Rows.ms(rs, "total_ns") / calls, Rows.ms(rs, "p95_ns"));
                });
    }

    /**
     * The databases, hosts and queues every service calls, and one edge per caller.
     * The outbound spans that turned out to be calls to another traced service are
     * left out: they are already {@code service → service} edges.
     */
    private List<Stats.Edge> targetEdges(Window window, List<Stats.Node> nodes) {
        Set<String> crossService = new HashSet<>(sql.query(
                "SELECT DISTINCT c.parent_span_id FROM span c JOIN span p"
                        + " ON p.trace_id = c.trace_id AND p.span_id = c.parent_span_id"
                        + " WHERE c.start_ms BETWEEN ? AND ? AND c.entry AND p.service <> c.service",
                List.of(window.from(), window.to()), rs -> rs.getString(1)));

        Clause where = window(window, null)
                .and("NOT entry")
                .and("category IN ('http', 'db', 'messaging', 'rpc')");
        List<SpanRecord> spans = sql.query("SELECT " + Rows.SPAN_COLUMNS + " FROM span WHERE "
                + where.sql() + " LIMIT " + MAX_DEPENDENCY_ROWS, where.params(), Rows::span);

        Map<String, List<SpanRecord>> byTarget = new LinkedHashMap<>();
        Map<String, List<SpanRecord>> byCaller = new LinkedHashMap<>();
        for (SpanRecord span : spans) {
            if (crossService.contains(span.spanId())) {
                continue;
            }
            String id = span.category() + ":" + target(span);
            byTarget.computeIfAbsent(id, k -> new ArrayList<>()).add(span);
            byCaller.computeIfAbsent(id + "\0" + span.service(), k -> new ArrayList<>()).add(span);
        }
        byTarget.forEach((id, group) -> {
            CallStats stats = CallStats.of(group);
            nodes.add(Stats.Node.target(group.get(0).category(), target(group.get(0)),
                    stats.calls(), stats.errors(), stats.avgMs(), stats.p95Ms()));
        });
        List<Stats.Edge> edges = new ArrayList<>();
        byCaller.forEach((key, group) -> {
            String[] parts = key.split("\0", 2);
            CallStats stats = CallStats.of(group);
            edges.add(new Stats.Edge("svc:" + parts[1], parts[0], stats.calls(), stats.errors(),
                    stats.avgMs(), stats.p95Ms()));
        });
        return edges;
    }

    /** Calls, errors, mean and p95 over a group of spans read row by row. */
    private record CallStats(long calls, long errors, double avgMs, double p95Ms) {

        static CallStats of(List<SpanRecord> spans) {
            double[] durations = new double[spans.size()];
            long errors = 0;
            double total = 0;
            for (int i = 0; i < spans.size(); i++) {
                durations[i] = spans.get(i).durationMillis();
                total += durations[i];
                if (spans.get(i).isError()) {
                    errors++;
                }
            }
            Arrays.sort(durations);
            return new CallStats(spans.size(), errors,
                    spans.isEmpty() ? 0 : total / spans.size(), percentile(durations, 0.95));
        }
    }

    // --- counts --------------------------------------------------------------

    public long spanCount() {
        return sql.count("SELECT COUNT(*) FROM span", List.of());
    }

    public long traceCount() {
        return sql.count("SELECT COUNT(*) FROM trace", List.of());
    }

    public long logCount() {
        return sql.count("SELECT COUNT(*) FROM log", List.of());
    }

    public long metricSeriesCount() {
        return sql.count("SELECT COUNT(*) FROM metric_series", List.of());
    }

    public long oldestSpan() {
        Long value = sql.queryOne("SELECT MIN(start_ms) FROM span", List.of(),
                rs -> rs.getLong(1));
        return value == null ? 0 : value;
    }

    public long oldestLog() {
        Long value = sql.queryOne("SELECT MIN(at_ms) FROM log", List.of(), rs -> rs.getLong(1));
        return value == null ? 0 : value;
    }

    // --- building blocks -----------------------------------------------------

    private static Clause window(Window window, @Nullable String service) {
        Clause where = new Clause("start_ms BETWEEN ? AND ?", window.from(), window.to());
        return service == null ? where : where.and("service = ?", service);
    }

    private static Clause entryWindow(Window window, @Nullable String service) {
        return window(window, service).and("entry");
    }

    /** A growing {@code WHERE} with its parameters beside it. */
    private record Clause(String sql, List<Object> params) {

        Clause(String sql, Object... params) {
            this(sql, Arrays.asList(params));
        }

        Clause and(String more, Object... extra) {
            List<Object> combined = new ArrayList<>(params);
            combined.addAll(Arrays.asList(extra));
            return new Clause(sql + " AND " + more, combined);
        }
    }

    /**
     * Which entry span a span belongs to, resolved by walking the parent chain.
     *
     * <p>Loaded as six columns over the window once and kept for the length of one
     * request. A recursive SQL walk would be exact to the row, but this answers
     * every query and every error group of one page from a single scan, which
     * storage.adoc accepts at local-development volumes.
     */
    record Ancestry(Map<String, Entry> entries, Map<String, String> parents) {

        /** The entry span itself, so a finding can count the requests it affected. */
        record Entry(String spanId, String endpoint, String service) {
        }

        /**
         * The walk over the traces of the window that have a span of {@code service}
         * in it, or over every trace when it is null.
         *
         * <p>A walk never leaves its trace, so the traces without a span of the
         * service hold nothing a span of the service can reach: leaving them out
         * changes no answer, and on a store shared by several applications it is
         * most of the rows (storage.adoc#reads).
         */
        static Ancestry of(Sql sql, Window window, @Nullable String service) {
            Map<String, Entry> entries = new HashMap<>();
            Map<String, String> parents = new HashMap<>();
            String where = "start_ms BETWEEN ? AND ?";
            List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
            if (service != null) {
                where = where + " AND trace_id IN (SELECT trace_id FROM span WHERE service = ?"
                        + " AND start_ms BETWEEN ? AND ?)";
                params.addAll(List.of(service, window.from(), window.to()));
            }
            sql.forEach("SELECT span_id, parent_span_id, entry, endpoint, service, name FROM span"
                    + " WHERE " + where, params, rs -> {
                        String spanId = rs.getString("span_id");
                        String parent = rs.getString("parent_span_id");
                        if (parent != null) {
                            parents.put(spanId, parent);
                        }
                        if (rs.getBoolean("entry")) {
                            String endpoint = rs.getString("endpoint");
                            entries.put(spanId, new Entry(spanId,
                                    endpoint == null ? rs.getString("name") : endpoint,
                                    rs.getString("service")));
                        }
                    });
            return new Ancestry(entries, parents);
        }

        @Nullable Entry entryOf(@Nullable String spanId) {
            String current = spanId;
            for (int depth = 0; current != null && depth < 64; depth++) {
                Entry entry = entries.get(current);
                if (entry != null) {
                    return entry;
                }
                current = parents.get(current);
            }
            return null;
        }
    }
}
