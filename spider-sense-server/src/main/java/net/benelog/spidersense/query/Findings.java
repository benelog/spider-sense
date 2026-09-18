package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.Ids;
import net.benelog.spidersense.store.ServiceInfo;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.Tingles;

/**
 * The primary answer an agent gets: a ranked, bounded list of things worth fixing.
 *
 * <p>A dashboard shows series and lets a person find the cluster; an agent pays
 * for every token and needs a verdict. So every rule here ends in one line with
 * the numbers that justify it, the trace ids that prove it and, where the stock
 * OpenTelemetry agent recorded enough, the code location (agent.md).
 *
 * <p>Nothing is computed twice: the rules run over the same {@link Queries} the UI
 * uses, so a number in a finding is the number on the screen.
 */
public final class Findings {

    public static final String ERROR = "error";
    public static final String N_PLUS_ONE = "n-plus-one";
    public static final String SLOW_QUERY = "slow-query";
    public static final String SLOW_ENDPOINT = "slow-endpoint";
    public static final String POOL_EXHAUSTED = "pool-exhausted";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    /** The repeats within one trace that make a query group an N+1 (storage.md). */
    private static final int REPEATS = 5;

    /** Repeats at which an N+1 stops being a nuisance and becomes the bug. */
    private static final int LOUD_REPEATS = 20;

    private static final int EVIDENCE_TRACES = 3;
    private static final int CANDIDATES = 500;
    private static final int GROUPS = 100;

    /** Which group a finding is about; the fields that do not apply are null. */
    public record Subject(String endpointId, String queryId, String errorId, String pool) {
    }

    /**
     * One thing worth fixing.
     *
     * @param numbers the kind-specific numbers agent.md lists, in the order it
     *        lists them; values are numbers, strings, or lists of small maps
     */
    public record Finding(String id, String kind, String severity, String service, String title,
            String why, Subject subject, Map<String, Object> numbers, String statement,
            List<String> code, List<String> traces) {
    }

    private final Sql sql;
    private final Queries queries;
    private final MetricQueries metrics;
    private final ServiceRegistry services;
    private final Tingles tingles;
    private final CodeFrames frames;

    public Findings(Sql sql, Queries queries, MetricQueries metrics, ServiceRegistry services,
            Tingles tingles, CodeFrames frames) {
        this.sql = sql;
        this.queries = queries;
        this.metrics = metrics;
        this.services = services;
        this.tingles = tingles;
        this.frames = frames;
    }

    /**
     * Every rule over the window, ranked.
     *
     * <p>Severity first, then the impact within a kind, then the id: two calls over
     * the same data answer in the same order, so an agent can diff them. Impacts of
     * different kinds are different units and are never compared with each other;
     * the kinds keep the order agent.md's table has.
     */
    public List<Finding> findings(Window window, String service, int limit) {
        List<Ranked> found = new ArrayList<>();
        found.addAll(errors(window, service));
        found.addAll(nPlusOne(window, service));
        found.addAll(slowQueries(window, service));
        found.addAll(slowEndpoints(window, service));
        found.addAll(poolExhausted(window, service));
        found.sort(Ranked.ORDER);

        List<Finding> ranked = new ArrayList<>(Math.min(found.size(), Math.max(1, limit)));
        for (Ranked each : found) {
            if (ranked.size() >= limit) {
                break;
            }
            ranked.add(each.finding());
        }
        return ranked;
    }

    /** A finding with the impact it is ranked by inside its kind. */
    private record Ranked(Finding finding, double impact) {

        private static final List<String> KINDS =
                List.of(ERROR, N_PLUS_ONE, SLOW_QUERY, SLOW_ENDPOINT, POOL_EXHAUSTED);

        static final Comparator<Ranked> ORDER = Comparator
                .comparingInt((Ranked r) -> severityRank(r.finding().severity()))
                .thenComparingInt(r -> KINDS.indexOf(r.finding().kind()))
                .thenComparing(Comparator.comparingDouble(Ranked::impact).reversed())
                .thenComparing(r -> r.finding().id());

        private static int severityRank(String severity) {
            return switch (severity) {
                case HIGH -> 0;
                case MEDIUM -> 1;
                default -> 2;
            };
        }
    }

    // --- error ---------------------------------------------------------------

    private List<Ranked> errors(Window window, String service) {
        List<Ranked> found = new ArrayList<>();
        for (Stats.ErrorGroup group : queries.errors(window, service, GROUPS, null)) {
            if (group.count() <= 0) {
                continue;
            }
            String where = group.endpoints().isEmpty() ? group.service() : group.endpoints().get(0).name();
            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("count", group.count());
            numbers.put("firstSeen", group.firstSeen());
            numbers.put("lastSeen", group.lastSeen());
            numbers.put("type", group.type());
            numbers.put("message", group.message());
            numbers.put("endpoints", endpointCounts(group.endpoints()));

            String stacktrace = group.sample() == null ? null : group.sample().stacktrace();
            Finding finding = new Finding(
                    id(ERROR, group.service(), group.errorId()),
                    ERROR, HIGH, group.service(),
                    simpleName(group.type()) + " in " + where,
                    Numbers.plural(group.count(), "occurrence") + " in " + where + "; " + group.message(),
                    new Subject(null, null, group.errorId(), null),
                    numbers, null,
                    frames.ofStacktrace(stacktrace),
                    traceIds(queries.tracesContaining(window, "error_id = ?", group.errorId(),
                            EVIDENCE_TRACES, false)));
            found.add(new Ranked(finding, group.count()));
        }
        return found;
    }

    // --- n + 1 ---------------------------------------------------------------

    /** One repeated statement under one entry span. */
    private record Repeat(String traceId, String endpointId, String endpoint, String service,
            String queryId, String statement, String queryName, int repeats, double totalMs,
            long start, Map<String, Object> attributes) {
    }

    /**
     * The same query group, five or more times under one entry span.
     *
     * <p>The candidates come from {@code GROUP BY trace_id, query_id} (storage.md),
     * which is one aggregate over the window; the spans of those pairs are then read
     * back and attributed to their entry span by the parent-chain walk the query
     * callers already use, because two endpoints of one trace each running the
     * statement four times is not an N+1 and grouping by trace alone cannot tell.
     */
    private List<Ranked> nPlusOne(Window window, String service) {
        List<String[]> candidates = candidates(window, service);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> traceIds = new LinkedHashSet<>();
        Set<String> queryIds = new LinkedHashSet<>();
        Set<String> pairs = new LinkedHashSet<>();
        for (String[] candidate : candidates) {
            traceIds.add(candidate[0]);
            queryIds.add(candidate[1]);
            pairs.add(candidate[0] + "\0" + candidate[1]);
        }

        List<Object> params = new ArrayList<>();
        params.add(window.from());
        params.add(window.to());
        params.addAll(traceIds);
        params.addAll(queryIds);
        String where = "start_ms BETWEEN ? AND ? AND trace_id IN (" + Sql.placeholders(traceIds.size())
                + ") AND query_id IN (" + Sql.placeholders(queryIds.size()) + ")";
        if (service != null) {
            where = where + " AND service = ?";
            params.add(service);
        }
        Queries.Ancestry ancestry = Queries.Ancestry.of(sql, window);
        Map<String, List<Repeat>> byEntry = new LinkedHashMap<>();
        sql.query("SELECT span_id, trace_id, query_id, service, start_ms, duration_ns, db_statement,"
                + " db_operation, db_table, attributes FROM span WHERE " + where, params, rs -> {
                    String traceId = rs.getString("trace_id");
                    String queryId = rs.getString("query_id");
                    if (!pairs.contains(traceId + "\0" + queryId)) {
                        return null;
                    }
                    Queries.Ancestry.Entry entry = ancestry.entryOf(rs.getString("span_id"));
                    if (entry == null) {
                        return null;
                    }
                    String endpointId = Ids.endpointId(entry.service(), entry.endpoint());
                    byEntry.computeIfAbsent(entry.spanId() + "\0" + queryId, key -> new ArrayList<>())
                            .add(new Repeat(traceId, endpointId, entry.endpoint(), entry.service(),
                                    queryId, rs.getString("db_statement"),
                                    queryName(rs.getString("db_operation"), rs.getString("db_table"),
                                            rs.getString("db_statement")),
                                    1, rs.getLong("duration_ns") / 1_000_000.0, rs.getLong("start_ms"),
                                    AttrJson.decode(rs.getString("attributes"))));
                    return null;
                });

        Map<String, List<Repeat>> byEndpointAndQuery = new LinkedHashMap<>();
        byEntry.values().forEach(spans -> {
            if (spans.size() < REPEATS) {
                return;
            }
            Repeat first = spans.get(0);
            double totalMs = 0;
            long start = Long.MAX_VALUE;
            // The extension captures the stack on the fifth repeat, so that one span of the group
            // knows where the statement is issued from; the others say nothing about it.
            Map<String, Object> attributes = first.attributes();
            boolean located = false;
            for (Repeat span : spans) {
                totalMs += span.totalMs();
                start = Math.min(start, span.start());
                if (!located && span.attributes() != null
                        && span.attributes().containsKey("code.stacktrace")) {
                    attributes = span.attributes();
                    located = true;
                }
            }
            byEndpointAndQuery
                    .computeIfAbsent(first.endpointId() + "\0" + first.queryId(), key -> new ArrayList<>())
                    .add(new Repeat(first.traceId(), first.endpointId(), first.endpoint(), first.service(),
                            first.queryId(), first.statement(), first.queryName(), spans.size(), totalMs,
                            start, attributes));
        });

        Map<String, Long> requestsByEndpoint = new HashMap<>();
        List<Ranked> found = new ArrayList<>();
        byEndpointAndQuery.values().forEach(affected -> {
            Repeat first = affected.get(0);
            long requests = requestsByEndpoint.computeIfAbsent(first.endpointId(),
                    id -> requests(window, service, id));
            int[] repeats = new int[affected.size()];
            double totalMs = 0;
            for (int i = 0; i < affected.size(); i++) {
                repeats[i] = affected.get(i).repeats();
                totalMs += affected.get(i).totalMs();
            }
            Arrays.sort(repeats);
            long median = repeats[(int) Math.ceil(0.5 * repeats.length) - 1];
            long max = repeats[repeats.length - 1];
            double msPerRequest = totalMs / affected.size();

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("requests", requests);
            numbers.put("affected", (long) affected.size());
            numbers.put("medianRepeats", median);
            numbers.put("maxRepeats", max);
            numbers.put("msPerRequest", msPerRequest);

            String severity = median >= LOUD_REPEATS || msPerRequest > tingles.slowRequestMs()
                    ? HIGH : MEDIUM;
            List<Repeat> newest = new ArrayList<>(affected);
            newest.sort(Comparator.comparingLong(Repeat::start).reversed());
            List<String> traces = new ArrayList<>();
            List<String> code = List.of();
            for (Repeat repeat : newest) {
                if (traces.size() < EVIDENCE_TRACES && !traces.contains(repeat.traceId())) {
                    traces.add(repeat.traceId());
                }
                if (code.isEmpty()) {
                    // The newest request that has a code location wins; none has one when the
                    // application ran without the extension, and then the finding names no line.
                    code = frames.ofAttributes(repeat.attributes());
                }
            }
            Finding finding = new Finding(
                    id(N_PLUS_ONE, first.service(), first.endpointId() + "\0" + first.queryId()),
                    N_PLUS_ONE, severity, first.service(),
                    first.endpoint() + " runs " + first.queryName() + " " + median + " times per request",
                    affected.size() + " of " + Numbers.plural(requests, "request") + " repeated it; "
                            + counts(repeats) + " times; " + Numbers.millis(msPerRequest)
                            + " per request in that statement",
                    new Subject(first.endpointId(), first.queryId(), null, null),
                    numbers, first.statement(),
                    code,
                    List.copyOf(traces));
            found.add(new Ranked(finding, affected.size() * (double) median));
        });
        return found;
    }

    private List<String[]> candidates(Window window, String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = "start_ms BETWEEN ? AND ? AND query_id IS NOT NULL";
        if (service != null) {
            where = where + " AND service = ?";
            params.add(service);
        }
        return sql.query("SELECT trace_id, query_id FROM span WHERE " + where
                        + " GROUP BY trace_id, query_id HAVING COUNT(*) >= " + REPEATS
                        + " ORDER BY COUNT(*) DESC LIMIT " + CANDIDATES,
                params, rs -> new String[]{rs.getString("trace_id"), rs.getString("query_id")});
    }

    private long requests(Window window, String service, String endpointId) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to(), endpointId));
        String where = "start_ms BETWEEN ? AND ? AND entry AND endpoint_id = ?";
        if (service != null) {
            where = where + " AND service = ?";
            params.add(service);
        }
        return sql.count("SELECT COUNT(*) FROM span WHERE " + where, params);
    }

    // --- slow query ----------------------------------------------------------

    private List<Ranked> slowQueries(Window window, String service) {
        List<Stats.QueryStats> slow = new ArrayList<>();
        for (Stats.QueryStats query : queries.queries(window, service, "total", GROUPS, null)) {
            if (query.p95Ms() > tingles.slowQueryMs()) {
                slow.add(query);
            }
        }
        if (slow.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Stats.QueryStats query : slow) {
            ids.add(query.queryId());
        }
        Map<String, Map<String, Object>> samples = sampleAttributes(window, "query_id", ids);

        List<Ranked> found = new ArrayList<>();
        for (Stats.QueryStats query : slow) {
            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("calls", query.calls());
            numbers.put("slowCalls", query.slowCalls());
            numbers.put("p50Ms", query.p50Ms());
            numbers.put("p95Ms", query.p95Ms());
            numbers.put("maxMs", query.maxMs());
            numbers.put("totalMs", query.totalMs());
            numbers.put("callers", callers(query.callers()));

            String name = queryName(query.operation(), query.table(), query.statement());
            String severity = query.p95Ms() > 10 * tingles.slowQueryMs() ? HIGH : MEDIUM;
            Finding finding = new Finding(
                    id(SLOW_QUERY, query.service(), query.queryId()),
                    SLOW_QUERY, severity, query.service(),
                    name + " is slow",
                    "p95 " + Numbers.millis(query.p95Ms()) + " over "
                            + Numbers.plural(query.calls(), "call") + ", "
                            + query.slowCalls() + " of them over " + tingles.slowQueryMs() + " ms; "
                            + Numbers.millis(query.totalMs()) + " in total",
                    new Subject(null, query.queryId(), null, null),
                    numbers, query.statement(),
                    frames.ofAttributes(samples.get(query.queryId())),
                    traceIds(queries.tracesContaining(window, "query_id = ?", query.queryId(),
                            EVIDENCE_TRACES, true)));
            found.add(new Ranked(finding, query.totalMs()));
        }
        return found;
    }

    // --- slow endpoint -------------------------------------------------------

    private List<Ranked> slowEndpoints(Window window, String service) {
        List<Stats.EndpointStats> slow = new ArrayList<>();
        for (Stats.EndpointStats endpoint : queries.endpoints(window, service, null)) {
            if (endpoint.p95Ms() > tingles.slowRequestMs()) {
                slow.add(endpoint);
            }
        }
        if (slow.isEmpty()) {
            return List.of();
        }
        Map<String, Queries.DbWork> databaseWork = queries.databaseWork(window, service);

        List<Ranked> found = new ArrayList<>();
        for (Stats.EndpointStats endpoint : slow) {
            Queries.DbWork work = databaseWork.getOrDefault(endpoint.endpointId(), Queries.DbWork.NONE);
            double perRequest = endpoint.calls() == 0 ? 0 : (double) work.calls() / endpoint.calls();
            double msPerRequest = endpoint.calls() == 0 ? 0 : work.totalMs() / endpoint.calls();
            double share = endpoint.totalMs() <= 0 ? 0
                    : Math.min(1, work.totalMs() / endpoint.totalMs());

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("calls", endpoint.calls());
            numbers.put("p50Ms", endpoint.p50Ms());
            numbers.put("p95Ms", endpoint.p95Ms());
            numbers.put("maxMs", endpoint.maxMs());
            numbers.put("totalMs", endpoint.totalMs());
            numbers.put("apdex", endpoint.apdex());
            numbers.put("dbCallsPerRequest", perRequest);
            numbers.put("dbMsPerRequest", msPerRequest);
            numbers.put("dbShare", share);

            String severity = endpoint.p95Ms() > 4 * tingles.slowRequestMs() ? HIGH : MEDIUM;
            Finding finding = new Finding(
                    id(SLOW_ENDPOINT, endpoint.service(), endpoint.endpointId()),
                    SLOW_ENDPOINT, severity, endpoint.service(),
                    endpoint.name() + " is slow",
                    "p95 " + Numbers.millis(endpoint.p95Ms()) + " over "
                            + Numbers.plural(endpoint.calls(), "call") + "; "
                            + Numbers.number(perRequest) + " database calls and "
                            + Numbers.millis(msPerRequest) + " per request, "
                            + Numbers.percent(share) + " of the time",
                    new Subject(endpoint.endpointId(), null, null, null),
                    numbers, null, List.of(),
                    traceIds(queries.tracesContaining(window, "endpoint_id = ?", endpoint.endpointId(),
                            EVIDENCE_TRACES, true)));
            found.add(new Ranked(finding, endpoint.totalMs()));
        }
        return found;
    }

    // --- pool exhausted ------------------------------------------------------

    private List<Ranked> poolExhausted(Window window, String service) {
        List<Ranked> found = new ArrayList<>();
        for (String name : servicesInScope(service)) {
            for (JvmView.ConnectionPool pool : JvmView.connectionPools(metrics, name, window)) {
                Ranked exhausted = exhausted(name, pool);
                if (exhausted != null) {
                    found.add(exhausted);
                }
            }
        }
        return found;
    }

    /**
     * The worst point of one pool, when it had one.
     *
     * <p>"Pending above zero" is a request that waited for a connection, and "used
     * equals max" is the moment the next one would have: both are the pool being the
     * bottleneck rather than the database.
     */
    private Ranked exhausted(String service, JvmView.ConnectionPool pool) {
        long at = 0;
        double worstPending = 0;
        double worstUsed = 0;
        double max = Double.NaN;
        boolean exhausted = false;
        for (int i = 0; i < pool.t().length; i++) {
            double pending = pool.pending().length > i ? pool.pending()[i] : Double.NaN;
            double used = pool.used().length > i ? pool.used()[i] : Double.NaN;
            double limit = pool.max().length > i ? pool.max()[i] : Double.NaN;
            boolean waiting = !Double.isNaN(pending) && pending > 0;
            boolean full = !Double.isNaN(used) && !Double.isNaN(limit) && limit > 0 && used >= limit;
            if (!waiting && !full) {
                continue;
            }
            exhausted = true;
            double pendingValue = Double.isNaN(pending) ? 0 : pending;
            double usedValue = Double.isNaN(used) ? 0 : used;
            if (pendingValue > worstPending
                    || (pendingValue == worstPending && usedValue > worstUsed)) {
                worstPending = pendingValue;
                worstUsed = usedValue;
                at = pool.t()[i];
                max = limit;
            }
        }
        if (!exhausted) {
            return null;
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("pool", pool.name());
        numbers.put("max", Double.isNaN(max) ? null : max);
        numbers.put("usedMax", worstUsed);
        numbers.put("pendingMax", worstPending);
        numbers.put("at", at);

        String limit = Double.isNaN(max) ? "its maximum" : Numbers.number(max);
        Finding finding = new Finding(
                id(POOL_EXHAUSTED, service, pool.name()),
                POOL_EXHAUSTED, HIGH, service,
                pool.name() + " ran out of connections",
                Numbers.number(worstUsed) + " of " + limit + " connections in use and "
                        + Numbers.number(worstPending) + " requests waiting at the worst point",
                new Subject(null, null, null, pool.name()),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, worstPending * 1_000_000 + worstUsed);
    }

    private List<String> servicesInScope(String service) {
        if (service != null) {
            return List.of(service);
        }
        List<String> names = new ArrayList<>();
        for (ServiceInfo info : services.all()) {
            names.add(info.name());
        }
        return names;
    }

    // --- shared --------------------------------------------------------------

    /**
     * The id agent.md promises: stable across windows, because it hashes what the
     * finding is about and nothing about when it was found.
     */
    private static String id(String kind, String service, String subject) {
        return kind + ":" + Ids.shortHash(kind + "\0" + service + "\0" + subject);
    }

    /** The newest attributes of one span per group, for the {@code code.*} frames. */
    private Map<String, Map<String, Object>> sampleAttributes(Window window, String column,
            Set<String> ids) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        params.addAll(ids);
        Map<String, Map<String, Object>> samples = new HashMap<>();
        sql.query("SELECT * FROM (SELECT " + column + " AS group_id, attributes,"
                + " ROW_NUMBER() OVER (PARTITION BY " + column + " ORDER BY start_ms DESC, id DESC) AS rn"
                + " FROM span WHERE start_ms BETWEEN ? AND ? AND " + column + " IN ("
                + Sql.placeholders(ids.size()) + ")) WHERE rn = 1", params, rs -> {
                    samples.put(rs.getString("group_id"), AttrJson.decode(rs.getString("attributes")));
                    return null;
                });
        return samples;
    }

    private static List<String> traceIds(List<Stats.TraceSummary> traces) {
        List<String> ids = new ArrayList<>(traces.size());
        for (Stats.TraceSummary trace : traces) {
            ids.add(trace.traceId());
        }
        return ids;
    }

    private static List<Map<String, Object>> endpointCounts(List<Stats.EndpointCount> endpoints) {
        List<Map<String, Object>> list = new ArrayList<>(endpoints.size());
        for (Stats.EndpointCount endpoint : endpoints) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", endpoint.name());
            entry.put("count", endpoint.count());
            list.add(entry);
        }
        return list;
    }

    private static List<Map<String, Object>> callers(List<Stats.Caller> callers) {
        List<Map<String, Object>> list = new ArrayList<>(callers.size());
        for (Stats.Caller caller : callers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("endpoint", caller.endpoint());
            entry.put("service", caller.service());
            entry.put("calls", caller.calls());
            list.add(entry);
        }
        return list;
    }

    /** {@code SELECT order_line}, or the statement itself when the agent named no table. */
    public static String queryName(String operation, String table, String statement) {
        if (operation != null && table != null) {
            return operation + " " + table;
        }
        if (statement == null) {
            return "a query";
        }
        String single = statement.replaceAll("\\s+", " ").trim();
        return single.length() <= 60 ? single : single.substring(0, 60) + "…";
    }

    /** {@code java.lang.IllegalStateException} is said as {@code IllegalStateException}. */
    public static String simpleName(String type) {
        if (type == null) {
            return "error";
        }
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    /** {@code 42, 42 and 41}: the repeats a person would read out, largest first. */
    private static String counts(int[] repeats) {
        List<String> largest = new ArrayList<>();
        for (int i = repeats.length - 1; i >= 0 && largest.size() < 3; i--) {
            largest.add(String.valueOf(repeats[i]));
        }
        if (largest.size() == 1) {
            return largest.get(0);
        }
        String last = largest.remove(largest.size() - 1);
        return String.join(", ", largest) + " and " + last;
    }
}
