package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Two windows side by side, endpoint by endpoint, query by query, error by error.
 *
 * <p>The question an agent asks after a change is "did it help", and a number on
 * its own cannot answer it: 812 ms is only bad beside the 120 ms it used to be.
 * So the usual loop is {@code mark before}, exercise, fix, {@code mark after},
 * exercise, compare (marks-and-compare.adoc#compare).
 *
 * <p>A verdict is deliberately blunt — {@code worse}, {@code better},
 * {@code same}, {@code new}, {@code gone} — and deliberately deaf to noise: a
 * change has to be both more than a fifth and more than ten milliseconds (half a
 * call per request for a query) before it is called anything but {@code same},
 * because a local machine's timings wobble by more than that on their own.
 */
public final class Compare {

    public static final String NEW = "new";
    public static final String GONE = "gone";
    public static final String WORSE = "worse";
    public static final String BETTER = "better";
    public static final String SAME = "same";

    /** How much a percentile has to move before it means anything. */
    private static final double RELATIVE = 0.2;
    private static final double ABSOLUTE_MS = 10;
    private static final double ABSOLUTE_CALLS = 0.5;

    public record Side(long calls, long errors, double p50Ms, double p95Ms, double maxMs,
            double dbCallsPerRequest, double dbMsPerRequest) {
    }

    public record QuerySide(long calls, double callsPerRequest, double p95Ms, double totalMs) {
    }

    public record EndpointDiff(String endpointId, String service, String name,
            @Nullable Side before, @Nullable Side after, String verdict) {
    }

    public record QueryDiff(String queryId, String service, @Nullable String statement,
            @Nullable QuerySide before, @Nullable QuerySide after, String verdict) {
    }

    public record ErrorDiff(String errorId, String service, @Nullable String type,
            @Nullable String message, long before, long after, String verdict) {
    }

    /** The whole answer of {@code GET /api/compare}. */
    public record Comparison(Window before, Window after, Stats.Totals beforeTotals,
            Stats.Totals afterTotals, List<EndpointDiff> endpoints, List<QueryDiff> queries,
            List<ErrorDiff> errors) {
    }

    private final Queries queries;

    public Compare(Queries queries) {
        this.queries = queries;
    }

    public Comparison compare(Window before, Window after, @Nullable String service) {
        Map<String, Stats.EndpointStats> beforeEndpoints = endpoints(before, service);
        Map<String, Stats.EndpointStats> afterEndpoints = endpoints(after, service);
        Map<String, Queries.DbWork> beforeWork = queries.databaseWork(before, service);
        Map<String, Queries.DbWork> afterWork = queries.databaseWork(after, service);

        Map<String, Double> weight = new HashMap<>();
        List<EndpointDiff> endpointDiffs = new ArrayList<>();
        for (String id : union(beforeEndpoints.keySet(), afterEndpoints.keySet())) {
            Stats.EndpointStats one = beforeEndpoints.get(id);
            Stats.EndpointStats two = afterEndpoints.get(id);
            Stats.EndpointStats any = Objects.requireNonNull(one != null ? one : two,
                    "the id came from one of the two windows");
            Side sideBefore = side(one, beforeWork.get(id));
            Side sideAfter = side(two, afterWork.get(id));
            weight.put(id, (one == null ? 0 : one.totalMs()) + (two == null ? 0 : two.totalMs()));
            endpointDiffs.add(new EndpointDiff(id, any.service(), any.name(), sideBefore, sideAfter,
                    verdict(sideBefore, sideAfter)));
        }
        endpointDiffs.sort(order(EndpointDiff::verdict, diff -> weight.get(diff.endpointId()),
                EndpointDiff::endpointId));

        long beforeRequests = queries.totals(before, service).requests();
        long afterRequests = queries.totals(after, service).requests();
        Map<String, Stats.QueryStats> beforeQueries = queries(before, service);
        Map<String, Stats.QueryStats> afterQueries = queries(after, service);
        Map<String, Double> queryWeight = new HashMap<>();
        List<QueryDiff> queryDiffs = new ArrayList<>();
        for (String id : union(beforeQueries.keySet(), afterQueries.keySet())) {
            Stats.QueryStats one = beforeQueries.get(id);
            Stats.QueryStats two = afterQueries.get(id);
            Stats.QueryStats any = Objects.requireNonNull(one != null ? one : two,
                    "the id came from one of the two windows");
            QuerySide sideBefore = querySide(one, beforeRequests);
            QuerySide sideAfter = querySide(two, afterRequests);
            queryWeight.put(id, (one == null ? 0 : one.totalMs()) + (two == null ? 0 : two.totalMs()));
            queryDiffs.add(new QueryDiff(id, any.service(), any.statement(), sideBefore, sideAfter,
                    queryVerdict(sideBefore, sideAfter)));
        }
        queryDiffs.sort(order(QueryDiff::verdict, diff -> queryWeight.get(diff.queryId()),
                QueryDiff::queryId));

        Map<String, Stats.ErrorGroup> beforeErrors = errors(before, service);
        Map<String, Stats.ErrorGroup> afterErrors = errors(after, service);
        List<ErrorDiff> errorDiffs = new ArrayList<>();
        for (String id : union(beforeErrors.keySet(), afterErrors.keySet())) {
            Stats.ErrorGroup one = beforeErrors.get(id);
            Stats.ErrorGroup two = afterErrors.get(id);
            Stats.ErrorGroup any = Objects.requireNonNull(one != null ? one : two,
                    "the id came from one of the two windows");
            long countBefore = one == null ? 0 : one.count();
            long countAfter = two == null ? 0 : two.count();
            errorDiffs.add(new ErrorDiff(id, any.service(), any.type(), any.message(), countBefore,
                    countAfter, errorVerdict(countBefore, countAfter)));
        }
        errorDiffs.sort(order(ErrorDiff::verdict,
                diff -> (double) Math.max(diff.before(), diff.after()), ErrorDiff::errorId));

        return new Comparison(before, after, queries.totals(before, service),
                queries.totals(after, service), endpointDiffs, queryDiffs, errorDiffs);
    }

    // --- verdicts ------------------------------------------------------------

    /**
     * The order marks-and-compare.adoc#verdicts gives, and it is an order rather than a set
     * of independent tests: every reason for {@code worse} is asked before any reason for
     * {@code better}, so an endpoint that stopped failing but grew slower past the bound is
     * {@code worse}. A change that swallows a failure behind a slow retry is what that catches.
     */
    static String verdict(@Nullable Side before, @Nullable Side after) {
        if (before == null) {
            return NEW;
        }
        if (after == null) {
            return GONE;
        }
        if (after.errors() > before.errors()) {
            return WORSE;
        }
        if (grew(before.p95Ms(), after.p95Ms(), ABSOLUTE_MS)) {
            return WORSE;
        }
        if (before.errors() > 0 && after.errors() == 0) {
            return BETTER;
        }
        if (grew(after.p95Ms(), before.p95Ms(), ABSOLUTE_MS)) {
            return BETTER;
        }
        return SAME;
    }

    static String queryVerdict(@Nullable QuerySide before, @Nullable QuerySide after) {
        if (before == null) {
            return NEW;
        }
        if (after == null) {
            return GONE;
        }
        if (grew(before.callsPerRequest(), after.callsPerRequest(), ABSOLUTE_CALLS)) {
            return WORSE;
        }
        if (grew(after.callsPerRequest(), before.callsPerRequest(), ABSOLUTE_CALLS)) {
            return BETTER;
        }
        return SAME;
    }

    static String errorVerdict(long before, long after) {
        if (before == 0 && after > 0) {
            return NEW;
        }
        if (after == 0 && before > 0) {
            return GONE;
        }
        if (after > before) {
            return WORSE;
        }
        if (after < before) {
            return BETTER;
        }
        return SAME;
    }

    /** More than a fifth bigger <em>and</em> bigger by at least {@code absolute}. */
    private static boolean grew(double from, double to, double absolute) {
        return to > from * (1 + RELATIVE) && to - from >= absolute;
    }

    /**
     * Worst first: {@code worse}, {@code new}, {@code same}, {@code better}, {@code gone};
     * the heaviest first within a verdict, and the id between equal weights, so two
     * calls over the same windows list the rows in the same order (cli.adoc).
     */
    private static <T> Comparator<T> order(java.util.function.Function<T, String> verdict,
            java.util.function.ToDoubleFunction<T> weight, java.util.function.Function<T, String> id) {
        List<String> verdicts = List.of(WORSE, NEW, SAME, BETTER, GONE);
        return Comparator.<T>comparingInt(diff -> verdicts.indexOf(verdict.apply(diff)))
                .thenComparing(Comparator.comparingDouble(weight).reversed())
                .thenComparing(id);
    }

    // --- sides ---------------------------------------------------------------

    private static @Nullable Side side(Stats.@Nullable EndpointStats stats,
            Queries.@Nullable DbWork work) {
        if (stats == null) {
            return null;
        }
        Queries.DbWork database = work == null ? Queries.DbWork.NONE : work;
        return new Side(stats.calls(), stats.errors(), stats.p50Ms(), stats.p95Ms(), stats.maxMs(),
                database.callsPer(stats.calls()), database.msPer(stats.calls()));
    }

    /**
     * {@code callsPerRequest} divides by the entry spans of the window, not by the
     * calls of the query: "this statement now runs four times a request" is the
     * sentence an N+1 fix is judged by.
     */
    private static @Nullable QuerySide querySide(Stats.@Nullable QueryStats stats, long requests) {
        if (stats == null) {
            return null;
        }
        return new QuerySide(stats.calls(), requests == 0 ? 0 : (double) stats.calls() / requests,
                stats.p95Ms(), stats.totalMs());
    }

    private Map<String, Stats.EndpointStats> endpoints(Window window, @Nullable String service) {
        Map<String, Stats.EndpointStats> byId = new LinkedHashMap<>();
        for (Stats.EndpointStats endpoint : queries.endpointsWithoutStatusCodes(window, service)) {
            byId.put(endpoint.endpointId(), endpoint);
        }
        return byId;
    }

    private Map<String, Stats.QueryStats> queries(Window window, @Nullable String service) {
        Map<String, Stats.QueryStats> byId = new LinkedHashMap<>();
        // The aggregate alone: a verdict reads neither the callers nor the schema block.
        for (Stats.QueryStats query : queries.queryGroups(window, service, "total", Queries.ALL_GROUPS, null)) {
            byId.put(query.queryId(), query);
        }
        return byId;
    }

    private Map<String, Stats.ErrorGroup> errors(Window window, @Nullable String service) {
        Map<String, Stats.ErrorGroup> byId = new LinkedHashMap<>();
        for (Stats.ErrorGroup group : queries.errorGroups(window, service, Queries.ALL_GROUPS, null)) {
            byId.put(group.errorId(), group);
        }
        return byId;
    }

    private static Set<String> union(Set<String> before, Set<String> after) {
        Set<String> ids = new LinkedHashSet<>(before);
        ids.addAll(after);
        return ids;
    }
}
