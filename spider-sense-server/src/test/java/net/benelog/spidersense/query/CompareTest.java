package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Store;

/** Before and after: the verdicts marks-and-compare.adoc#verdicts defines, over two windows of the same data. */
class CompareTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long BEFORE = NOW - 120_000;
    private static final long AFTER = NOW - 30_000;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
    private final Queries queries = new Queries(store.sql(), store.tingles(), store.services());
    private final Compare compare = new Compare(queries);
    private final Window before = Window.of(BEFORE, AFTER - 1);
    private final Window after = Window.of(AFTER, NOW);

    private int ids = 1;

    @AfterEach
    void close() {
        store.close();
    }

    private void flush() {
        store.writer().awaitIdle(5_000);
    }

    private Span.Builder entry(String route, long at, long durationMs) {
        int n = ids++;
        return Otlp.span("%032x".formatted(n), "%016x".formatted(n), "GET " + route,
                Span.SpanKind.SPAN_KIND_SERVER, at, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", route),
                Otlp.attr("http.response.status_code", 200));
    }

    private void send(Span.Builder... spans) {
        decoder.ingest(Otlp.traces(Otlp.service("orders"), spans));
    }

    private Map<String, String> endpointVerdicts() {
        Map<String, String> verdicts = new HashMap<>();
        for (Compare.EndpointDiff diff : compare.compare(before, after, null).endpoints()) {
            verdicts.put(diff.name(), diff.verdict());
        }
        return verdicts;
    }

    @Test
    void anEndpointIsWorseBetterSameNewOrGone() {
        send(entry("/slower", BEFORE + 1000, 100), entry("/faster", BEFORE + 1000, 500),
                entry("/steady", BEFORE + 1000, 100), entry("/gone", BEFORE + 1000, 100));
        send(entry("/slower", AFTER + 1000, 500), entry("/faster", AFTER + 1000, 100),
                entry("/steady", AFTER + 1000, 105), entry("/new", AFTER + 1000, 100));
        flush();

        Map<String, String> verdicts = endpointVerdicts();

        assertThat(verdicts.get("GET /slower")).isEqualTo(Compare.WORSE);
        assertThat(verdicts.get("GET /faster")).isEqualTo(Compare.BETTER);
        assertThat(verdicts.get("GET /steady")).isEqualTo(Compare.SAME);
        assertThat(verdicts.get("GET /new")).isEqualTo(Compare.NEW);
        assertThat(verdicts.get("GET /gone")).isEqualTo(Compare.GONE);
    }

    /**
     * Every reason for worse is asked before any reason for better: an endpoint that stopped
     * failing behind a slow retry is worse, and one that stopped failing at the same speed is better.
     */
    @Test
    void anEndpointThatStoppedFailingButGrewSlowerIsWorse() {
        Compare.Side failing = new Compare.Side(3, 1, 100, 100, 100, 0, 0);

        assertThat(Compare.verdict(failing, new Compare.Side(3, 0, 900, 900, 900, 0, 0)))
                .isEqualTo(Compare.WORSE);
        assertThat(Compare.verdict(failing, new Compare.Side(3, 0, 100, 105, 105, 0, 0)))
                .isEqualTo(Compare.BETTER);
    }

    @Test
    void theWorstIsFirstAndASideThatIsMissingIsNull() {
        send(entry("/slower", BEFORE + 1000, 100), entry("/gone", BEFORE + 1000, 100));
        send(entry("/slower", AFTER + 1000, 500), entry("/new", AFTER + 1000, 100));
        flush();

        Compare.Comparison comparison = compare.compare(before, after, null);

        assertThat(comparison.endpoints().get(0).verdict()).isEqualTo(Compare.WORSE);
        assertThat(comparison.endpoints().get(1).verdict()).isEqualTo(Compare.NEW);
        assertThat(comparison.endpoints().get(1).before()).isNull();
        assertThat(comparison.endpoints().get(2).verdict()).isEqualTo(Compare.GONE);
        assertThat(comparison.endpoints().get(2).after()).isNull();
        assertThat(comparison.beforeTotals().requests()).isEqualTo(2);
        assertThat(comparison.afterTotals().requests()).isEqualTo(2);
    }

    @Test
    void anErrorThatAppearsIsNewAndOneThatGoesIsGone() {
        send(Otlp.failing(entry("/ship", BEFORE + 1000, 10),
                "java.lang.IllegalStateException", "already shipped", "at orders.Ship.run(Ship.java:1)"));
        send(entry("/ship", AFTER + 1000, 10),
                Otlp.failing(entry("/pay", AFTER + 1000, 10),
                        "java.lang.ArithmeticException", "/ by zero", "at orders.Pay.run(Pay.java:1)"));
        flush();

        Map<String, String> verdicts = new HashMap<>();
        for (Compare.ErrorDiff diff : compare.compare(before, after, null).errors()) {
            verdicts.put(diff.type(), diff.verdict());
        }

        assertThat(verdicts.get("java.lang.ArithmeticException")).isEqualTo(Compare.NEW);
        assertThat(verdicts.get("java.lang.IllegalStateException")).isEqualTo(Compare.GONE);
        // The endpoint that stopped failing is better, whatever its percentile did.
        assertThat(endpointVerdicts().get("GET /ship")).isEqualTo(Compare.BETTER);
    }

    @Test
    void rowsOfEqualVerdictAndWeightAreOrderedById() {
        for (String type : new String[]{"orders.Zeta", "orders.Alpha", "orders.Mid"}) {
            send(Otlp.failing(entry("/ship", BEFORE + 1000, 10), type, "failed",
                    "at orders.Ship.run(Ship.java:1)"));
            send(Otlp.failing(entry("/ship", AFTER + 1000, 10), type, "failed",
                    "at orders.Ship.run(Ship.java:1)"));
        }
        flush();

        List<Compare.ErrorDiff> errors = compare.compare(before, after, null).errors();

        assertThat(errors).hasSize(3).extracting(Compare.ErrorDiff::verdict).containsOnly(Compare.SAME);
        assertThat(errors).extracting(Compare.ErrorDiff::errorId)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void aQueryIsJudgedByHowOftenItRunsPerRequest() {
        Span.Builder oneRequest = entry("/orders/{id}", BEFORE + 1000, 50);
        send(oneRequest, query(oneRequest, BEFORE + 1001));
        Span.Builder repeated = entry("/orders/{id}", AFTER + 1000, 50);
        Span.Builder[] spans = new Span.Builder[7];
        spans[0] = repeated;
        for (int i = 1; i < spans.length; i++) {
            spans[i] = query(repeated, AFTER + 1000 + i);
        }
        send(spans);
        flush();

        Compare.Comparison comparison = compare.compare(before, after, null);

        assertThat(comparison.queries()).hasSize(1);
        Compare.QueryDiff diff = comparison.queries().get(0);
        assertThat(diff.before().callsPerRequest()).isEqualTo(1.0);
        assertThat(diff.after().callsPerRequest()).isEqualTo(6.0);
        assertThat(diff.verdict()).isEqualTo(Compare.WORSE);
    }

    @Test
    void aGroupBelowTheTopHundredOfOneSideIsStillJudgedOnBoth() {
        // A hundred heavier statements push the light one to the 101st place before.
        Span.Builder heavy = entry("/orders", BEFORE + 1000, 50);
        Span.Builder[] spans = new Span.Builder[102];
        spans[0] = heavy;
        for (int i = 1; i <= 100; i++) {
            spans[i] = query(heavy, BEFORE + 1000 + i, 5, "select * from t" + (char) ('a' + i % 26)
                    + (char) ('a' + i / 26));
        }
        spans[101] = query(heavy, BEFORE + 1200, 1, "select * from light");
        send(spans);
        Span.Builder light = entry("/orders", AFTER + 1000, 50);
        send(light, query(light, AFTER + 1001, 1, "select * from light"));
        flush();

        Compare.Comparison comparison = compare.compare(before, after, null);

        assertThat(comparison.queries()).hasSize(101);
        Compare.QueryDiff diff = comparison.queries().stream()
                .filter(each -> "select * from light".equals(each.statement())).findFirst().orElseThrow();
        assertThat(diff.before()).isNotNull();
        assertThat(diff.verdict()).isEqualTo(Compare.SAME);
    }

    @Test
    void anErrorGroupBelowTheTopHundredOfOneSideIsStillJudgedOnBoth() {
        for (int i = 0; i < 100; i++) {
            String type = "orders.Failure" + (char) ('A' + i % 26) + (char) ('A' + i / 26);
            send(Otlp.failing(entry("/ship", BEFORE + 1000 + i, 10), type, "failed",
                    "at orders.Ship.run(Ship.java:1)"),
                    Otlp.failing(entry("/ship", BEFORE + 1000 + i, 10), type, "failed",
                            "at orders.Ship.run(Ship.java:1)"));
        }
        send(Otlp.failing(entry("/pay", BEFORE + 2000, 10), "java.lang.ArithmeticException", "/ by zero",
                "at orders.Pay.run(Pay.java:1)"));
        send(Otlp.failing(entry("/pay", AFTER + 1000, 10), "java.lang.ArithmeticException", "/ by zero",
                "at orders.Pay.run(Pay.java:1)"));
        flush();

        Compare.ErrorDiff diff = compare.compare(before, after, null).errors().stream()
                .filter(each -> "java.lang.ArithmeticException".equals(each.type()))
                .findFirst().orElseThrow();

        assertThat(diff.before()).isEqualTo(1);
        assertThat(diff.after()).isEqualTo(1);
        assertThat(diff.verdict()).isEqualTo(Compare.SAME);
    }

    private Span.Builder query(Span.Builder parent, long at) {
        return query(parent, at, 2, "select * from order_line where order_id = ?");
    }

    private Span.Builder query(Span.Builder parent, long at, long durationMs, String statement) {
        int n = ids++;
        return Otlp.child(parent, "%016x".formatted(n), "SELECT order_line",
                Span.SpanKind.SPAN_KIND_CLIENT, at, durationMs,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", statement),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "order_line"));
    }

    // --- the diff of two snapshots, with no store -------------------------------

    private static Stats.EndpointStats endpointStats(String id, long calls, long errors, double p95Ms,
            double totalMs) {
        return new Stats.EndpointStats(id, "orders", "GET", "/" + id, "GET /" + id, "SERVER", calls,
                errors, 0, 0, 0, p95Ms, p95Ms, p95Ms, p95Ms, totalMs, new long[5], null, Map.of());
    }

    private static Stats.QueryStats queryStats(String id, long calls, double totalMs) {
        return new Stats.QueryStats(id, "orders", "h2", null, "SELECT", "t", "select " + id, calls, 0,
                0, 1, 1, 1, totalMs, 0, List.of(), 0, null);
    }

    private static Stats.ErrorGroup errorGroup(String id, long count) {
        return new Stats.ErrorGroup(id, "orders", "java.lang.IllegalStateException", "boom " + id,
                count, 0, 0, List.of(), null);
    }

    private static Compare.Snapshot snapshot(Window window, long requests,
            Map<String, Stats.EndpointStats> endpoints, Map<String, Queries.DbWork> work,
            Map<String, Stats.QueryStats> queries, Map<String, Stats.ErrorGroup> errors) {
        Stats.Totals totals = new Stats.Totals(requests, 0, 0, 0, 0, 0, 0, 0, new long[5], null);
        return new Compare.Snapshot(window, totals, endpoints, work, queries, errors);
    }

    @Test
    void theRowsAreOrderedByVerdictThenWeightThenId() {
        Compare.Snapshot one = snapshot(before, 10,
                Map.of("same-light", endpointStats("same-light", 10, 0, 100, 10),
                        "same-heavy", endpointStats("same-heavy", 10, 0, 100, 900),
                        "better", endpointStats("better", 10, 0, 500, 50),
                        "gone", endpointStats("gone", 10, 0, 100, 5_000),
                        "worse", endpointStats("worse", 10, 0, 100, 1)),
                Map.of(), Map.of(), Map.of());
        Compare.Snapshot two = snapshot(after, 10,
                Map.of("same-light", endpointStats("same-light", 10, 0, 100, 10),
                        "same-heavy", endpointStats("same-heavy", 10, 0, 100, 900),
                        "better", endpointStats("better", 10, 0, 100, 50),
                        "worse", endpointStats("worse", 10, 0, 500, 1),
                        "b-new", endpointStats("b-new", 10, 0, 100, 3),
                        "a-new", endpointStats("a-new", 10, 0, 100, 3)),
                Map.of(), Map.of(), Map.of());

        Compare.Comparison comparison = Compare.diff(one, two);

        assertThat(comparison.endpoints()).extracting(Compare.EndpointDiff::endpointId)
                .containsExactly("worse", "a-new", "b-new", "same-heavy", "same-light", "better", "gone");
        assertThat(comparison.before()).isEqualTo(before);
        assertThat(comparison.after()).isEqualTo(after);
    }

    @Test
    void aSideReadsItsDatabaseWorkAndAMissingSideIsNull() {
        Compare.Snapshot one = snapshot(before, 10, Map.of("e", endpointStats("e", 10, 0, 100, 10)),
                Map.of("e", new Queries.DbWork(40, 200, 0)), Map.of(), Map.of());
        Compare.Snapshot two = snapshot(after, 10, Map.of(), Map.of(), Map.of(), Map.of());

        Compare.EndpointDiff diff = Compare.diff(one, two).endpoints().get(0);

        assertThat(diff.verdict()).isEqualTo(Compare.GONE);
        assertThat(diff.after()).isNull();
        assertThat(diff.before()).isNotNull();
        assertThat(diff.before().dbCallsPerRequest()).isEqualTo(4.0);
        assertThat(diff.before().dbMsPerRequest()).isEqualTo(20.0);
    }

    @Test
    void aQuerysCallsPerRequestDividesByTheWindowsRequests() {
        Compare.Snapshot one = snapshot(before, 10, Map.of(), Map.of(),
                Map.of("q", queryStats("q", 50, 100)), Map.of());
        Compare.Snapshot two = snapshot(after, 20, Map.of(), Map.of(),
                Map.of("q", queryStats("q", 20, 30), "r", queryStats("r", 20, 40)), Map.of());

        List<Compare.QueryDiff> queries = Compare.diff(one, two).queries();

        assertThat(queries).extracting(Compare.QueryDiff::queryId).containsExactly("r", "q");
        Compare.QueryDiff fixed = queries.get(1);
        assertThat(fixed.before().callsPerRequest()).isEqualTo(5.0);
        assertThat(fixed.after().callsPerRequest()).isEqualTo(1.0);
        assertThat(fixed.verdict()).isEqualTo(Compare.BETTER);
    }

    @Test
    void anErrorGroupIsWeightedByItsLargerCountAndNamedByTheBeforeWindow() {
        Compare.Snapshot one = snapshot(before, 10, Map.of(), Map.of(), Map.of(),
                Map.of("e1", errorGroup("e1", 2), "e2", errorGroup("e2", 9)));
        Stats.ErrorGroup renamed = new Stats.ErrorGroup("e1", "orders", "java.lang.IllegalStateException",
                "renamed", 7, 0, 0, List.of(), null);
        Compare.Snapshot two = snapshot(after, 10, Map.of(), Map.of(), Map.of(),
                Map.of("e1", renamed, "e2", errorGroup("e2", 12)));

        List<Compare.ErrorDiff> errors = Compare.diff(one, two).errors();

        assertThat(errors).extracting(Compare.ErrorDiff::errorId).containsExactly("e2", "e1");
        assertThat(errors.get(1).message()).isEqualTo("boom e1");
        assertThat(errors.get(1).before()).isEqualTo(2);
        assertThat(errors.get(1).after()).isEqualTo(7);
    }
}
