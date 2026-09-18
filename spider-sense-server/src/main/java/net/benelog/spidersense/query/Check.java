package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.Tingles;

/**
 * Thresholds as a verdict, so an agent can use Spider Sense the way it uses a
 * test.
 *
 * <p>{@code check} answers pass or fail and the CLI turns that into an exit code
 * (agent.md). The third answer matters as much as the other two: when nothing was
 * requested in the window there is nothing to judge, and saying "pass" there would
 * be a green light for a run that never happened — so {@code pass} is null and the
 * reason says why.
 */
public final class Check {

    public static final String MAX_P95_MS = "maxP95Ms";
    public static final String MAX_ERRORS = "maxErrors";
    public static final String MAX_ERROR_RATE = "maxErrorRate";
    public static final String MAX_QUERIES_PER_REQUEST = "maxQueriesPerRequest";
    public static final String MAX_SLOW_QUERIES = "maxSlowQueries";
    public static final String MAX_N_PLUS_ONE = "maxNPlusOne";
    public static final String MAX_LOG_ERRORS = "maxLogErrors";
    public static final String MIN_APDEX = "minApdex";

    /** The rules in the order agent.md lists them, which is the order they are answered in. */
    public static final List<String> RULES = List.of(MAX_P95_MS, MAX_ERRORS, MAX_ERROR_RATE,
            MAX_QUERIES_PER_REQUEST, MAX_SLOW_QUERIES, MAX_N_PLUS_ONE, MAX_LOG_ERRORS, MIN_APDEX);

    public static final String NO_REQUESTS = "no requests in the window";

    private static final int GROUPS = 100;
    private static final int FINDINGS = 100;

    public record RuleCheck(String rule, double limit, Double actual, boolean pass, String detail) {
    }

    /** {@code pass} is null when there was no request to judge. */
    public record CheckResult(Boolean pass, long requests, String reason, List<RuleCheck> checks) {
    }

    private final Queries queries;
    private final Findings findings;
    private final Tingles tingles;

    public Check(Queries queries, Findings findings, Tingles tingles) {
        this.queries = queries;
        this.findings = findings;
        this.tingles = tingles;
    }

    /** The default rule set: no error, no N+1, and no endpoint slower than the threshold. */
    public Map<String, Double> defaults() {
        Map<String, Double> rules = new LinkedHashMap<>();
        rules.put(MAX_ERRORS, 0.0);
        rules.put(MAX_N_PLUS_ONE, 0.0);
        rules.put(MAX_P95_MS, (double) tingles.slowRequestMs());
        return rules;
    }

    /**
     * Every rule given, over the scope.
     *
     * @param endpoint an {@code endpointId} or an endpoint name, or null for every
     *        endpoint of the window
     */
    public CheckResult check(Window window, String service, String endpoint, Map<String, Double> rules) {
        Map<String, Double> asked = rules == null || rules.isEmpty() ? defaults() : rules;
        List<Stats.EndpointStats> endpoints = scope(window, service, endpoint);

        long requests = 0;
        long errors = 0;
        for (Stats.EndpointStats each : endpoints) {
            requests += each.calls();
            errors += each.errors();
        }
        Stats.Totals totals = queries.totals(window, service);
        if (endpoint == null) {
            // Entry spans with no endpoint id are requests too, and the totals have them.
            requests = totals.requests();
            errors = totals.errors();
        }

        List<RuleCheck> checks = new ArrayList<>();
        for (String rule : RULES) {
            Double limit = asked.get(rule);
            if (limit != null) {
                checks.add(evaluate(rule, limit, window, service, endpoint, endpoints, requests, errors,
                        totals));
            }
        }
        if (requests == 0) {
            return new CheckResult(null, 0, NO_REQUESTS, checks);
        }
        boolean pass = true;
        for (RuleCheck check : checks) {
            pass = pass && check.pass();
        }
        return new CheckResult(pass, requests, null, checks);
    }

    private RuleCheck evaluate(String rule, double limit, Window window, String service, String endpoint,
            List<Stats.EndpointStats> endpoints, long requests, long errors, Stats.Totals totals) {
        return switch (rule) {
            case MAX_P95_MS -> {
                Stats.EndpointStats worst = null;
                for (Stats.EndpointStats each : endpoints) {
                    if (worst == null || each.p95Ms() > worst.p95Ms()) {
                        worst = each;
                    }
                }
                double actual = worst == null ? 0 : worst.p95Ms();
                String detail = worst == null ? "no endpoint in the window"
                        : worst.name() + " p95 " + Numbers.millis(worst.p95Ms()) + " over "
                                + Numbers.plural(worst.calls(), "call");
                yield max(rule, limit, actual, detail);
            }
            case MAX_ERRORS -> {
                long count = errorCount(window, service, endpoint);
                yield max(rule, limit, count, count == 0 ? "no error in the window"
                        : Numbers.plural(count, "occurrence") + " over the window");
            }
            case MAX_ERROR_RATE -> {
                double actual = requests == 0 ? 0 : (double) errors / requests;
                yield max(rule, limit, actual, errors + " of "
                        + Numbers.plural(requests, "request") + " failed (" + Numbers.percent(actual) + ")");
            }
            case MAX_QUERIES_PER_REQUEST -> {
                Map<String, Queries.DbWork> work = queries.databaseWork(window, service);
                Stats.EndpointStats worst = null;
                double actual = 0;
                for (Stats.EndpointStats each : endpoints) {
                    Queries.DbWork database = work.getOrDefault(each.endpointId(), Queries.DbWork.NONE);
                    double perRequest = each.calls() == 0 ? 0 : (double) database.calls() / each.calls();
                    if (worst == null || perRequest > actual) {
                        worst = each;
                        actual = perRequest;
                    }
                }
                String detail = worst == null ? "no endpoint in the window"
                        : worst.name() + " runs " + Numbers.number(actual)
                                + " database calls per request";
                yield max(rule, limit, actual, detail);
            }
            case MAX_SLOW_QUERIES -> {
                long slow = 0;
                for (Stats.QueryStats query : queries.queries(window, service, "total", GROUPS, null)) {
                    if (endpoint == null || callsFrom(query, endpoints)) {
                        slow += query.slowCalls();
                    }
                }
                yield max(rule, limit, slow,
                        Numbers.plural(slow, "call") + " over " + tingles.slowQueryMs() + " ms");
            }
            case MAX_N_PLUS_ONE -> {
                List<Findings.Finding> found = new ArrayList<>();
                for (Findings.Finding finding : findings.findings(window, service, FINDINGS)) {
                    if (Findings.N_PLUS_ONE.equals(finding.kind())
                            && (endpoint == null || inScope(finding, endpoints))) {
                        found.add(finding);
                    }
                }
                String detail = found.isEmpty() ? "no repeated statement in the window"
                        : Numbers.plural(found.size(), "finding") + ": " + found.get(0).title();
                yield max(rule, limit, found.size(), detail);
            }
            case MAX_LOG_ERRORS -> {
                // The rule counts the records, not the groups: one logger saying the
                // same thing 200 times is 200 failures nothing else reports (agent.md).
                long records = 0;
                Findings.Finding worst = null;
                for (Findings.Finding finding : findings.findings(window, service, FINDINGS)) {
                    if (!Findings.LOG_ERROR.equals(finding.kind())) {
                        continue;
                    }
                    Object count = finding.numbers().get("count");
                    records += count instanceof Number number ? number.longValue() : 0;
                    if (worst == null) {
                        worst = finding;
                    }
                }
                String detail = worst == null ? "no ERROR log outside a failed trace"
                        : Numbers.plural(records, "record") + ": " + worst.title();
                yield max(rule, limit, records, detail);
            }
            default -> {
                Double apdex = apdex(endpoints, endpoint, totals);
                String detail = apdex == null ? "no request to score"
                        : "Apdex " + Numbers.score(apdex) + " over "
                                + Numbers.plural(requests, "request");
                boolean pass = apdex == null || apdex >= limit;
                yield new RuleCheck(rule, limit, apdex, pass, detail);
            }
        };
    }

    private static RuleCheck max(String rule, double limit, double actual, String detail) {
        return new RuleCheck(rule, limit, actual, actual <= limit, detail);
    }

    /** The endpoints the scope covers: one, or every endpoint of the window. */
    private List<Stats.EndpointStats> scope(Window window, String service, String endpoint) {
        List<Stats.EndpointStats> endpoints = queries.endpoints(window, service, null);
        if (endpoint == null) {
            return endpoints;
        }
        List<Stats.EndpointStats> matching = new ArrayList<>();
        for (Stats.EndpointStats each : endpoints) {
            if (endpoint.equals(each.endpointId()) || endpoint.equals(each.name())) {
                matching.add(each);
            }
        }
        return matching;
    }

    private long errorCount(Window window, String service, String endpoint) {
        long count = 0;
        for (Stats.ErrorGroup group : queries.errors(window, service, GROUPS, null)) {
            if (endpoint == null) {
                count += group.count();
                continue;
            }
            for (Stats.EndpointCount each : group.endpoints()) {
                if (matchesName(endpoint, each.name(), group.service())) {
                    count += each.count();
                }
            }
        }
        return count;
    }

    private static boolean matchesName(String endpoint, String name, String service) {
        return endpoint.equals(name)
                || endpoint.equals(net.benelog.spidersense.store.Ids.endpointId(service, name));
    }

    private static boolean callsFrom(Stats.QueryStats query, List<Stats.EndpointStats> endpoints) {
        for (Stats.Caller caller : query.callers()) {
            for (Stats.EndpointStats each : endpoints) {
                if (each.name().equals(caller.endpoint())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inScope(Findings.Finding finding, List<Stats.EndpointStats> endpoints) {
        for (Stats.EndpointStats each : endpoints) {
            if (each.endpointId().equals(finding.subject().endpointId())) {
                return true;
            }
        }
        return false;
    }

    /** One endpoint's Apdex, or the scope's; null when nothing was requested. */
    private static Double apdex(List<Stats.EndpointStats> endpoints, String endpoint,
            Stats.Totals totals) {
        if (endpoint == null) {
            return totals.apdex();
        }
        long[] histogram = new long[ResponseBuckets.SLOTS];
        long calls = 0;
        for (Stats.EndpointStats each : endpoints) {
            calls += each.calls();
            for (int i = 0; i < histogram.length; i++) {
                histogram[i] += each.histogram()[i];
            }
        }
        return ResponseBuckets.apdex(histogram, calls);
    }
}
