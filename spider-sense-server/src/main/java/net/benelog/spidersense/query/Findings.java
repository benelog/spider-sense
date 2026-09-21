package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.Ids;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.MetricSeriesNames;
import net.benelog.spidersense.store.ServiceInfo;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.Tingles;
import org.jspecify.annotations.Nullable;

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
    public static final String LOG_ERROR = "log-error";
    public static final String N_PLUS_ONE = "n-plus-one";
    public static final String SLOW_QUERY = "slow-query";
    public static final String SLOW_ENDPOINT = "slow-endpoint";
    public static final String SLOW_JOB = "slow-job";
    public static final String SLOW_EXTERNAL = "slow-external";
    public static final String POOL_EXHAUSTED = "pool-exhausted";
    public static final String GC_PAUSE = "gc-pause";
    public static final String HEAP_PRESSURE = "heap-pressure";
    public static final String THREAD_GROWTH = "thread-growth";

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

    /** The OTLP severity number of {@code ERROR}; a {@code log-error} counts it and worse. */
    private static final int ERROR_SEVERITY = 17;

    /** A guard on the one rule that reads log rows rather than an aggregate. */
    private static final int MAX_LOG_ROWS = 20_000;

    /** The title of a {@code log-error} carries this much of the message (agent.md). */
    private static final int MESSAGE_IN_TITLE = 80;

    /** The traces of a {@code slow-external} group the three slowest are picked from. */
    private static final int EVIDENCE_CANDIDATES = 200;

    /** A collector taking this share of an export interval is a {@code gc-pause}. */
    private static final double GC_SHARE = 0.10;

    /** The heap at this share of its limit is {@code heap-pressure}. */
    private static final double HEAP_RATIO = 0.90;

    /** Threads: this many more than at the start of the window, or twice as many. */
    private static final int THREADS_GROWN_BY = 50;
    private static final int THREADS_DOUBLED_FROM = 20;

    /** Which group a finding is about; the fields that do not apply are null. */
    public record Subject(@Nullable String endpointId, @Nullable String queryId,
            @Nullable String errorId, @Nullable String pool, @Nullable String job,
            @Nullable String target, @Nullable String logger, @Nullable String jvm) {
    }

    /** When a finding was acknowledged, and why (agent.md, "Acknowledgements"). */
    public record Ack(long at, @Nullable String note) {
    }

    /**
     * One thing worth fixing.
     *
     * @param numbers the kind-specific numbers agent.md lists, in the order it
     *        lists them; values are numbers, strings, or lists of small maps
     * @param ack     null unless a reader has accepted this finding, in which case
     *        it is ranked after every other one
     * @param schema  the indexes of the statement's tables and the predicates none
     *        serves; only {@code slow-query} and {@code n-plus-one} have one, and
     *        only when the catalog knows every table (agent.md)
     */
    public record Finding(String id, String kind, String severity, String service, String title,
            String why, Subject subject, Map<String, Object> numbers, @Nullable String statement,
            List<String> code, List<String> traces, @Nullable Ack ack, @Nullable SchemaBlock schema) {

        /** A finding as a rule makes one: nothing has acknowledged it yet. */
        public Finding(String id, String kind, String severity, String service, String title,
                String why, Subject subject, Map<String, Object> numbers, @Nullable String statement,
                List<String> code, List<String> traces) {
            this(id, kind, severity, service, title, why, subject, numbers, statement, code,
                    traces, null, null);
        }

        /** The same finding, with the acknowledgement the store had for its id. */
        public Finding withAck(Ack acknowledged) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, acknowledged, schema);
        }

        /** The same finding, with the block its statement and the catalog produced. */
        public Finding withSchema(@Nullable SchemaBlock block) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, ack, block);
        }
    }

    /**
     * The findings of one window and how many of them were acknowledged.
     *
     * <p>The count is taken before the limit, because it answers "how much is
     * being kept out of the way" rather than "how much of this page is dimmed"
     * (api.md).
     */
    public record Answer(List<Finding> findings, int acked) {
    }

    private final Sql sql;
    private final Acks acks;
    private final Catalog catalog;
    private final Queries queries;
    private final MetricQueries metrics;
    private final ServiceRegistry services;
    private final Tingles tingles;
    private final CodeFrames frames;

    public Findings(Sql sql, Queries queries, MetricQueries metrics, ServiceRegistry services,
            Tingles tingles, CodeFrames frames) {
        this.sql = sql;
        this.acks = new Acks(sql);
        this.catalog = queries.catalog();
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
    public List<Finding> findings(Window window, @Nullable String service, int limit) {
        return findings(window, service, limit, false);
    }

    /**
     * The same ranking, narrowed to what a reader has not accepted yet.
     *
     * @param hideAcked whether acknowledged findings are left out rather than ranked last
     */
    public List<Finding> findings(Window window, @Nullable String service, int limit,
            boolean hideAcked) {
        return answer(window, service, limit, hideAcked).findings();
    }

    /**
     * The same ranking, with the acknowledgements attached and counted.
     *
     * <p>An acknowledged finding keeps its place among the acknowledged ones: the
     * partition is stable, so the list a reader saw yesterday has not been
     * reshuffled, only pushed down (agent.md, "Acknowledgements").
     */
    public Answer answer(Window window, @Nullable String service, int limit, boolean hideAcked) {
        Ancestors ancestors = new Ancestors(sql, window);
        List<Ranked> found = new ArrayList<>();
        found.addAll(errors(window, service));
        found.addAll(logErrors(window, service, ancestors));
        found.addAll(nPlusOne(window, service, ancestors));
        found.addAll(slowQueries(window, service));
        found.addAll(slowEndpoints(window, service));
        found.addAll(slowJobs(window, service));
        found.addAll(slowExternal(window, service, ancestors));
        found.addAll(poolExhausted(window, service));
        found.addAll(jvm(window, service));
        found.sort(Ranked.ORDER);

        Set<String> ids = new LinkedHashSet<>();
        for (Ranked each : found) {
            ids.add(each.finding().id());
        }
        Map<String, Acks.Ack> acknowledged = acks.byId(ids);

        List<Finding> open = new ArrayList<>();
        List<Finding> accepted = new ArrayList<>();
        for (Ranked each : found) {
            Acks.Ack ack = acknowledged.get(each.finding().id());
            if (ack == null) {
                open.add(each.finding());
            } else if (!hideAcked) {
                accepted.add(each.finding().withAck(new Ack(ack.at(), ack.note())));
            }
        }

        List<Finding> ranked = new ArrayList<>(open);
        ranked.addAll(accepted);
        if (ranked.size() > limit) {
            ranked = new ArrayList<>(ranked.subList(0, Math.max(0, limit)));
        }
        return new Answer(ranked, acknowledged.size());
    }

    /** A finding with the impact it is ranked by inside its kind. */
    private record Ranked(Finding finding, double impact) {

        private static final List<String> KINDS = List.of(ERROR, LOG_ERROR, N_PLUS_ONE, SLOW_QUERY,
                SLOW_ENDPOINT, SLOW_JOB, SLOW_EXTERNAL, POOL_EXHAUSTED, GC_PAUSE, HEAP_PRESSURE,
                THREAD_GROWTH);

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

    private List<Ranked> errors(Window window, @Nullable String service) {
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
                    new Subject(null, null, group.errorId(), null, null, null, null, null),
                    numbers, null,
                    frames.ofStacktrace(stacktrace),
                    traceIds(queries.tracesContaining(window, "error_id = ?", group.errorId(),
                            EVIDENCE_TRACES, false)));
            found.add(new Ranked(finding, group.count()));
        }
        return found;
    }

    // --- log error -----------------------------------------------------------

    /** One {@code ERROR} log record nothing else reports, and where it came from. */
    private record LogLine(long at, @Nullable String traceId, String endpoint,
            Map<String, Object> attributes) {
    }

    /**
     * {@code ERROR} log records whose trace has no error span, grouped by
     * {@code (service, logger, normalised message)}.
     *
     * <p>This is what {@code catch (Exception e) { log.error(…, e); return fallback; }}
     * leaves behind: no span error, no exception event, one line in the log. A
     * record whose trace does carry an error span is already an {@code error}
     * finding, so the join drops it rather than reporting it twice (agent.md).
     *
     * <p>The grouping is the one {@link Ids#normaliseMessage} defines, which is a
     * Java regular expression rather than SQL, so the rows are read and grouped
     * here; the cap is the same order as the dependency scan's.
     */
    private List<Ranked> logErrors(Window window, @Nullable String service, Ancestors ancestors) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = "l.at_ms BETWEEN ? AND ? AND l.severity_number >= " + ERROR_SEVERITY
                + " AND (t.trace_id IS NULL OR t.error_count = 0)";
        if (service != null) {
            where = where + " AND l.service = ?";
            params.add(service);
        }
        Map<String, List<LogLine>> byGroup = new LinkedHashMap<>();
        Map<String, String[]> named = new LinkedHashMap<>();
        sql.forEach("SELECT l.service AS service, l.logger AS logger, l.body AS body, l.at_ms AS at_ms,"
                + " l.trace_id AS trace_id, l.span_id AS span_id, l.attributes AS attributes,"
                + " t.root_name AS root_name FROM log l"
                + " LEFT JOIN trace t ON t.trace_id = l.trace_id WHERE " + where
                + " ORDER BY l.at_ms, l.id LIMIT " + MAX_LOG_ROWS, params, rs -> {
                    String logger = logger(rs.getString("logger"));
                    String message = Ids.normaliseMessage(rs.getString("body"));
                    String key = rs.getString("service") + "\0" + logger + "\0" + message;
                    named.putIfAbsent(key, new String[]{rs.getString("service"), logger, message});
                    byGroup.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new LogLine(rs.getLong("at_ms"), rs.getString("trace_id"),
                                    endpointOf(ancestors, rs.getString("span_id"),
                                            rs.getString("root_name")),
                                    AttrJson.decode(rs.getString("attributes"))));
                });

        List<Ranked> found = new ArrayList<>();
        byGroup.forEach((key, records) -> {
            String[] parts = Objects.requireNonNull(named.get(key), "every group was named as it was read");
            String serviceName = parts[0];
            String logger = parts[1];
            String message = parts[2];
            Map<String, long[]> byEndpoint = new LinkedHashMap<>();
            for (LogLine record : records) {
                byEndpoint.computeIfAbsent(record.endpoint(), name -> new long[1])[0]++;
            }
            List<Stats.EndpointCount> endpoints = new ArrayList<>();
            byEndpoint.forEach((name, count) -> endpoints.add(new Stats.EndpointCount(name, count[0])));
            endpoints.sort((a, b) -> Long.compare(b.count(), a.count()));

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("count", (long) records.size());
            numbers.put("firstSeen", records.get(0).at());
            numbers.put("lastSeen", records.get(records.size() - 1).at());
            numbers.put("logger", logger);
            numbers.put("message", message);
            numbers.put("endpoints", endpointCounts(endpoints));

            List<String> traces = new ArrayList<>();
            for (int i = records.size() - 1; i >= 0 && traces.size() < EVIDENCE_TRACES; i--) {
                String traceId = records.get(i).traceId();
                if (traceId != null && !traces.contains(traceId)) {
                    traces.add(traceId);
                }
            }
            String seenIn = endpoints.isEmpty() ? serviceName : endpoints.get(0).name();
            Finding finding = new Finding(
                    id(LOG_ERROR, serviceName, logger + "\0" + message),
                    LOG_ERROR, HIGH, serviceName,
                    "ERROR in " + simpleName(logger) + ": " + cut(message, MESSAGE_IN_TITLE),
                    Numbers.plural(records.size(), "record") + " in " + seenIn
                            + ", none of them on a failed trace; " + message,
                    new Subject(null, null, null, null, null, null, logger, null),
                    numbers, null,
                    frames.ofStacktrace(stacktraceOf(records.get(records.size() - 1).attributes())),
                    List.copyOf(traces));
            found.add(new Ranked(finding, records.size()));
        });
        return found;
    }

    /** The endpoint a log record belongs to, as an {@code error} finding's endpoints are found. */
    private static String endpointOf(Ancestors ancestors, @Nullable String spanId,
            @Nullable String rootName) {
        if (spanId != null && !spanId.isBlank()) {
            Queries.Ancestry.Entry entry = ancestors.get().entryOf(spanId);
            if (entry != null) {
                return entry.endpoint();
            }
        }
        return rootName == null ? "(no endpoint)" : rootName;
    }

    /** The logging bridge exports the throwable as an attribute when there was one. */
    private static @Nullable String stacktraceOf(@Nullable Map<String, Object> attributes) {
        Object stacktrace = attributes == null ? null : attributes.get("exception.stacktrace");
        return stacktrace == null ? null : String.valueOf(stacktrace);
    }

    private static String logger(@Nullable String logger) {
        return logger == null || logger.isBlank() ? "(no logger)" : logger;
    }

    // --- n + 1 ---------------------------------------------------------------

    /** One repeated statement under one entry span. */
    private record Repeat(String traceId, String endpointId, String endpoint, String service,
            String queryId, @Nullable String statement, String queryName, int repeats, double totalMs,
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
    private List<Ranked> nPlusOne(Window window, @Nullable String service, Ancestors ancestors) {
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
        Queries.Ancestry ancestry = ancestors.get();
        Map<String, List<Repeat>> byEntry = new LinkedHashMap<>();
        sql.forEach("SELECT span_id, trace_id, query_id, service, start_ms, duration_ns, db_statement,"
                + " db_operation, db_table, attributes FROM span WHERE " + where, params, rs -> {
                    String traceId = rs.getString("trace_id");
                    String queryId = rs.getString("query_id");
                    if (!pairs.contains(traceId + "\0" + queryId)) {
                        return;
                    }
                    Queries.Ancestry.Entry entry = ancestry.entryOf(rs.getString("span_id"));
                    if (entry == null) {
                        return;
                    }
                    String endpointId = Ids.endpointId(entry.service(), entry.endpoint());
                    byEntry.computeIfAbsent(entry.spanId() + "\0" + queryId, key -> new ArrayList<>())
                            .add(new Repeat(traceId, endpointId, entry.endpoint(), entry.service(),
                                    queryId, rs.getString("db_statement"),
                                    queryName(rs.getString("db_operation"), rs.getString("db_table"),
                                            rs.getString("db_statement")),
                                    1, rs.getLong("duration_ns") / 1_000_000.0, rs.getLong("start_ms"),
                                    AttrJson.decode(rs.getString("attributes"))));
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
        // The catalog is one read per service, not one per repeated statement.
        Map<String, Map<String, List<Catalog.Table>>> catalogs = new HashMap<>();
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
                    new Subject(first.endpointId(), first.queryId(), null, null, null, null, null, null),
                    numbers, first.statement(),
                    code,
                    List.copyOf(traces))
                    .withSchema(SchemaBlock.of(first.statement(),
                            catalogs.computeIfAbsent(first.service(), catalog::forService)));
            found.add(new Ranked(finding, affected.size() * (double) median));
        });
        return found;
    }

    private List<String[]> candidates(Window window, @Nullable String service) {
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

    private long requests(Window window, @Nullable String service, String endpointId) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to(), endpointId));
        String where = "start_ms BETWEEN ? AND ? AND entry AND endpoint_id = ?";
        if (service != null) {
            where = where + " AND service = ?";
            params.add(service);
        }
        return sql.count("SELECT COUNT(*) FROM span WHERE " + where, params);
    }

    // --- slow query ----------------------------------------------------------

    private List<Ranked> slowQueries(Window window, @Nullable String service) {
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
                    new Subject(null, query.queryId(), null, null, null, null, null, null),
                    numbers, query.statement(),
                    frames.ofAttributes(samples.get(query.queryId())),
                    traceIds(queries.tracesContaining(window, "query_id = ?", query.queryId(),
                            EVIDENCE_TRACES, true)))
                    // The group already carries the block /api/queries shows; a finding
                    // and a query row never disagree about the same statement.
                    .withSchema(query.schema());
            found.add(new Ranked(finding, query.totalMs()));
        }
        return found;
    }

    // --- slow endpoint -------------------------------------------------------

    private List<Ranked> slowEndpoints(Window window, @Nullable String service) {
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

            List<String> traces = traceIds(queries.tracesContaining(window, "endpoint_id = ?",
                    endpoint.endpointId(), EVIDENCE_TRACES, true));

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
            numbers.put("hotSpan", hotSpan(traces));

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
                    new Subject(endpoint.endpointId(), null, null, null, null, null, null, null),
                    numbers, null, List.of(), traces);
            found.add(new Ranked(finding, endpoint.totalMs()));
        }
        return found;
    }

    // --- slow job ------------------------------------------------------------

    /** One job group over the window: the runs of one span name of one service. */
    private record Job(String service, String name, long runs, double p50Ms, double p95Ms,
            double maxMs, double totalMs) {
    }

    /**
     * A job is a root {@code INTERNAL} span (design.md): a scheduled method, an
     * {@code @Async} call, a batch step.
     *
     * <p>A job is never an entry span, so it is in no request count, in no Apdex and
     * in no {@code check} verdict; this rule is the one place a slow scheduler tick
     * or batch step is reported, and it measures over the runs of a job exactly what
     * {@code slow-endpoint} measures over the requests of an endpoint.
     */
    private List<Ranked> slowJobs(Window window, @Nullable String service) {
        List<Job> slow = new ArrayList<>();
        for (Job job : jobs(window, service)) {
            if (job.p95Ms() > tingles.slowRequestMs()) {
                slow.add(job);
            }
        }
        if (slow.isEmpty()) {
            return List.of();
        }
        Map<String, Queries.DbWork> databaseWork = queries.jobDatabaseWork(window, service);
        Map<String, Map<String, Object>> samples = jobSampleAttributes(window, service);

        List<Ranked> found = new ArrayList<>();
        for (Job job : slow) {
            String key = job.service() + "\0" + job.name();
            Queries.DbWork work = databaseWork.getOrDefault(key, Queries.DbWork.NONE);
            double perRun = job.runs() == 0 ? 0 : (double) work.calls() / job.runs();
            double msPerRun = job.runs() == 0 ? 0 : work.totalMs() / job.runs();
            double share = job.totalMs() <= 0 ? 0 : Math.min(1, work.totalMs() / job.totalMs());

            List<String> traces = traceIds(queries.tracesContaining(window,
                    "parent_span_id IS NULL AND s.kind = 'INTERNAL' AND s.service = ? AND s.name = ?",
                    List.of(job.service(), job.name()), EVIDENCE_TRACES, true));

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("runs", job.runs());
            numbers.put("p50Ms", job.p50Ms());
            numbers.put("p95Ms", job.p95Ms());
            numbers.put("maxMs", job.maxMs());
            numbers.put("totalMs", job.totalMs());
            numbers.put("dbCallsPerRun", perRun);
            numbers.put("dbMsPerRun", msPerRun);
            numbers.put("dbShare", share);
            numbers.put("hotSpan", hotSpan(traces));

            String severity = job.p95Ms() > 4 * tingles.slowRequestMs() ? HIGH : MEDIUM;
            Finding finding = new Finding(
                    id(SLOW_JOB, job.service(), job.name()),
                    SLOW_JOB, severity, job.service(),
                    job.name() + " is slow",
                    "p95 " + Numbers.millis(job.p95Ms()) + " over "
                            + Numbers.plural(job.runs(), "run") + "; "
                            + Numbers.number(perRun) + " database calls and "
                            + Numbers.millis(msPerRun) + " per run, "
                            + Numbers.percent(share) + " of the time",
                    new Subject(null, null, null, null, job.name(), null, null, null),
                    numbers, null,
                    frames.ofAttributes(samples.get(key)), traces);
            found.add(new Ranked(finding, job.totalMs()));
        }
        return found;
    }

    /** The job groups of the window, the heaviest first (storage.md). */
    private List<Job> jobs(Window window, @Nullable String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = jobWhere(service, params);
        return sql.query("SELECT service, name, COUNT(*) AS runs,"
                        + " PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_ns) AS p50_ns,"
                        + " PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns) AS p95_ns,"
                        + " MAX(duration_ns) AS max_ns, SUM(duration_ns) AS total_ns FROM span WHERE "
                        + where + " GROUP BY service, name ORDER BY total_ns DESC LIMIT " + GROUPS,
                params, rs -> new Job(rs.getString("service"), rs.getString("name"),
                        rs.getLong("runs"), Rows.ms(rs, "p50_ns"), Rows.ms(rs, "p95_ns"),
                        Rows.ms(rs, "max_ns"), Rows.ms(rs, "total_ns")));
    }

    /**
     * The newest run of each job group, for the {@code code.*} frames.
     *
     * <p>One statement for every group, as {@link #sampleAttributes} does for a
     * single column; a job is keyed by two, so it partitions by both.
     */
    private Map<String, Map<String, Object>> jobSampleAttributes(Window window,
            @Nullable String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = jobWhere(service, params);
        Map<String, Map<String, Object>> samples = new HashMap<>();
        sql.forEach("SELECT * FROM (SELECT service, name, attributes,"
                + " ROW_NUMBER() OVER (PARTITION BY service, name ORDER BY start_ms DESC, id DESC) AS rn"
                + " FROM span WHERE " + where + ") WHERE rn = 1", params, rs ->
                        samples.put(rs.getString("service") + "\0" + rs.getString("name"),
                                AttrJson.decode(rs.getString("attributes"))));
        return samples;
    }

    /** A run of a job: a root {@code INTERNAL} span of the window (design.md). */
    private static String jobWhere(@Nullable String service, List<Object> params) {
        String where = "start_ms BETWEEN ? AND ? AND parent_span_id IS NULL AND kind = 'INTERNAL'";
        if (service != null) {
            params.add(service);
            return where + " AND service = ?";
        }
        return where;
    }

    // --- slow external -------------------------------------------------------

    /**
     * An outbound HTTP call that is slow: {@code CLIENT} spans of category
     * {@code http}, grouped by {@code (service, target, span name)}.
     *
     * <p>The target is the dependency target of the service map
     * ({@link Queries#target}), which lives in the span's attributes rather than in
     * a column, so the group is formed here over the window's outbound spans rather
     * than by a {@code GROUP BY}; the percentiles are the nearest-rank ones
     * {@code PERCENTILE_DISC} gives the other rules.
     */
    private List<Ranked> slowExternal(Window window, @Nullable String service, Ancestors ancestors) {
        Map<String, List<SpanRecord>> byGroup = new LinkedHashMap<>();
        for (SpanRecord span : queries.outboundHttp(window, service)) {
            byGroup.computeIfAbsent(
                    span.service() + "\0" + Queries.target(span) + "\0" + span.name(),
                    key -> new ArrayList<>()).add(span);
        }
        List<Ranked> found = new ArrayList<>();
        byGroup.forEach((key, calls) -> {
            String[] parts = key.split("\0", 3);
            Ranked ranked = external(window, ancestors, parts[0], parts[1], parts[2], calls);
            if (ranked != null) {
                found.add(ranked);
            }
        });
        return found;
    }

    private @Nullable Ranked external(Window window, Ancestors ancestors, String service, String target,
            String name, List<SpanRecord> calls) {
        double[] durations = new double[calls.size()];
        double totalMs = 0;
        long errors = 0;
        SpanRecord newest = null;
        for (int i = 0; i < calls.size(); i++) {
            SpanRecord call = calls.get(i);
            durations[i] = call.durationMillis();
            totalMs += durations[i];
            if (call.isError()) {
                errors++;
            }
            if (newest == null || call.startNanos() > newest.startNanos()) {
                newest = call;
            }
        }
        Arrays.sort(durations);
        double p95Ms = Queries.percentile(durations, 0.95);
        if (p95Ms <= tingles.slowRequestMs()) {
            return null;
        }

        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("calls", (long) calls.size());
        numbers.put("errors", errors);
        numbers.put("p50Ms", Queries.percentile(durations, 0.5));
        numbers.put("p95Ms", p95Ms);
        numbers.put("maxMs", durations[durations.length - 1]);
        numbers.put("totalMs", totalMs);
        numbers.put("callers", callers(externalCallers(ancestors, calls, service)));

        String severity = p95Ms > 4 * tingles.slowRequestMs() ? HIGH : MEDIUM;
        Finding finding = new Finding(
                id(SLOW_EXTERNAL, service, target + "\0" + name),
                SLOW_EXTERNAL, severity, service,
                name + " " + target + " is slow",
                "p95 " + Numbers.millis(p95Ms) + " over "
                        + Numbers.plural(calls.size(), "call") + ", "
                        + Numbers.plural(errors, "error") + "; "
                        + Numbers.millis(totalMs) + " in total",
                new Subject(null, null, null, null, null, target, null, null),
                numbers, null,
                frames.ofAttributes(newest == null ? null : newest.attributes()),
                externalTraces(window, calls));
        return new Ranked(finding, totalMs);
    }

    /** Which endpoints made the calls: the nearest entry span up the chain, as a query's callers. */
    private static List<Stats.Caller> externalCallers(Ancestors ancestors, List<SpanRecord> calls,
            String service) {
        Queries.Ancestry ancestry = ancestors.get();
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> byService = new LinkedHashMap<>();
        for (SpanRecord call : calls) {
            Queries.Ancestry.Entry entry = ancestry.entryOf(call.spanId());
            String endpoint = entry == null ? "(no endpoint)" : entry.endpoint();
            counts.computeIfAbsent(endpoint, name -> new long[1])[0]++;
            byService.putIfAbsent(endpoint, entry == null ? service : entry.service());
        }
        List<Stats.Caller> list = new ArrayList<>();
        counts.forEach((endpoint, count) ->
                list.add(new Stats.Caller(endpoint,
                        Objects.requireNonNull(byService.get(endpoint), "every endpoint was named"),
                        count[0])));
        list.sort((a, b) -> Long.compare(b.calls(), a.calls()));
        return list;
    }

    /**
     * The three slowest traces that contain one of the group's calls.
     *
     * <p>The group's target is not a column, so the traces of its slowest calls are
     * the candidates the store then orders by trace duration; the cap keeps the
     * {@code IN} list bounded on a group with thousands of calls.
     */
    private List<String> externalTraces(Window window, List<SpanRecord> calls) {
        List<SpanRecord> slowest = new ArrayList<>(calls);
        slowest.sort(Comparator.comparingDouble((SpanRecord call) -> call.durationMillis()).reversed());
        Set<String> candidates = new LinkedHashSet<>();
        for (SpanRecord call : slowest) {
            if (candidates.size() >= EVIDENCE_CANDIDATES) {
                break;
            }
            candidates.add(call.traceId());
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return traceIds(queries.tracesContaining(window,
                "trace_id IN (" + Sql.placeholders(candidates.size()) + ")",
                new ArrayList<>(candidates), EVIDENCE_TRACES, true));
    }

    // --- pool exhausted ------------------------------------------------------

    private List<Ranked> poolExhausted(Window window, @Nullable String service) {
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
    private @Nullable Ranked exhausted(String service, JvmView.ConnectionPool pool) {
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
                new Subject(null, null, null, pool.name(), null, null, null, null),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, worstPending * 1_000_000 + worstUsed);
    }

    private List<String> servicesInScope(@Nullable String service) {
        if (service != null) {
            return List.of(service);
        }
        List<String> names = new ArrayList<>();
        for (ServiceInfo info : services.all()) {
            names.add(info.name());
        }
        return names;
    }

    // --- the JVM -------------------------------------------------------------

    /**
     * The three rules that read the JVM page's own series (agent.md).
     *
     * <p>A run that is slow because it is collecting or swapping is named for what
     * it is, instead of producing {@code slow-endpoint} findings that point at the
     * wrong thing.
     */
    private List<Ranked> jvm(Window window, @Nullable String service) {
        List<Ranked> found = new ArrayList<>();
        for (String name : servicesInScope(service)) {
            for (MetricQueries.SeriesData series :
                    metrics.series(MetricSeriesNames.GC_DURATION, name, Map.of(), window)) {
                Ranked paused = gcPause(name, series);
                if (paused != null) {
                    found.add(paused);
                }
            }
            add(found, heapPressure(name, window));
            add(found, threadGrowth(name, window));
        }
        return found;
    }

    private static void add(List<Ranked> found, @Nullable Ranked ranked) {
        if (ranked != null) {
            found.add(ranked);
        }
    }

    /**
     * One collector: its longest single collection, and the worst share of an
     * export interval its collections took.
     *
     * <p>The raw histogram points are read rather than {@link JvmView#gc}'s buckets,
     * because the rule is about one point's {@code max} and about the sum over the
     * interval between two points, both of which bucketing has already averaged
     * away. {@code jvm.gc.duration} is seconds by the semantic conventions, and the
     * stored unit is trusted over that when it says otherwise.
     */
    private @Nullable Ranked gcPause(String service, MetricQueries.SeriesData series) {
        List<MetricPoint> points = series.points();
        if (points.isEmpty()) {
            return null;
        }
        double toMillis = "ms".equals(series.unit()) ? 1 : 1000;
        boolean cumulative = !"DELTA".equals(series.temporality());
        double worstMs = 0;
        long worstAt = points.get(0).at();
        double shareMax = 0;
        long shareAt = points.get(0).at();
        long collections = 0;
        for (int i = 0; i < points.size(); i++) {
            MetricPoint point = points.get(i);
            double longest = point.max() * toMillis;
            if (longest > worstMs) {
                worstMs = longest;
                worstAt = point.at();
            }
            if (cumulative && i == 0) {
                continue;
            }
            long count = point.count();
            double sum = point.sum();
            if (cumulative) {
                count -= points.get(i - 1).count();
                sum -= points.get(i - 1).sum();
            }
            if (count > 0) {
                collections += count;
            }
            long interval = i == 0 ? 0 : point.at() - points.get(i - 1).at();
            if (interval > 0) {
                double share = Math.max(0, sum) * toMillis / interval;
                if (share > shareMax) {
                    shareMax = share;
                    shareAt = point.at();
                }
            }
        }
        long threshold = tingles.slowRequestMs();
        boolean paused = worstMs >= threshold;
        if (!paused && shareMax < GC_SHARE) {
            return null;
        }
        String name = series.attribute(MetricSeriesNames.GC_NAME);
        String action = series.attribute(MetricSeriesNames.GC_ACTION);
        String collector = name == null ? "the collector" : name;

        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("gc", name);
        numbers.put("action", action);
        numbers.put("worstMs", worstMs);
        numbers.put("shareMax", shareMax);
        numbers.put("collections", collections);
        numbers.put("at", paused ? worstAt : shareAt);

        Finding finding = new Finding(
                id(GC_PAUSE, service, "gc:" + name + "\0" + action),
                GC_PAUSE, paused ? HIGH : MEDIUM, service,
                collector + " paused for " + Numbers.millis(worstMs),
                "the longest single collection took " + Numbers.millis(worstMs) + " over "
                        + Numbers.plural(collections, "collection") + "; "
                        + Numbers.percent(shareMax) + " of an export interval at worst",
                new Subject(null, null, null, null, null, null, null, "gc:" + name),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, worstMs);
    }

    /** The heap against its limit, summed over the pools exactly as the JVM page sums them. */
    private @Nullable Ranked heapPressure(String service, Window window) {
        JvmView.Memory heap = JvmView.heap(metrics, service, window);
        double ratioMax = 0;
        double usedMax = 0;
        double limit = 0;
        long at = 0;
        for (int i = 0; i < heap.t().length; i++) {
            double used = at(heap.used(), i);
            double max = at(heap.limit(), i);
            if (Double.isNaN(used) || Double.isNaN(max) || max <= 0) {
                continue;
            }
            double ratio = used / max;
            if (ratio > ratioMax) {
                ratioMax = ratio;
                usedMax = used;
                limit = max;
                at = heap.t()[i];
            }
        }
        if (ratioMax < HEAP_RATIO) {
            return null;
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("usedMax", usedMax);
        numbers.put("limit", limit);
        numbers.put("ratioMax", ratioMax);
        numbers.put("at", at);

        Finding finding = new Finding(
                id(HEAP_PRESSURE, service, "heap"),
                HEAP_PRESSURE, HIGH, service,
                "heap at " + Numbers.percent(ratioMax) + " of its limit",
                Numbers.number(usedMax / 1_048_576.0) + " MiB of "
                        + Numbers.number(limit / 1_048_576.0) + " MiB in use at the worst point",
                new Subject(null, null, null, null, null, null, null, "heap"),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, ratioMax);
    }

    /** Threads at the end of the window against threads at its start. */
    private @Nullable Ranked threadGrowth(String service, Window window) {
        JvmView.Threads threads = JvmView.threads(metrics, service, window);
        double first = Double.NaN;
        double last = Double.NaN;
        double max = 0;
        long at = 0;
        for (int i = 0; i < threads.t().length; i++) {
            double count = at(threads.count(), i);
            if (Double.isNaN(count)) {
                continue;
            }
            if (Double.isNaN(first)) {
                first = count;
            }
            last = count;
            at = threads.t()[i];
            max = Math.max(max, count);
        }
        if (Double.isNaN(first)) {
            return null;
        }
        boolean grew = last - first >= THREADS_GROWN_BY
                || (first >= THREADS_DOUBLED_FROM && last >= 2 * first);
        if (!grew) {
            return null;
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("first", (long) first);
        numbers.put("last", (long) last);
        numbers.put("max", (long) max);
        numbers.put("at", at);

        Finding finding = new Finding(
                id(THREAD_GROWTH, service, "threads"),
                THREAD_GROWTH, MEDIUM, service,
                "threads grew from " + Numbers.count((long) first) + " to "
                        + Numbers.count((long) last),
                Numbers.plural((long) (last - first), "thread") + " more than at the start of the"
                        + " window, peaking at " + Numbers.count((long) max),
                new Subject(null, null, null, null, null, null, null, "threads"),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, last - first);
    }

    /** A value of an aligned series, or {@link Double#NaN} when the series is shorter. */
    private static double at(double[] values, int index) {
        return values.length > index ? values[index] : Double.NaN;
    }

    // --- shared --------------------------------------------------------------

    /**
     * The parent-chain walk of one window, loaded at most once for one call of
     * {@link #findings}.
     *
     * <p>Three rules need it — the N+1, the outbound calls and the log errors — and
     * {@link Queries.Ancestry#of} is a scan of the window's spans, so it is built
     * lazily and shared rather than built once per rule.
     */
    private static final class Ancestors {

        private final Sql sql;
        private final Window window;
        private Queries.@Nullable Ancestry ancestry;

        private Ancestors(Sql sql, Window window) {
            this.sql = sql;
            this.window = window;
        }

        Queries.Ancestry get() {
            Queries.Ancestry loaded = ancestry;
            if (loaded == null) {
                loaded = Queries.Ancestry.of(sql, window);
                ancestry = loaded;
            }
            return loaded;
        }
    }

    /**
     * Where the time went in the finding's first evidence trace.
     *
     * <p>A span's self time is its duration less the durations of its direct
     * children, never below zero — the Profile view's definition — and the hot span
     * is the largest of them, with that time's share of the trace. It is the first
     * clue when {@code dbShare} is low: the trace says "here", not "somewhere".
     *
     * @return null when the finding has no trace
     */
    private @Nullable Map<String, Object> hotSpan(List<String> traces) {
        if (traces.isEmpty()) {
            return null;
        }
        Queries.TraceDetail detail = queries.trace(traces.get(0));
        if (detail == null || detail.spans().isEmpty()) {
            return null;
        }
        Set<String> known = new HashSet<>();
        for (SpanRecord span : detail.spans()) {
            known.add(span.spanId());
        }
        Map<String, Long> childNanos = new HashMap<>();
        for (SpanRecord span : detail.spans()) {
            String parent = span.parentSpanId();
            if (parent != null && known.contains(parent)) {
                childNanos.merge(parent, span.durationNanos(), Long::sum);
            }
        }
        SpanRecord hottest = null;
        long selfNanos = -1;
        for (SpanRecord span : detail.spans()) {
            long self = Math.max(0, span.durationNanos()
                    - childNanos.getOrDefault(span.spanId(), 0L));
            if (self > selfNanos) {
                selfNanos = self;
                hottest = span;
            }
        }
        if (hottest == null) {
            return null;
        }
        double selfMs = selfNanos / 1_000_000.0;
        Map<String, Object> hot = new LinkedHashMap<>();
        hot.put("name", hottest.summary());
        hot.put("category", hottest.category());
        hot.put("selfMs", selfMs);
        hot.put("share", detail.durationMs() <= 0 ? 0.0
                : Math.min(1, selfMs / detail.durationMs()));
        return hot;
    }

    /** One line, cut at {@code max} characters with an ellipsis. */
    private static String cut(@Nullable String text, int max) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

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
        sql.forEach("SELECT * FROM (SELECT " + column + " AS group_id, attributes,"
                + " ROW_NUMBER() OVER (PARTITION BY " + column + " ORDER BY start_ms DESC, id DESC) AS rn"
                + " FROM span WHERE start_ms BETWEEN ? AND ? AND " + column + " IN ("
                + Sql.placeholders(ids.size()) + ")) WHERE rn = 1", params, rs ->
                        samples.put(rs.getString("group_id"),
                                AttrJson.decode(rs.getString("attributes"))));
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
    public static String queryName(@Nullable String operation, @Nullable String table,
            @Nullable String statement) {
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
    public static String simpleName(@Nullable String type) {
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
