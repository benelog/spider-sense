package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

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

    /**
     * Everything a comparison reads of one window, read once: the totals, and the
     * endpoints, their database work, the query groups and the error groups by id.
     */
    record Snapshot(Window window, Stats.Totals totals, Map<String, Stats.EndpointStats> endpoints,
            Map<String, Queries.DbWork> work, Map<String, Stats.QueryStats> queries,
            Map<String, Stats.ErrorGroup> errors) {
    }

    private final Queries queries;

    public Compare(Queries queries) {
        this.queries = queries;
    }

    public Comparison compare(Window before, Window after, @Nullable String service) {
        return diff(snapshot(before, service), snapshot(after, service));
    }

    private Snapshot snapshot(Window window, @Nullable String service) {
        // The aggregates alone: a verdict reads neither the status codes, nor the callers
        // and the schema block of a query, nor where an error occurred.
        return new Snapshot(window, queries.totals(window, service),
                index(queries.endpointsWithoutStatusCodes(window, service), Stats.EndpointStats::endpointId),
                queries.databaseWork(window, service),
                index(queries.queryGroups(window, service, "total", Queries.ALL_GROUPS, null),
                        Stats.QueryStats::queryId),
                index(queries.errorGroups(window, service, Queries.ALL_GROUPS, null),
                        Stats.ErrorGroup::errorId));
    }

    /** The comparison of two snapshots: every group of either, with its verdict, worst first. */
    static Comparison diff(Snapshot before, Snapshot after) {
        long beforeRequests = before.totals().requests();
        long afterRequests = after.totals().requests();
        List<EndpointDiff> endpoints = join(before.endpoints(), after.endpoints(),
                (id, inBefore, inAfter, either) -> {
                    Side sideBefore = side(inBefore, before.work().get(id));
                    Side sideAfter = side(inAfter, after.work().get(id));
                    return new Weighted<>(new EndpointDiff(id, either.service(), either.name(),
                            sideBefore, sideAfter, verdict(sideBefore, sideAfter)),
                            totalMs(inBefore) + totalMs(inAfter));
                },
                EndpointDiff::verdict, EndpointDiff::endpointId);
        List<QueryDiff> queryDiffs = join(before.queries(), after.queries(),
                (id, inBefore, inAfter, either) -> {
                    QuerySide sideBefore = querySide(inBefore, beforeRequests);
                    QuerySide sideAfter = querySide(inAfter, afterRequests);
                    return new Weighted<>(new QueryDiff(id, either.service(), either.statement(),
                            sideBefore, sideAfter, queryVerdict(sideBefore, sideAfter)),
                            (inBefore == null ? 0 : inBefore.totalMs()) + (inAfter == null ? 0 : inAfter.totalMs()));
                },
                QueryDiff::verdict, QueryDiff::queryId);
        List<ErrorDiff> errors = join(before.errors(), after.errors(),
                (id, inBefore, inAfter, either) -> {
                    long countBefore = inBefore == null ? 0 : inBefore.count();
                    long countAfter = inAfter == null ? 0 : inAfter.count();
                    return new Weighted<>(new ErrorDiff(id, either.service(), either.type(),
                            either.message(), countBefore, countAfter,
                            errorVerdict(countBefore, countAfter)),
                            Math.max(countBefore, countAfter));
                },
                ErrorDiff::verdict, ErrorDiff::errorId);
        return new Comparison(before.window(), after.window(), before.totals(), after.totals(),
                endpoints, queryDiffs, errors);
    }

    private static double totalMs(Stats.@Nullable EndpointStats endpoint) {
        return endpoint == null ? 0 : endpoint.totalMs();
    }

    /** A row of a comparison with the weight it is ordered by within its verdict. */
    private record Weighted<D>(D diff, double weight) {
    }

    /** Builds the row of one id from the group of each window, either of which may be missing. */
    @FunctionalInterface
    private interface RowBuilder<T, D> {

        /** @param either the group of the window that has it, the before one when both do */
        Weighted<D> row(String id, @Nullable T before, @Nullable T after, T either);
    }

    /**
     * A full outer join by id of two windows' groups, in the order
     * marks-and-compare.adoc#verdicts gives: by verdict, then the heaviest first, then
     * the id.
     */
    private static <T, D> List<D> join(Map<String, T> before, Map<String, T> after,
            RowBuilder<T, D> builder, Function<D, String> verdict, Function<D, String> id) {
        Set<String> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        List<Weighted<D>> rows = new ArrayList<>(ids.size());
        for (String each : ids) {
            T inBefore = before.get(each);
            T inAfter = after.get(each);
            T either = Objects.requireNonNull(inBefore != null ? inBefore : inAfter,
                    "the id came from one of the two windows");
            rows.add(builder.row(each, inBefore, inAfter, either));
        }
        rows.sort(order(row -> verdict.apply(row.diff()), Weighted::weight, row -> id.apply(row.diff())));
        List<D> diffs = new ArrayList<>(rows.size());
        for (Weighted<D> row : rows) {
            diffs.add(row.diff());
        }
        return diffs;
    }

    /** The groups of a window by id, in the order they were read. */
    private static <T> Map<String, T> index(List<T> groups, Function<T, String> id) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T group : groups) {
            byId.put(id.apply(group), group);
        }
        return byId;
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
    private static <T> Comparator<T> order(Function<T, String> verdict, ToDoubleFunction<T> weight,
            Function<T, String> id) {
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
}
