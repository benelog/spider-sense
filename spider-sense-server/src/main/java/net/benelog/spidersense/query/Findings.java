package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import net.benelog.spidersense.store.Marks;
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
 * OpenTelemetry agent recorded enough, the code location (findings.adoc#code).
 *
 * <p>Nothing is computed twice: the rules run over the same {@link Queries} the UI
 * uses, so a number in a finding is the number on the screen.
 */
public final class Findings {

    public static final String ERROR = "error";
    public static final String LOG_ERROR = "log-error";
    public static final String N_PLUS_ONE = "n-plus-one";
    public static final String N_PLUS_ONE_HTTP = "n-plus-one-http";
    public static final String SLOW_QUERY = "slow-query";
    public static final String SLOW_ENDPOINT = "slow-endpoint";
    public static final String SLOW_JOB = "slow-job";
    public static final String SLOW_EXTERNAL = "slow-external";
    public static final String POOL_EXHAUSTED = "pool-exhausted";
    public static final String GC_PAUSE = "gc-pause";
    public static final String HEAP_PRESSURE = "heap-pressure";
    public static final String THREAD_GROWTH = "thread-growth";
    public static final String REGRESSION = "regression";

    /** A finding's state: absent from its service's previous run (findings.adoc#state). */
    public static final String NEW = "new";
    /** A finding's state: present in its service's previous run too. */
    public static final String ONGOING = "ongoing";
    /** A finding's state: resolved, and back. */
    public static final String REGRESSED = "regressed";

    /** The {@code numbers} a regression puts before the underlying finding's own. */
    public static final String RESOLVED_AT = "resolvedAt";
    public static final String NOTE = "note";
    public static final String ORIGINAL_KIND = "originalKind";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    /** The traces a finding names as its evidence (findings.adoc#fields). */
    static final int EVIDENCE_TRACES = 3;

    /**
     * The traces a {@code slow-endpoint} or a {@code slow-job} reads the aggregated
     * hot spans and the breakdown from; the first {@link #EVIDENCE_TRACES} of them
     * are the finding's own {@code traces} (findings.adoc#time).
     */
    private static final int SAMPLE_TRACES = 20;
    /** How many candidate traces one read of their spans names in its {@code IN} list. */
    private static final int CANDIDATE_CHUNK = 1_000;
    private static final int GROUPS = 100;

    /** How many {@code (service, previous run, kind)} id sets a server keeps (findings.adoc#state). */
    private static final int PREVIOUS_RUNS = 256;

    /** The OTLP severity number of {@code ERROR}; a {@code log-error} counts it and worse. */
    private static final int ERROR_SEVERITY = 17;

    /** The title of a {@code log-error} carries this much of the message (findings.adoc#log-error). */
    private static final int MESSAGE_IN_TITLE = 80;

    /** A {@code slow-query} whose p95 is this many times {@code slow.query.ms} is {@code high}. */
    private static final int SLOW_QUERY_HIGH_FACTOR = 10;

    /** The title of an N+1 or a {@code slow-query} names at most this much of a statement. */
    private static final int STATEMENT_IN_TITLE = 60;

    private static final double BYTES_PER_MIB = 1_048_576.0;

    /** The traces of a {@code slow-external} group the three slowest are picked from. */
    private static final int EVIDENCE_CANDIDATES = 200;

    /** A collector taking this share of an export interval is a {@code gc-pause}. */
    private static final double GC_SHARE = 0.10;

    /** The heap at this share of its limit is {@code heap-pressure}. */
    private static final double HEAP_RATIO = 0.90;

    /** Threads: this many more than at the start of the window, or twice as many. */
    private static final int THREADS_GROWN_BY = 50;
    private static final int THREADS_DOUBLED_FROM = 20;

    /**
     * Which group a finding is about; the fields that do not apply are null.
     *
     * <p>A rule builds one with the factory named after what it is about, so a call
     * site says which field it sets rather than where it sits among eight strings.
     */
    public record Subject(@Nullable String endpointId, @Nullable String queryId,
            @Nullable String errorId, @Nullable String pool, @Nullable String job,
            @Nullable String target, @Nullable String logger, @Nullable String jvm) {

        /** A {@code slow-endpoint}: one endpoint. */
        public static Subject endpoint(String endpointId) {
            return new Subject(endpointId, null, null, null, null, null, null, null);
        }

        /** A {@code slow-query}: one query group. */
        public static Subject query(String queryId) {
            return new Subject(null, queryId, null, null, null, null, null, null);
        }

        /** An {@code error}: one error group. */
        public static Subject error(String errorId) {
            return new Subject(null, null, errorId, null, null, null, null, null);
        }

        /** A {@code pool-exhausted}: one connection pool. */
        public static Subject pool(String name) {
            return new Subject(null, null, null, name, null, null, null, null);
        }

        /** A {@code slow-job}: one job, by its span name. */
        public static Subject job(String name) {
            return new Subject(null, null, null, null, name, null, null, null);
        }

        /** A {@code slow-external}: one dependency target. */
        public static Subject target(String target) {
            return new Subject(null, null, null, null, null, target, null, null);
        }

        /** A {@code log-error}: one logger. */
        public static Subject logger(String logger) {
            return new Subject(null, null, null, null, null, null, logger, null);
        }

        /** A JVM finding: {@code heap}, {@code threads} or {@code gc:<collector>}. */
        public static Subject jvm(String what) {
            return new Subject(null, null, null, null, null, null, null, what);
        }

        /** An {@code n-plus-one}: one statement repeated under one endpoint. */
        public static Subject repeatedQuery(String endpointId, String queryId) {
            return new Subject(endpointId, queryId, null, null, null, null, null, null);
        }

        /** An {@code n-plus-one-http}: one call repeated under one endpoint, by its target. */
        public static Subject repeatedCall(String endpointId, String target) {
            return new Subject(endpointId, null, null, null, null, target, null, null);
        }
    }

    /** When a finding was acknowledged, and why (findings.adoc#acknowledgements). */
    public record Ack(long at, @Nullable String note) {
    }

    /** When a finding was resolved, and how (findings.adoc#resolutions). */
    public record Resolution(long at, @Nullable String note) {
    }

    /**
     * One thing worth fixing.
     *
     * @param numbers    the kind-specific numbers findings.adoc#kinds lists, in the order it
     *        lists them; values are numbers, strings, or lists of small maps
     * @param ack        null unless a reader has accepted this finding, in which case
     *        it is ranked after every other one
     * @param schema     the indexes of the statement's tables and the predicates none
     *        serves; only {@code slow-query} and {@code n-plus-one} have one, and
     *        only when the catalog knows every table (findings.adoc#schema)
     * @param resolution null unless a reader has resolved this finding; a resolved
     *        finding that came back after it is a {@code regression}, one that did
     *        not is ranked with the acknowledged ones
     * @param state      {@code new}, {@code ongoing} or {@code regressed}
     *        (findings.adoc#state)
     */
    public record Finding(String id, String kind, String severity, String service, String title,
            String why, Subject subject, Map<String, Object> numbers, @Nullable String statement,
            List<String> code, List<String> traces, @Nullable Ack ack, @Nullable SchemaBlock schema,
            @Nullable Resolution resolution, String state) {

        /** A finding as a rule makes one: nothing has acknowledged it yet. */
        public Finding(String id, String kind, String severity, String service, String title,
                String why, Subject subject, Map<String, Object> numbers, @Nullable String statement,
                List<String> code, List<String> traces) {
            this(id, kind, severity, service, title, why, subject, numbers, statement, code,
                    traces, null, null, null, ONGOING);
        }

        /** The same finding, with the acknowledgement the store had for its id. */
        public Finding withAck(Ack acknowledged) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, acknowledged, schema, resolution, state);
        }

        /** The same finding, with the block its statement and the catalog produced. */
        public Finding withSchema(@Nullable SchemaBlock block) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, ack, block, resolution, state);
        }

        /** The same finding, with the resolution the store had for its id. */
        public Finding withResolution(Resolution resolved) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, ack, schema, resolved, state);
        }

        /** The same finding, labelled {@code new}, {@code ongoing} or {@code regressed}. */
        public Finding withState(String labelled) {
            return new Finding(id, kind, severity, service, title, why, subject, numbers,
                    statement, code, traces, ack, schema, resolution, labelled);
        }

        /**
         * The kind the rules found: the kind itself, or, for a {@code regression},
         * the kind it was before it was resolved ({@code numbers.originalKind}).
         */
        public String baseKind() {
            if (REGRESSION.equals(kind) && numbers.get(ORIGINAL_KIND) instanceof String original) {
                return original;
            }
            return kind;
        }

        /**
         * Whether the finding is ranked last, with the acknowledged ones: it is
         * acknowledged, or it is resolved and has not come back.
         */
        public boolean setAside() {
            return ack != null || (resolution != null && !REGRESSION.equals(kind));
        }
    }

    /**
     * The findings of one window, how many of them were acknowledged, and how
     * many were resolved and have not come back.
     *
     * <p>The counts are taken before the limit, because they answer "how much is
     * being kept out of the way" rather than "how much of this page is dimmed"
     * (api.adoc).
     */
    public record Answer(List<Finding> findings, int acked, int resolved) {
    }

    private final Sql sql;
    private final Acks acks;
    private final Catalog catalog;
    private final Queries queries;
    private final MetricQueries metrics;
    private final ServiceRegistry services;
    private final Tingles tingles;
    private final CodeFrames frames;

    /**
     * The ids the rules found over a closed previous run, by
     * {@code service\0from\0newest\0kind}.
     *
     * <p>A newer {@code start} mark closes a run, so what the rules find in it no
     * longer changes, and the answers after a restart ask about the same run over
     * and over; the oldest set is dropped once there are {@link #PREVIOUS_RUNS}.
     */
    private final Map<String, Set<String>> previousRuns = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    return size() > PREVIOUS_RUNS;
                }
            });

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
     * the kinds keep the order the table of findings.adoc#kinds has.
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
     * The ranking with the regressions found and nothing else attached: what
     * {@code check} counts. The states are left at {@code ongoing}, because
     * labelling them costs one more run of the rules per service and no rule of
     * {@code check} reads them.
     */
    public List<Finding> ranked(Window window, @Nullable String service, int limit) {
        return answer(window, service, limit, false, false).findings();
    }

    /**
     * The same ranking, with the acknowledgements and resolutions attached and
     * counted, and every finding labelled with its state.
     *
     * <p>An acknowledged finding keeps its place among the acknowledged ones: the
     * partition is stable, so the list a reader saw yesterday has not been
     * reshuffled, only pushed down (findings.adoc#acknowledgements).
     */
    public Answer answer(Window window, @Nullable String service, int limit, boolean hideAcked) {
        return answer(window, service, limit, hideAcked, true);
    }

    private Answer answer(Window window, @Nullable String service, int limit, boolean hideAcked,
            boolean labelled) {
        List<Ranked> found = rules(window, service);

        Set<String> ids = new LinkedHashSet<>();
        for (Ranked each : found) {
            ids.add(each.finding().id());
        }
        Map<String, Acks.Ack> decided = acks.byId(ids);

        // A resolved finding that occurred again after its resolution is a
        // regression, ranked above everything; one that did not stays itself, with
        // the resolution attached, and is set aside with the acknowledged ones.
        Map<String, Map<String, Ranked>> since = new HashMap<>();
        List<Ranked> ranked = new ArrayList<>();
        int acked = 0;
        int resolved = 0;
        for (Ranked each : found) {
            Acks.Ack row = decided.get(each.finding().id());
            if (row == null) {
                ranked.add(each);
            } else if (!row.resolved()) {
                acked++;
                ranked.add(new Ranked(each.finding().withAck(new Ack(row.at(), row.note())),
                        each.impact()));
            } else {
                Resolution resolution = new Resolution(row.at(), row.note());
                Ranked back = recurrence(each, row.at(), window, since);
                if (back == null) {
                    resolved++;
                    ranked.add(new Ranked(each.finding().withResolution(resolution), each.impact()));
                } else {
                    ranked.add(new Ranked(regression(back.finding(), resolution), back.impact()));
                }
            }
        }
        ranked.sort(Ranked.ORDER);

        List<Finding> open = new ArrayList<>();
        List<Finding> aside = new ArrayList<>();
        for (Ranked each : ranked) {
            if (!each.finding().setAside()) {
                open.add(each.finding());
            } else if (!hideAcked) {
                aside.add(each.finding());
            }
        }

        List<Finding> page = new ArrayList<>(open);
        page.addAll(aside);
        if (page.size() > limit) {
            page = new ArrayList<>(page.subList(0, Math.max(0, limit)));
        }
        if (labelled) {
            page = states(page, window);
        }
        return new Answer(page, acked, resolved);
    }

    /**
     * Which rules run, and whether they gather what a reader is shown beyond the id.
     *
     * @param evidence false when only the ids matter: no evidence traces, no code
     *        frames, no sample, no schema block, no database work (findings.adoc#state)
     */
    private record Scope(Set<String> kinds, boolean evidence) {

        static final Scope ALL = new Scope(Set.copyOf(Ranked.KINDS), true);

        boolean runs(String... any) {
            for (String kind : any) {
                if (kinds.contains(kind)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Every rule over the window, ranked, before anything a reader decided is applied. */
    private List<Ranked> rules(Window window, @Nullable String service) {
        return rules(window, service, Scope.ALL);
    }

    private List<Ranked> rules(Window window, @Nullable String service, Scope scope) {
        Reads reads = new Reads(sql, queries, window, service);
        boolean evidence = scope.evidence();
        List<Ranked> found = new ArrayList<>();
        if (scope.runs(ERROR)) {
            found.addAll(errors(window, service, reads, evidence));
        }
        if (scope.runs(LOG_ERROR)) {
            found.addAll(logErrors(window, service, reads, evidence));
        }
        if (scope.runs(N_PLUS_ONE)) {
            found.addAll(nPlusOne(window, service, reads, evidence));
        }
        if (scope.runs(N_PLUS_ONE_HTTP)) {
            found.addAll(nPlusOneHttp(window, service, reads, evidence));
        }
        if (scope.runs(SLOW_QUERY)) {
            found.addAll(slowQueries(window, service, reads, evidence));
        }
        if (scope.runs(SLOW_ENDPOINT)) {
            found.addAll(slowEndpoints(window, service, evidence));
        }
        if (scope.runs(SLOW_JOB)) {
            found.addAll(slowJobs(window, service, evidence));
        }
        if (scope.runs(SLOW_EXTERNAL)) {
            found.addAll(slowExternal(window, reads, evidence));
        }
        if (scope.runs(POOL_EXHAUSTED)) {
            found.addAll(poolExhausted(window, service));
        }
        if (scope.runs(GC_PAUSE, HEAP_PRESSURE, THREAD_GROWTH)) {
            found.addAll(jvm(window, service));
        }
        // The JVM rules come as one; a kind the scope did not ask for is dropped.
        found.removeIf(each -> !scope.kinds().contains(each.finding().kind()));
        found.sort(Ranked.ORDER);
        return found;
    }

    /** The findings the rules produce over a window for one service, by id. */
    private Map<String, Ranked> byId(Window window, String service) {
        Map<String, Ranked> byId = new LinkedHashMap<>();
        for (Ranked each : rules(window, service)) {
            byId.put(each.finding().id(), each);
        }
        return byId;
    }

    // --- resolutions and states ------------------------------------------------

    /**
     * The finding as it occurred after its resolution, or null when it has not.
     *
     * <p>A resolution older than the window means every occurrence in it is a
     * recurrence, and the finding is the window's own. A resolution inside the
     * window asks the rules again over the part of the window after it, for the
     * finding's service, so that the traffic before the fix does not count and
     * the evidence a regression carries is the evidence of its return. The part
     * is asked once per resolution instant and service, however many findings
     * share them.
     */
    private @Nullable Ranked recurrence(Ranked finding, long resolvedAt, Window window,
            Map<String, Map<String, Ranked>> since) {
        if (resolvedAt < window.from()) {
            return finding;
        }
        if (resolvedAt >= window.to()) {
            return null;
        }
        String service = finding.finding().service();
        Map<String, Ranked> after = since.computeIfAbsent(resolvedAt + " " + service,
                key -> byId(Window.of(resolvedAt + 1, window.to()), service));
        return after.get(finding.finding().id());
    }

    /**
     * A resolved finding that came back: severity {@code high}, the resolution's
     * instant and note and the original kind first in {@code numbers}, and the
     * rest as the rules found it after the resolution (findings.adoc#resolutions).
     *
     * <p>The id stays the finding's own, so resolving it again, or acknowledging
     * it, is the same command with the same id.
     */
    private static Finding regression(Finding back, Resolution resolution) {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put(RESOLVED_AT, resolution.at());
        numbers.put(NOTE, resolution.note());
        numbers.put(ORIGINAL_KIND, back.kind());
        numbers.putAll(back.numbers());
        String why = "came back after it was resolved"
                + (resolution.note() == null ? "" : " (" + resolution.note() + ")") + "; " + back.why();
        return new Finding(back.id(), REGRESSION, HIGH, back.service(), back.title(), why,
                back.subject(), numbers, back.statement(), back.code(), back.traces(), null,
                back.schema(), resolution, REGRESSED);
    }

    /**
     * Every finding labelled: {@code regressed} for a regression, {@code new} when
     * the rules do not find it in its service's previous run, {@code ongoing} when
     * they do (findings.adoc#state).
     *
     * <p>The previous run of a service is the time between its two newest
     * {@code start} marks at or before the end of the window: {@code [the one
     * before, the newest)}, from the beginning of the data when there is only one,
     * and nothing at all when there is none, so every finding of a service that
     * never restarted is {@code new}. The rules run once per service of the page,
     * only those of the kinds it has there, and for the ids alone.
     */
    private List<Finding> states(List<Finding> page, Window window) {
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        for (Finding finding : page) {
            if (!REGRESSION.equals(finding.kind())) {
                kinds.computeIfAbsent(finding.service(), service -> new HashSet<>()).add(finding.kind());
            }
        }
        Map<String, Set<String>> before = new HashMap<>();
        kinds.forEach((service, asked) -> before.put(service, previousRun(service, asked, window)));

        List<Finding> labelled = new ArrayList<>(page.size());
        for (Finding finding : page) {
            if (REGRESSION.equals(finding.kind())) {
                labelled.add(finding.withState(REGRESSED));
                continue;
            }
            Set<String> previous = before.getOrDefault(finding.service(), Set.of());
            labelled.add(finding.withState(previous.contains(finding.id()) ? ONGOING : NEW));
        }
        return labelled;
    }

    /**
     * The ids the rules of {@code asked} find over the previous run of a service.
     *
     * <p>A kind this server already asked about for the same run is read from
     * {@link #previousRuns}; the others run together, once, and are kept.
     */
    private Set<String> previousRun(String service, Set<String> asked, Window window) {
        List<Long> starts = sql.query("SELECT at_ms FROM mark WHERE name = ? AND service = ?"
                        + " AND at_ms <= ? ORDER BY at_ms DESC, id DESC LIMIT 2",
                List.of(Marks.START, service, window.to()), rs -> rs.getLong(1));
        if (starts.isEmpty() || starts.get(0) <= 0) {
            return Set.of();
        }
        long newest = starts.get(0);
        long from = starts.size() > 1 ? starts.get(1) : 0;
        String run = service + "\0" + from + "\0" + newest + "\0";

        Set<String> ids = new HashSet<>();
        Set<String> missing = new HashSet<>();
        for (String kind : asked) {
            Set<String> known = previousRuns.get(run + kind);
            if (known == null) {
                missing.add(kind);
            } else {
                ids.addAll(known);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Set<String>> byKind = new HashMap<>();
            for (String kind : missing) {
                byKind.put(kind, new HashSet<>());
            }
            for (Ranked each : rules(Window.of(from, newest - 1), service, new Scope(missing, false))) {
                Set<String> ofKind = byKind.get(each.finding().kind());
                if (ofKind != null) {
                    ofKind.add(each.finding().id());
                }
            }
            byKind.forEach((kind, found) -> {
                previousRuns.put(run + kind, Set.copyOf(found));
                ids.addAll(found);
            });
        }
        return ids;
    }

    /** A finding with the impact it is ranked by inside its kind. */
    record Ranked(Finding finding, double impact) {

        /** findings.adoc: the kind order that breaks a tie of severity. */
        private static final List<String> KINDS = List.of(REGRESSION, ERROR, N_PLUS_ONE, N_PLUS_ONE_HTTP,
                SLOW_QUERY, SLOW_ENDPOINT, SLOW_JOB, SLOW_EXTERNAL, LOG_ERROR, POOL_EXHAUSTED, GC_PAUSE,
                HEAP_PRESSURE, THREAD_GROWTH);

        static final Comparator<Ranked> ORDER = Comparator
                .comparingInt((Ranked r) -> severityRank(r.finding().severity()))
                .thenComparingInt(r -> KINDS.indexOf(r.finding().kind()))
                // Regressions among themselves keep the order their kinds had.
                .thenComparingInt(r -> KINDS.indexOf(r.finding().baseKind()))
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

    private List<Ranked> errors(Window window, @Nullable String service, Reads reads,
            boolean evidence) {
        List<Ranked> found = new ArrayList<>();
        List<Stats.ErrorGroup> groups = evidence
                ? queries.errors(window, service, GROUPS, null, reads::ancestry)
                : queries.errorGroups(window, service, GROUPS, null);
        for (Stats.ErrorGroup group : groups) {
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
                    Subject.error(group.errorId()),
                    numbers, null,
                    frames.ofStacktrace(stacktrace),
                    evidence ? traceIds(queries.tracesContaining(window, "error_id = ?",
                            group.errorId(), EVIDENCE_TRACES, false)) : List.of());
            found.add(new Ranked(finding, group.count()));
        }
        return found;
    }

    // --- log error -----------------------------------------------------------

    /**
     * One {@code (service, logger, normalised message)} group of {@code ERROR} log
     * records nothing else reports, folded as its records are read: the counts are
     * of every record, and only the newest few trace ids and the newest record's
     * attributes are kept as evidence.
     */
    private static final class LogGroup {
        final String service;
        final String logger;
        final String message;
        long count;
        long firstSeen;
        long lastSeen;
        final Map<String, long[]> byEndpoint = new LinkedHashMap<>();
        /** The newest distinct trace ids, oldest first. */
        final LinkedHashSet<String> traces = new LinkedHashSet<>();
        @Nullable String attributes;

        LogGroup(String service, String logger, String message) {
            this.service = service;
            this.logger = logger;
            this.message = message;
        }

        void add(long at, @Nullable String traceId, String endpoint, @Nullable String attributes) {
            if (count == 0) {
                firstSeen = at;
            }
            count++;
            lastSeen = at;
            byEndpoint.computeIfAbsent(endpoint, name -> new long[1])[0]++;
            if (traceId != null) {
                traces.remove(traceId);
                traces.add(traceId);
                if (traces.size() > EVIDENCE_TRACES) {
                    traces.remove(traces.iterator().next());
                }
            }
            this.attributes = attributes;
        }
    }

    /**
     * {@code ERROR} log records whose trace has no error span, grouped by
     * {@code (service, logger, normalised message)}.
     *
     * <p>This is what {@code catch (Exception e) { log.error(…, e); return fallback; }}
     * leaves behind: no span error, no exception event, one line in the log. A
     * record whose trace does carry an error span is already an {@code error}
     * finding, so the join drops it rather than reporting it twice (findings.adoc#log-error).
     *
     * <p>The grouping is the one {@link Ids#normaliseMessage} defines, which is a
     * Java regular expression rather than SQL, so the rows are read and grouped
     * here. Every row of the window is read, because {@code count} is what
     * {@code check --max-log-errors} sums and a cap on the rows would undercount it
     * (check.adoc#rules); what a group holds does not grow with its records.
     */
    private List<Ranked> logErrors(Window window, @Nullable String service, Reads reads,
            boolean evidence) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = "l.at_ms BETWEEN ? AND ? AND l.severity_number >= " + ERROR_SEVERITY
                + " AND (t.trace_id IS NULL OR t.error_count = 0)";
        if (service != null) {
            where = where + " AND l.service = ?";
            params.add(service);
        }
        Map<String, LogGroup> byGroup = new LinkedHashMap<>();
        sql.forEach("SELECT l.service AS service, l.logger AS logger, l.body AS body, l.at_ms AS at_ms,"
                + " l.trace_id AS trace_id, l.span_id AS span_id,"
                + (evidence ? " l.attributes AS attributes," : "")
                + " t.root_name AS root_name FROM log l"
                + " LEFT JOIN trace t ON t.trace_id = l.trace_id WHERE " + where
                + " ORDER BY l.at_ms, l.id", params, rs -> {
                    String serviceName = rs.getString("service");
                    String logger = logger(rs.getString("logger"));
                    String message = Ids.normaliseMessage(rs.getString("body"));
                    String key = serviceName + "\0" + logger + "\0" + message;
                    byGroup.computeIfAbsent(key, k -> new LogGroup(serviceName, logger, message))
                            .add(rs.getLong("at_ms"), rs.getString("trace_id"),
                                    evidence
                                            ? endpointOf(reads, rs.getString("span_id"),
                                                    rs.getString("root_name"))
                                            : endpointOf(null, null, rs.getString("root_name")),
                                    evidence ? rs.getString("attributes") : null);
                });

        List<Ranked> found = new ArrayList<>();
        for (LogGroup group : byGroup.values()) {
            List<Stats.EndpointCount> endpoints = new ArrayList<>();
            group.byEndpoint.forEach((name, count) -> endpoints.add(new Stats.EndpointCount(name, count[0])));
            endpoints.sort(Stats.EndpointCount.MOST_FIRST);

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("count", group.count);
            numbers.put("firstSeen", group.firstSeen);
            numbers.put("lastSeen", group.lastSeen);
            numbers.put("logger", group.logger);
            numbers.put("message", group.message);
            numbers.put("endpoints", endpointCounts(endpoints));

            List<String> traces = new ArrayList<>(group.traces);
            Collections.reverse(traces);
            String seenIn = endpoints.isEmpty() ? group.service : endpoints.get(0).name();
            Finding finding = new Finding(
                    id(LOG_ERROR, group.service, group.logger + "\0" + group.message),
                    LOG_ERROR, HIGH, group.service,
                    "ERROR in " + simpleName(group.logger) + ": " + cut(group.message, MESSAGE_IN_TITLE),
                    Numbers.plural(group.count, "record") + " in " + seenIn
                            + ", none of them on a failed trace; " + group.message,
                    Subject.logger(group.logger),
                    numbers, null,
                    frames.ofStacktrace(stacktraceOf(AttrJson.decode(group.attributes))),
                    List.copyOf(traces));
            found.add(new Ranked(finding, group.count));
        }
        return found;
    }

    /** The endpoint a log record belongs to, as an {@code error} finding's endpoints are found. */
    private static String endpointOf(@Nullable Reads reads, @Nullable String spanId,
            @Nullable String rootName) {
        if (reads != null && spanId != null && !spanId.isBlank()) {
            Queries.Ancestry.Entry entry = reads.ancestry().entryOf(spanId);
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

    /** What a statement's occurrence carries beyond the fold: its text, its name and its attributes. */
    private record StatementRun(@Nullable String statement, String queryName,
            Map<String, Object> attributes) {
    }

    /**
     * The same query group, five or more times under one entry span.
     *
     * <p>The candidates come from {@code GROUP BY trace_id, query_id} (storage.adoc),
     * which is one aggregate over the window; the spans of those pairs are then read
     * back and attributed to their entry span by the parent-chain walk the query
     * callers already use, because two endpoints of one trace each running the
     * statement four times is not an N+1 and grouping by trace alone cannot tell.
     */
    private List<Ranked> nPlusOne(Window window, @Nullable String service, Reads reads,
            boolean evidence) {
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

        Queries.Ancestry ancestry = reads.ancestry();
        List<Repeats.Occurrence<StatementRun>> occurrences = new ArrayList<>();
        List<String> traceList = List.copyOf(traceIds);
        for (int from = 0; from < traceList.size(); from += CANDIDATE_CHUNK) {
            List<String> chunk = traceList.subList(from, Math.min(from + CANDIDATE_CHUNK, traceList.size()));
            List<Object> params = new ArrayList<>();
            params.add(window.from());
            params.add(window.to());
            params.addAll(chunk);
            params.addAll(queryIds);
            String where = "start_ms BETWEEN ? AND ? AND trace_id IN (" + Sql.placeholders(chunk.size())
                    + ") AND query_id IN (" + Sql.placeholders(queryIds.size()) + ")";
            if (service != null) {
                where = where + " AND service = ?";
                params.add(service);
            }
            sql.forEach("SELECT span_id, trace_id, query_id, service, start_ms, duration_ns, db_statement,"
                    + " db_operation, db_table, attributes FROM span WHERE " + where
                    + " ORDER BY start_ms, span_id", params, rs -> {
                        String traceId = rs.getString("trace_id");
                        String queryId = rs.getString("query_id");
                        if (!pairs.contains(traceId + "\0" + queryId)) {
                            return;
                        }
                        Queries.Ancestry.Entry entry = ancestry.entryOf(rs.getString("span_id"));
                        if (entry == null) {
                            return;
                        }
                        Map<String, Object> attributes = evidence
                                ? AttrJson.decode(rs.getString("attributes")) : Map.of();
                        occurrences.add(new Repeats.Occurrence<>(traceId, entry.spanId(),
                                Ids.endpointId(entry.service(), entry.endpoint()), entry.endpoint(),
                                entry.service(), queryId, Rows.ms(rs, "duration_ns"),
                                rs.getLong("start_ms"),
                                // The extension captures the stack on the fifth repeat, so that one
                                // span of the group knows where the statement is issued from.
                                attributes.containsKey("code.stacktrace"),
                                new StatementRun(rs.getString("db_statement"),
                                        queryName(rs.getString("db_operation"), rs.getString("db_table"),
                                                rs.getString("db_statement")),
                                        attributes)));
                    });
        }

        Map<String, Long> requestsByEndpoint = new HashMap<>();
        // The catalog is one read per service, not one per repeated statement.
        Map<String, Map<String, List<Catalog.Table>>> catalogs = new HashMap<>();
        List<Ranked> found = new ArrayList<>();
        for (List<Repeats.RequestRepeat<StatementRun>> affected : Repeats.foldPerEntry(occurrences)) {
            Repeats.RepeatStats<StatementRun> stats = Repeats.RepeatStats.of(affected);
            Repeats.Occurrence<StatementRun> first = stats.first();
            String statement = first.detail().statement();
            List<String> code = List.of();
            if (evidence) {
                for (Repeats.RequestRepeat<StatementRun> repeat : stats.newest()) {
                    // The newest request that has a code location wins; none has one when the
                    // application ran without the extension, and then the finding names no line.
                    code = frames.ofAttributes(repeat.located().detail().attributes());
                    if (!code.isEmpty()) {
                        break;
                    }
                }
            }
            Finding finding = repeated(N_PLUS_ONE, stats,
                    requests(window, service, evidence, first.endpointId(), requestsByEndpoint),
                    "runs " + first.detail().queryName(), "statement",
                    Subject.repeatedQuery(first.endpointId(), first.groupKey()), statement, code)
                    .withSchema(evidence ? SchemaBlock.of(statement,
                            catalogs.computeIfAbsent(first.service(), catalog::forService)) : null);
            found.add(new Ranked(finding, stats.impact()));
        }
        return found;
    }

    /**
     * An N+1 finding of either kind: everything but what the rule names is the shared
     * fold's (findings.adoc#n-plus-one-http).
     *
     * @param action what the endpoint does that many times a request: {@code runs SELECT
     *        order_line} or {@code calls GET localhost:8081/api/books/?}
     * @param unit   what repeats, in the {@code why}: {@code statement} or {@code call}
     */
    private Finding repeated(String kind, Repeats.RepeatStats<?> stats, long requests,
            String action, String unit, Subject subject, @Nullable String statement,
            List<String> code) {
        Repeats.Occurrence<?> first = stats.first();
        return new Finding(
                id(kind, first.service(), first.endpointId() + "\0" + first.groupKey()),
                kind, stats.severity(tingles.slowRequestMs()), first.service(),
                first.endpoint() + " " + action + " " + stats.median() + " times per request",
                stats.why(requests, unit),
                subject, stats.numbers(requests), statement, code, stats.evidenceTraces());
    }

    private List<String[]> candidates(Window window, @Nullable String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = "start_ms BETWEEN ? AND ? AND query_id IS NOT NULL";
        if (service != null) {
            where = where + " AND service = ?";
            params.add(service);
        }
        // Every pair of the window, not the most repeated few: affected and requests
        // must count the same population (findings.adoc#n-plus-one).
        return sql.query("SELECT trace_id, query_id FROM span WHERE " + where
                        + " GROUP BY trace_id, query_id HAVING COUNT(*) >= " + Repeats.MIN_REPEATS
                        + " ORDER BY trace_id, query_id",
                params, rs -> new String[]{rs.getString("trace_id"), rs.getString("query_id")});
    }

    /**
     * The requests of an endpoint over the window, read once per endpoint for one run
     * of a rule, and not at all when only the ids matter.
     */
    private long requests(Window window, @Nullable String service, boolean evidence,
            String endpointId, Map<String, Long> byEndpoint) {
        return evidence ? byEndpoint.computeIfAbsent(endpointId, id -> requests(window, service, id)) : 0;
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

    // --- n + 1 over HTTP ------------------------------------------------------

    /**
     * The same outbound call, five or more times under one entry span.
     *
     * <p>The N+1 an ORM cannot cause: a loop that fetches one remote resource per
     * item. It is {@link #nPlusOne}'s rule with the call in place of the statement,
     * and it reads what {@link #slowExternal} reads — the window's outbound HTTP
     * spans and the parent-chain walk — because the call's target lives in the
     * attributes rather than in a column.
     */
    private List<Ranked> nPlusOneHttp(Window window, @Nullable String service, Reads reads,
            boolean evidence) {
        Queries.Ancestry ancestry = reads.ancestry();
        List<Repeats.Occurrence<Queries.OutboundCall>> occurrences = new ArrayList<>();
        for (Queries.OutboundCall span : reads.outboundHttp()) {
            Queries.Ancestry.Entry entry = ancestry.entryOf(span.spanId());
            if (entry == null) {
                continue;
            }
            // The extension captures the stack on the fifth repeat, as it does for a statement.
            occurrences.add(new Repeats.Occurrence<>(span.traceId(), entry.spanId(),
                    Ids.endpointId(entry.service(), entry.endpoint()), entry.endpoint(),
                    entry.service(), span.call(), span.durationMillis(), span.startMillis(),
                    span.located(), span));
        }

        Map<String, Long> requestsByEndpoint = new HashMap<>();
        List<Ranked> found = new ArrayList<>();
        for (List<Repeats.RequestRepeat<Queries.OutboundCall>> affected
                : Repeats.foldPerEntry(occurrences)) {
            Repeats.RepeatStats<Queries.OutboundCall> stats = Repeats.RepeatStats.of(affected);
            Repeats.Occurrence<Queries.OutboundCall> first = stats.first();
            Finding finding = repeated(N_PLUS_ONE_HTTP, stats,
                    requests(window, service, evidence, first.endpointId(), requestsByEndpoint),
                    "calls " + first.groupKey(), "call",
                    Subject.repeatedCall(first.endpointId(), first.detail().target()), null,
                    evidence ? callCode(stats.newest()) : List.of());
            found.add(new Ranked(finding, stats.impact()));
        }
        return found;
    }

    /** How many located repeats an {@code n-plus-one-http} reads the attributes of for its code. */
    private static final int CODE_ATTEMPTS = 20;

    /**
     * The code of an {@code n-plus-one-http}: the frames of the newest repeat whose call
     * carries {@code code.stacktrace} and names an application frame, else what the
     * newest repeat's own call names.
     *
     * <p>The attributes are read again by span id, one call at a time, so the window's
     * outbound calls are held without them.
     */
    private List<String> callCode(List<Repeats.RequestRepeat<Queries.OutboundCall>> newest) {
        int attempts = 0;
        for (Repeats.RequestRepeat<Queries.OutboundCall> repeat : newest) {
            Queries.OutboundCall call = repeat.located().detail();
            if (!call.located()) {
                continue;
            }
            List<String> code = frames.ofAttributes(attributesOf(call.traceId(), call.spanId()));
            if (!code.isEmpty() || ++attempts >= CODE_ATTEMPTS) {
                return code;
            }
        }
        Queries.OutboundCall call = newest.get(0).located().detail();
        return call.located() ? List.of()
                : frames.ofAttributes(attributesOf(call.traceId(), call.spanId()));
    }

    // --- slow query ----------------------------------------------------------

    private List<Ranked> slowQueries(Window window, @Nullable String service, Reads reads,
            boolean evidence) {
        // Every group over the threshold, however it ranks by total time: the cut of a
        // list applied before the threshold would hide a slow group behind fast frequent ones.
        List<Stats.QueryStats> slow = evidence
                ? queries.slowQueries(window, service, reads::ancestry)
                : queries.slowQueryGroups(window, service);
        if (slow.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Stats.QueryStats query : slow) {
            ids.add(query.queryId());
        }
        Map<String, Map<String, Object>> samples = evidence
                ? sampleAttributes(window, "query_id", ids) : Map.of();

        List<Ranked> found = new ArrayList<>();
        for (Stats.QueryStats query : slow) {
            Map<String, Object> numbers = new LinkedHashMap<>();
            String name = queryName(query.operation(), query.table(), query.statement());
            numbers.put("calls", query.calls());
            numbers.put("slowCalls", query.slowCalls());
            new SlowGroup(query.service(), name, query.calls(), query.p50Ms(), query.p95Ms(),
                    query.maxMs(), query.totalMs()).putPercentiles(numbers);
            numbers.put("callers", callers(query.callers()));

            String severity = query.p95Ms() > SLOW_QUERY_HIGH_FACTOR * tingles.slowQueryMs()
                    ? HIGH : MEDIUM;
            Finding finding = new Finding(
                    id(SLOW_QUERY, query.service(), query.queryId()),
                    SLOW_QUERY, severity, query.service(),
                    name + " is slow",
                    "p95 " + Numbers.millis(query.p95Ms()) + " over "
                            + Numbers.plural(query.calls(), "call") + ", "
                            + query.slowCalls() + " of them over " + tingles.slowQueryMs() + " ms; "
                            + Numbers.millis(query.totalMs()) + " in total",
                    Subject.query(query.queryId()),
                    numbers, query.statement(),
                    frames.ofAttributes(samples.get(query.queryId())),
                    evidence ? traceIds(queries.tracesContaining(window, "query_id = ?",
                            query.queryId(), EVIDENCE_TRACES, true)) : List.of())
                    // The group already carries the block /api/queries shows; a finding
                    // and a query row never disagree about the same statement.
                    .withSchema(query.schema());
            found.add(new Ranked(finding, query.totalMs()));
        }
        return found;
    }

    // --- slow endpoint -------------------------------------------------------

    private List<Ranked> slowEndpoints(Window window, @Nullable String service, boolean evidence) {
        List<Stats.EndpointStats> slow = new ArrayList<>();
        for (Stats.EndpointStats endpoint : queries.endpoints(window, service, null, false)) {
            if (endpoint.p95Ms() > tingles.slowRequestMs()) {
                slow.add(endpoint);
            }
        }
        if (slow.isEmpty()) {
            return List.of();
        }
        // Only the endpoints that crossed the threshold become findings, so only they
        // are joined with their database spans.
        List<String> slowIds = new ArrayList<>();
        for (Stats.EndpointStats endpoint : slow) {
            slowIds.add(endpoint.endpointId());
        }
        Map<String, Queries.DbWork> databaseWork = evidence
                ? queries.databaseWork(window, service, slowIds) : Map.of();

        List<Ranked> found = new ArrayList<>();
        for (Stats.EndpointStats endpoint : slow) {
            SlowGroup group = SlowGroup.of(endpoint);
            Queries.DbWork work = databaseWork.getOrDefault(endpoint.endpointId(), Queries.DbWork.NONE);
            TimeEvidence time = timeEvidence(window, endpoint.service(), evidence
                    ? traceIds(queries.tracesContaining(window, "endpoint_id = ?",
                            endpoint.endpointId(), SAMPLE_TRACES, true))
                    : List.of());

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("calls", group.count());
            group.putPercentiles(numbers);
            numbers.put("apdex", endpoint.apdex());
            putDatabaseWork(numbers, group, work, "Request");
            time.putInto(numbers);

            Finding finding = new Finding(
                    id(SLOW_ENDPOINT, endpoint.service(), endpoint.endpointId()),
                    SLOW_ENDPOINT, slowSeverity(group.p95Ms()), endpoint.service(),
                    endpoint.name() + " is slow",
                    slowWhy(group, work, "call", "request"),
                    Subject.endpoint(endpoint.endpointId()),
                    numbers, null, List.of(), time.traces());
            found.add(new Ranked(finding, endpoint.totalMs()));
        }
        return found;
    }

    /**
     * {@code high} past the Apdex "frustrated" bound, four times {@code slow.request.ms},
     * {@code medium} otherwise: the rule {@code slow-endpoint}, {@code slow-job} and
     * {@code slow-external} share (findings.adoc#slow-endpoint).
     */
    private String slowSeverity(double p95Ms) {
        return p95Ms > queries.responseBuckets().frustratedMs() ? HIGH : MEDIUM;
    }

    /**
     * The database work of a group per request (or per run) and its share of the
     * group's time, as {@code dbCallsPer…}, {@code dbMsPer…} and {@code dbShare}.
     *
     * @param per {@code Request} or {@code Run}, the end of the two keys
     */
    private static void putDatabaseWork(Map<String, Object> numbers, SlowGroup group,
            Queries.DbWork work, String per) {
        numbers.put("dbCallsPer" + per, work.callsPer(group.count()));
        numbers.put("dbMsPer" + per, work.msPer(group.count()));
        numbers.put("dbShare", work.shareOf(group.totalMs()));
    }

    /**
     * {@code p95 812 ms over 40 calls; 12 database calls and 90 ms per request, 11% of
     * the time}.
     *
     * @param counted what the group counts: {@code call} or {@code run}
     * @param per     what the database work is per: {@code request} or {@code run}
     */
    private static String slowWhy(SlowGroup group, Queries.DbWork work, String counted,
            String per) {
        return "p95 " + Numbers.millis(group.p95Ms()) + " over "
                + Numbers.plural(group.count(), counted) + "; "
                + Numbers.number(work.callsPer(group.count())) + " database calls and "
                + Numbers.millis(work.msPer(group.count())) + " per " + per + ", "
                + Numbers.percent(work.shareOf(group.totalMs())) + " of the time";
    }

    /**
     * Where the time of a slow group went, read from a sample of its slowest traces:
     * the first {@link #EVIDENCE_TRACES} are the finding's own evidence, the first of
     * them names the hot span, and the whole sample is split into the aggregated hot
     * spans and the breakdown (findings.adoc#time).
     */
    private record TimeEvidence(List<String> traces, @Nullable Map<String, Object> hotSpan,
            Queries.TimeSplit split) {

        void putInto(Map<String, Object> numbers) {
            numbers.put("hotSpan", hotSpan);
            numbers.put("hotSpans", hotSpans(split));
            numbers.put("breakdown", split.breakdown());
        }
    }

    /** The time evidence of a sample; an empty sample, when only the ids matter, reads nothing. */
    private TimeEvidence timeEvidence(Window window, String service, List<String> sample) {
        List<String> traces = evidenceOf(sample);
        return new TimeEvidence(traces, hotSpan(traces), queries.timeSplit(window, service, sample));
    }

    // --- slow job ------------------------------------------------------------

    /**
     * A job is a root {@code INTERNAL} span (design.adoc#endpoint-identity): a scheduled method, an
     * {@code @Async} call, a batch step.
     *
     * <p>A job is never an entry span, so it is in no request count, in no Apdex and
     * in no {@code check} verdict; this rule is the one place a slow scheduler tick
     * or batch step is reported, and it measures over the runs of a job exactly what
     * {@code slow-endpoint} measures over the requests of an endpoint.
     */
    private List<Ranked> slowJobs(Window window, @Nullable String service, boolean evidence) {
        List<SlowGroup> slow = slowJobGroups(window, service);
        if (slow.isEmpty()) {
            return List.of();
        }
        // Only the jobs that crossed the threshold become findings, so only they are
        // joined with their database spans, one service at a time.
        Map<String, Set<String>> slowNames = new LinkedHashMap<>();
        for (SlowGroup job : slow) {
            slowNames.computeIfAbsent(job.service(), name -> new LinkedHashSet<>()).add(job.name());
        }
        Map<String, Queries.DbWork> databaseWork = new HashMap<>();
        if (evidence) {
            slowNames.forEach((name, names) ->
                    databaseWork.putAll(queries.jobDatabaseWork(window, name, names)));
        }

        List<Ranked> found = new ArrayList<>();
        for (SlowGroup job : slow) {
            String key = job.service() + "\0" + job.name();
            Queries.DbWork work = databaseWork.getOrDefault(key, Queries.DbWork.NONE);
            TimeEvidence time = timeEvidence(window, job.service(), evidence
                    ? traceIds(queries.tracesContaining(window,
                            "parent_span_id IS NULL AND s.kind = 'INTERNAL' AND s.service = ? AND s.name = ?",
                            List.of(job.service(), job.name()), SAMPLE_TRACES, true))
                    : List.of());

            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("runs", job.count());
            job.putPercentiles(numbers);
            putDatabaseWork(numbers, job, work, "Run");
            time.putInto(numbers);

            Finding finding = new Finding(
                    id(SLOW_JOB, job.service(), job.name()),
                    SLOW_JOB, slowSeverity(job.p95Ms()), job.service(),
                    job.name() + " is slow",
                    slowWhy(job, work, "run", "run"),
                    Subject.job(job.name()),
                    numbers, null,
                    evidence ? frames.ofAttributes(newestRun(window, job)) : List.of(), time.traces());
            found.add(new Ranked(finding, job.totalMs()));
        }
        return found;
    }

    /**
     * The job groups of the window whose p95 exceeds {@code slow.request.ms}, the
     * heaviest first (storage.adoc).
     *
     * <p>Every one of them: the threshold is a {@code HAVING}, so a slow job that ranks
     * low by total time is still a finding (findings.adoc#slow-job).
     */
    private List<SlowGroup> slowJobGroups(Window window, @Nullable String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = jobWhere(service, params);
        return sql.query("SELECT service, name, COUNT(*) AS runs,"
                        + " PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_ns) AS p50_ns,"
                        + " PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns) AS p95_ns,"
                        + " MAX(duration_ns) AS max_ns, SUM(duration_ns) AS total_ns FROM span WHERE "
                        + where + " GROUP BY service, name"
                        + " HAVING PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns) > "
                        + tingles.slowRequestMs() * 1_000_000L
                        + " ORDER BY total_ns DESC, service, name",
                params, rs -> new SlowGroup(rs.getString("service"), rs.getString("name"),
                        rs.getLong("runs"), Rows.ms(rs, "p50_ns"), Rows.ms(rs, "p95_ns"),
                        Rows.ms(rs, "max_ns"), Rows.ms(rs, "total_ns")));
    }

    /**
     * The attributes of a job's newest run, for the {@code code.*} frames.
     *
     * <p>One statement per slow job rather than one over every job group: the
     * service's {@code (service, start_ms)} index is read backwards from the end of
     * the window and stops at the first run, where a statement over every group
     * would read every span of the window.
     */
    private @Nullable Map<String, Object> newestRun(Window window, SlowGroup job) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String where = jobWhere(job.service(), params) + " AND name = ?";
        params.add(job.name());
        return sql.queryOne("SELECT attributes FROM span WHERE " + where
                + " ORDER BY start_ms DESC, id DESC LIMIT 1", params,
                rs -> AttrJson.decode(rs.getString("attributes")));
    }

    /** A run of a job: a root {@code INTERNAL} span of the window (design.adoc#endpoint-identity). */
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
    private List<Ranked> slowExternal(Window window, Reads reads, boolean evidence) {
        Map<String, List<Queries.OutboundCall>> byGroup = new LinkedHashMap<>();
        for (Queries.OutboundCall span : reads.outboundHttp()) {
            byGroup.computeIfAbsent(
                    span.service() + "\0" + span.target() + "\0" + span.name(),
                    key -> new ArrayList<>()).add(span);
        }
        List<Ranked> found = new ArrayList<>();
        byGroup.forEach((key, calls) -> {
            String[] parts = key.split("\0", 3);
            Ranked ranked = external(window, evidence ? reads : null, parts[0], parts[1], parts[2],
                    calls);
            if (ranked != null) {
                found.add(ranked);
            }
        });
        return found;
    }

    /** One group; with no {@code reads} it is the id alone, with no callers and no evidence. */
    private @Nullable Ranked external(Window window, @Nullable Reads reads, String service,
            String target, String name, List<Queries.OutboundCall> calls) {
        double[] durations = new double[calls.size()];
        double totalMs = 0;
        long errors = 0;
        // The newest call that carries code.stacktrace, which the extension writes only on a
        // slow one (findings.adoc#code), else the newest call.
        Queries.OutboundCall newest = null;
        for (int i = 0; i < calls.size(); i++) {
            Queries.OutboundCall call = calls.get(i);
            durations[i] = call.durationMillis();
            totalMs += durations[i];
            if (call.error()) {
                errors++;
            }
            if (newest == null || (call.located() && !newest.located())
                    || (call.located() == newest.located() && call.startNanos() > newest.startNanos())) {
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
        new SlowGroup(service, name, calls.size(), Queries.percentile(durations, 0.5), p95Ms,
                durations[durations.length - 1], totalMs).putPercentiles(numbers);
        numbers.put("callers", reads == null ? List.of()
                : callers(externalCallers(reads, calls, service)));

        Finding finding = new Finding(
                id(SLOW_EXTERNAL, service, target + "\0" + name),
                SLOW_EXTERNAL, slowSeverity(p95Ms), service,
                name + " " + target + " is slow",
                "p95 " + Numbers.millis(p95Ms) + " over "
                        + Numbers.plural(calls.size(), "call") + ", "
                        + Numbers.plural(errors, "error") + "; "
                        + Numbers.millis(totalMs) + " in total",
                Subject.target(target),
                numbers, null,
                reads == null || newest == null ? List.of()
                        : frames.ofAttributes(attributesOf(newest.traceId(), newest.spanId())),
                reads == null ? List.of() : externalTraces(window, calls));
        return new Ranked(finding, totalMs);
    }

    /** The attributes of one span, read again for the one call a finding takes its code from. */
    private @Nullable Map<String, Object> attributesOf(String traceId, String spanId) {
        return sql.queryOne("SELECT attributes FROM span WHERE trace_id = ? AND span_id = ?",
                List.of(traceId, spanId), rs -> AttrJson.decode(rs.getString("attributes")));
    }

    /** Which endpoints made the calls: the nearest entry span up the chain, as a query's callers. */
    private static List<Stats.Caller> externalCallers(Reads reads, List<Queries.OutboundCall> calls,
            String service) {
        Queries.Ancestry ancestry = reads.ancestry();
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> byService = new LinkedHashMap<>();
        for (Queries.OutboundCall call : calls) {
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
        list.sort(Stats.Caller.MOST_FIRST);
        return list;
    }

    /**
     * The three slowest traces that contain one of the group's calls.
     *
     * <p>The group's target is not a column, so the traces of its slowest calls are
     * the candidates the store then orders by trace duration; the cap keeps the
     * {@code IN} list bounded on a group with thousands of calls.
     */
    private List<String> externalTraces(Window window, List<Queries.OutboundCall> calls) {
        List<Queries.OutboundCall> slowest = new ArrayList<>(calls);
        slowest.sort(Comparator.comparingLong(Queries.OutboundCall::durationNanos).reversed());
        Set<String> candidates = new LinkedHashSet<>();
        for (Queries.OutboundCall call : slowest) {
            if (candidates.size() >= EVIDENCE_CANDIDATES) {
                break;
            }
            candidates.add(call.traceId());
        }
        return traceIds(queries.slowestOf(window, candidates, EVIDENCE_TRACES));
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
     *
     * <p>{@code usedMax} is the highest usage of the window, not the usage at the
     * worst point: the most waiting and the fullest pool need not be the same
     * moment, and "{@code usedMax} equal to {@code max}" has to read as "the pool
     * was full" whichever came first (findings.adoc#pool-exhausted).
     */
    static @Nullable Ranked exhausted(String service, JvmView.ConnectionPool pool) {
        long at = 0;
        double worstPending = 0;
        double worstUsed = 0;
        double usedMax = 0;
        double max = Double.NaN;
        boolean exhausted = false;
        for (int i = 0; i < pool.t().length; i++) {
            double pending = at(pool.pending(), i);
            double used = at(pool.used(), i);
            double limit = at(pool.max(), i);
            if (!Double.isNaN(used)) {
                usedMax = Math.max(usedMax, used);
            }
            if (Double.isNaN(max) && !Double.isNaN(limit)) {
                max = limit;
            }
            boolean waiting = !Double.isNaN(pending) && pending > 0;
            boolean full = !Double.isNaN(used) && !Double.isNaN(limit) && limit > 0 && used >= limit;
            if (!waiting && !full) {
                continue;
            }
            double pendingValue = Double.isNaN(pending) ? 0 : pending;
            double usedValue = Double.isNaN(used) ? 0 : used;
            if (!exhausted || pendingValue > worstPending
                    || (pendingValue == worstPending && usedValue > worstUsed)) {
                worstPending = pendingValue;
                worstUsed = usedValue;
                at = pool.t()[i];
                if (!Double.isNaN(limit)) {
                    max = limit;
                }
            }
            exhausted = true;
        }
        if (!exhausted) {
            return null;
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("pool", pool.name());
        numbers.put("max", Double.isNaN(max) ? null : max);
        numbers.put("usedMax", usedMax);
        numbers.put("pendingMax", worstPending);
        numbers.put("at", at);

        String limit = Double.isNaN(max) ? "its maximum" : Numbers.number(max);
        Finding finding = new Finding(
                id(POOL_EXHAUSTED, service, pool.name()),
                POOL_EXHAUSTED, HIGH, service,
                pool.name() + " ran out of connections",
                "up to " + Numbers.number(usedMax) + " of " + limit + " connections in use and up to "
                        + Numbers.number(worstPending) + " requests waiting",
                Subject.pool(pool.name()),
                numbers, null, List.of(), List.of());
        // Ranked by the most waiting, then by the fullest: no pool has a million connections,
        // so the waiting count dominates and the usage only breaks a tie.
        return new Ranked(finding, worstPending * 1_000_000 + usedMax);
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
     * The three rules that read the JVM page's own series (findings.adoc#kinds).
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
                add(found, gcPause(name, series, tingles.slowRequestMs()));
            }
            add(found, heapPressure(name, JvmView.heap(metrics, name, window)));
            add(found, threadGrowth(name, JvmView.threads(metrics, name, window)));
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
    static @Nullable Ranked gcPause(String service, MetricQueries.SeriesData series,
            long slowRequestMs) {
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
            if (!cumulative) {
                double longest = point.max() * toMillis;
                if (longest > worstMs) {
                    worstMs = longest;
                    worstAt = point.at();
                }
            }
            if (cumulative && i == 0) {
                continue;
            }
            long count = point.count();
            double sum = point.sum();
            if (cumulative) {
                MetricPoint previous = points.get(i - 1);
                count -= previous.count();
                sum -= previous.sum();
                // A cumulative point's max is the longest collection since the JVM started, so
                // it names this interval only when it rose in it; the interval's mean is a
                // collection at least that long in any case.
                double longest = count > 0 ? sum / count * toMillis : 0;
                if (count > 0 && point.max() > previous.max()) {
                    longest = Math.max(longest, point.max() * toMillis);
                }
                if (longest > worstMs) {
                    worstMs = longest;
                    worstAt = point.at();
                }
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
        boolean paused = worstMs >= slowRequestMs;
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
                Subject.jvm("gc:" + name),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, worstMs);
    }

    /** The heap against its limit, summed over the pools exactly as the JVM page sums them. */
    static @Nullable Ranked heapPressure(String service, JvmView.Memory heap) {
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
                Numbers.number(usedMax / BYTES_PER_MIB) + " MiB of "
                        + Numbers.number(limit / BYTES_PER_MIB) + " MiB in use at the worst point",
                Subject.jvm("heap"),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, ratioMax);
    }

    /** Threads at the end of the window against threads at its start. */
    static @Nullable Ranked threadGrowth(String service, JvmView.Threads threads) {
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
                Subject.jvm("threads"),
                numbers, null, List.of(), List.of());
        return new Ranked(finding, last - first);
    }

    /** A value of an aligned series, or {@link Double#NaN} when the series is shorter. */
    private static double at(double[] values, int index) {
        return values.length > index ? values[index] : Double.NaN;
    }

    // --- shared --------------------------------------------------------------

    /**
     * The scans of one window that more than one rule reads, each made at most
     * once for one run of the rules.
     *
     * <p>The parent-chain walk ({@link Queries.Ancestry#of}) is read by the query
     * callers, the error endpoints, the N+1s, the outbound calls and the log errors;
     * the outbound HTTP spans by {@code n-plus-one-http} and {@code slow-external}.
     * Each is a scan of the window's spans, so it is made lazily, when the first rule
     * asks, and handed to the others. The query callers and the error endpoints get
     * the same walk {@code /api/queries} and {@code /api/errors} build for the same
     * window and service, so their numbers stay the list's numbers.
     */
    private static final class Reads {

        private final Sql sql;
        private final Queries queries;
        private final Window window;
        private final @Nullable String service;
        private Queries.@Nullable Ancestry ancestry;
        private @Nullable List<Queries.OutboundCall> outboundHttp;

        private Reads(Sql sql, Queries queries, Window window, @Nullable String service) {
            this.sql = sql;
            this.queries = queries;
            this.window = window;
            this.service = service;
        }

        Queries.Ancestry ancestry() {
            Queries.Ancestry loaded = ancestry;
            if (loaded == null) {
                loaded = Queries.Ancestry.of(sql, window, service);
                ancestry = loaded;
            }
            return loaded;
        }

        List<Queries.OutboundCall> outboundHttp() {
            List<Queries.OutboundCall> loaded = outboundHttp;
            if (loaded == null) {
                loaded = queries.outboundHttp(window, service);
                outboundHttp = loaded;
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

    /** The evidence a finding carries, which is the head of the sample it read. */
    private static List<String> evidenceOf(List<String> sample) {
        return List.copyOf(sample.subList(0, Math.min(EVIDENCE_TRACES, sample.size())));
    }

    /**
     * The aggregated hot spans as {@code numbers} carries them: a list of small maps,
     * as {@code callers} and {@code endpoints} are (findings.adoc#hot-span).
     */
    private static List<Map<String, Object>> hotSpans(Queries.TimeSplit split) {
        List<Map<String, Object>> list = new ArrayList<>(split.hotSpans().size());
        for (Queries.HotSpan hot : split.hotSpans()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", hot.name());
            entry.put("category", hot.category());
            entry.put("selfMs", hot.selfMs());
            entry.put("share", hot.share());
            entry.put("count", hot.count());
            list.add(entry);
        }
        return list;
    }

    /** One line, cut at {@code max} characters with an ellipsis. */
    private static String cut(@Nullable String text, int max) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /**
     * The id findings.adoc#fields promises: stable across windows, because it hashes what the
     * finding is about and nothing about when it was found.
     */
    private static String id(String kind, String service, String subject) {
        return kind + ":" + Ids.shortHash(kind + "\0" + service + "\0" + subject);
    }

    /**
     * The attributes of one span per group, for the {@code code.*} frames: the newest
     * that carries {@code code.stacktrace}, which the extension writes only on a slow
     * statement (findings.adoc#code), else the newest.
     */
    private Map<String, Map<String, Object>> sampleAttributes(Window window, String column,
            Set<String> ids) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        params.addAll(ids);
        Map<String, Map<String, Object>> samples = new HashMap<>();
        sql.forEach("SELECT * FROM (SELECT " + column + " AS group_id, attributes,"
                + " ROW_NUMBER() OVER (PARTITION BY " + column
                + " ORDER BY CASE WHEN attributes LIKE '%\"code.stacktrace\"%' THEN 0 ELSE 1 END,"
                + " start_ms DESC, id DESC) AS rn"
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
        return cut(statement, STATEMENT_IN_TITLE);
    }

    /** {@code java.lang.IllegalStateException} is said as {@code IllegalStateException}. */
    public static String simpleName(@Nullable String type) {
        if (type == null) {
            return "error";
        }
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }
}
