package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Store;

/** Thresholds as a verdict: the default rule set, one failing rule, and no data at all. */
class CheckTest {

    private static final long NOW = 1_700_000_000_000L;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
    private final Queries queries = new Queries(store.sql(), store.tingles(), store.services());
    private final MetricQueries metrics = new MetricQueries(store.sql());
    private final Findings findings = new Findings(store.sql(), queries, metrics, store.services(),
            store.tingles(), new CodeFrames(""));
    private final Check check = new Check(queries, findings, store.tingles());
    private final Window window = Window.of(NOW - 60_000, NOW + 60_000);

    private int ids = 1;

    @AfterEach
    void close() {
        store.close();
    }

    private void flush() {
        store.writer().awaitIdle(5_000);
    }

    private Span.Builder entry(String route, long durationMs) {
        int n = ids++;
        return Otlp.span("%032x".formatted(n), "%016x".formatted(n), "GET " + route,
                Span.SpanKind.SPAN_KIND_SERVER, NOW, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", route),
                Otlp.attr("http.response.status_code", 200));
    }

    private static Check.RuleCheck rule(Check.CheckResult result, String name) {
        for (Check.RuleCheck each : result.checks()) {
            if (each.rule().equals(name)) {
                return each;
            }
        }
        return null;
    }

    @Test
    void withNoRuleGivenTheDefaultsAreTheOnesAgentMdNames() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry("/orders", 10)));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of());

        assertThat(result.pass()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.reason()).isNull();
        assertThat(result.checks()).extracting(Check.RuleCheck::rule)
                .containsExactly(Check.MAX_P95_MS, Check.MAX_ERRORS, Check.MAX_N_PLUS_ONE,
                        Check.MAX_REGRESSIONS);
        assertThat(rule(result, Check.MAX_P95_MS).limit()).isEqualTo(500.0);
        assertThat(rule(result, Check.MAX_P95_MS).actual()).isEqualTo(10.0);
    }

    @Test
    void aRuleThatIsExceededFailsAndSaysWhich() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry("/orders", 10), entry("/orders/report", 800)));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of());

        assertThat(result.pass()).isFalse();
        Check.RuleCheck p95 = rule(result, Check.MAX_P95_MS);
        assertThat(p95.pass()).isFalse();
        assertThat(p95.actual()).isEqualTo(800.0);
        assertThat(p95.detail()).isEqualTo("GET /orders/report p95 800.0 ms over 1 call");
        assertThat(rule(result, Check.MAX_ERRORS).pass()).isTrue();
    }

    @Test
    void everyRuleGivenIsEvaluatedAndNoOther() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.failing(entry("/ship", 10), "java.lang.IllegalStateException", "already shipped",
                        "at orders.Ship.run(Ship.java:1)")));
        flush();

        Check.CheckResult result = check.check(window, null, null,
                Map.of(Check.MAX_ERRORS, 0.0, Check.MAX_ERROR_RATE, 0.5, Check.MIN_APDEX, 0.9));

        assertThat(result.checks()).extracting(Check.RuleCheck::rule)
                .containsExactly(Check.MAX_ERRORS, Check.MAX_ERROR_RATE, Check.MIN_APDEX);
        assertThat(rule(result, Check.MAX_ERRORS).actual()).isEqualTo(1.0);
        assertThat(rule(result, Check.MAX_ERROR_RATE).actual()).isEqualTo(1.0);
        assertThat(rule(result, Check.MAX_ERROR_RATE).pass()).isFalse();
        assertThat(rule(result, Check.MIN_APDEX).actual()).isEqualTo(0.0);
        assertThat(rule(result, Check.MIN_APDEX).pass()).isFalse();
        assertThat(result.pass()).isFalse();
    }

    /** A statement run slow by one endpoint does not count against another that runs it fast. */
    @Test
    void anEndpointsSlowQueriesAreTheOnesItsOwnRequestsMade() {
        Span.Builder a = entry("/a", 400);
        Span.Builder b = entry("/b", 10);
        List<Span.Builder> spans = new ArrayList<>(List.of(a, b));
        for (int i = 0; i < 3; i++) {
            spans.add(query(a, 200, NOW + i));
        }
        spans.add(query(b, 2, NOW));
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        Check.RuleCheck fromB = rule(check.check(window, null, "GET /b", Map.of(Check.MAX_SLOW_QUERIES, 0.0)),
                Check.MAX_SLOW_QUERIES);
        Check.RuleCheck fromA = rule(check.check(window, null, "GET /a", Map.of(Check.MAX_SLOW_QUERIES, 0.0)),
                Check.MAX_SLOW_QUERIES);

        assertThat(fromB.actual()).isZero();
        assertThat(fromB.pass()).isTrue();
        assertThat(fromA.actual()).isEqualTo(3.0);
    }

    private Span.Builder query(Span.Builder parent, long durationMs, long at) {
        int n = ids++;
        return Otlp.child(parent, "%016x".formatted(n), "SELECT items", Span.SpanKind.SPAN_KIND_CLIENT, at, durationMs,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from items where id = ?"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "items"));
    }

    @Test
    void noRequestInScopeIsNoVerdictRatherThanAPass() {
        Check.CheckResult result = check.check(window, null, null, Map.of());

        assertThat(result.pass()).isNull();
        assertThat(result.requests()).isZero();
        assertThat(result.reason()).isEqualTo(Check.NO_REQUESTS);
    }

    @Test
    void anEndpointNarrowsTheScopeByNameOrById() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry("/orders", 10), entry("/orders/report", 800)));
        flush();

        Check.CheckResult fast = check.check(window, null, "GET /orders", Map.of());
        assertThat(fast.pass()).isTrue();
        assertThat(fast.requests()).isEqualTo(1);
        assertThat(rule(fast, Check.MAX_P95_MS).actual()).isEqualTo(10.0);

        String id = net.benelog.spidersense.store.Ids.endpointId("orders", "GET /orders/report");
        Check.CheckResult slow = check.check(window, null, id, Map.of());
        assertThat(slow.pass()).isFalse();
        assertThat(rule(slow, Check.MAX_P95_MS).actual()).isEqualTo(800.0);

        Check.CheckResult unknown = check.check(window, null, "GET /nothing", Map.of());
        assertThat(unknown.pass()).isNull();
        assertThat(unknown.reason()).isEqualTo(Check.NO_REQUESTS);
    }

    @Test
    void aSeedersRootInsertsAreSpansButNotRequests() {
        // A JPA seeder issues each INSERT in a trace of its own: a root CLIENT span with db.system.
        int n = ids++;
        Span.Builder seed = Otlp.span("%032x".formatted(n), "%016x".formatted(n), "INSERT orders",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW, 900,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "insert into orders values (?)"),
                Otlp.attr("db.operation", "INSERT"),
                Otlp.attr("db.sql.table", "orders"));
        decoder.accept(Otlp.traces(Otlp.service("orders"), seed, entry("/orders", 10)));
        flush();

        assertThat(queries.totals(window, null).requests()).isEqualTo(1);
        assertThat(queries.endpoints(window, null, null)).extracting(Stats.EndpointStats::name)
                .containsExactly("GET /orders");

        // The seeder took 900 ms, but only the request is judged, so the p95 rule passes.
        Check.CheckResult result = check.check(window, null, null, Map.of());
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.pass()).isTrue();
        assertThat(rule(result, Check.MAX_P95_MS).actual()).isEqualTo(10.0);

        // The span is still there, and still its own trace.
        assertThat(queries.traces(
                new Queries.TraceFilter(window, null, null, null, null, null, null, null, 10)))
                .hasSize(2);
    }

    @Test
    void aSlowJobIsNotARequestAndIsInNoVerdict() {
        // A scheduler tick is a root INTERNAL span: a job (design.adoc#endpoint-identity), never a request.
        int n = ids++;
        Span.Builder tick = Otlp.span("%032x".formatted(n), "%016x".formatted(n), "ReportJob.run",
                Span.SpanKind.SPAN_KIND_INTERNAL, NOW, 900);
        decoder.accept(Otlp.traces(Otlp.service("orders"), tick, entry("/orders", 10)));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of());

        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.pass()).isTrue();
        assertThat(rule(result, Check.MAX_P95_MS).actual()).isEqualTo(10.0);
        assertThat(queries.endpoints(window, null, null)).extracting(Stats.EndpointStats::name)
                .containsExactly("GET /orders");
    }

    @Test
    void uncoveredErrorLogsAreCountedByTheLogErrorRuleAndAreNotADefault() {
        assertThat(check.defaults()).doesNotContainKey(Check.MAX_LOG_ERRORS);

        decoder.accept(Otlp.traces(Otlp.service("orders"), entry("/orders", 10)));
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "Payment gateway timeout for order 42", null, null),
                Otlp.log(NOW + 1, 17, "Payment gateway timeout for order 43", null, null)));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of(Check.MAX_LOG_ERRORS, 0.0));

        assertThat(result.checks()).extracting(Check.RuleCheck::rule)
                .containsExactly(Check.MAX_LOG_ERRORS);
        assertThat(rule(result, Check.MAX_LOG_ERRORS).actual()).isEqualTo(2.0);
        assertThat(rule(result, Check.MAX_LOG_ERRORS).pass()).isFalse();
        assertThat(rule(result, Check.MAX_LOG_ERRORS).detail())
                .isEqualTo("2 records: ERROR in OrderController: Payment gateway timeout for order ?");
        assertThat(result.pass()).isFalse();
    }

    @Test
    void theLogErrorRulePassesWhenNothingLoggedAnErrorOutsideAFailedTrace() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry("/orders", 10)));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of(Check.MAX_LOG_ERRORS, 0.0));

        assertThat(rule(result, Check.MAX_LOG_ERRORS).actual()).isZero();
        assertThat(rule(result, Check.MAX_LOG_ERRORS).pass()).isTrue();
        assertThat(rule(result, Check.MAX_LOG_ERRORS).detail())
                .isEqualTo("no ERROR log outside a failed trace");
    }

    @Test
    void repeatedStatementsAreCountedByTheNPlusOneRule() {
        Span.Builder root = entry("/orders/{id}", 60);
        List<Span.Builder> spans = new java.util.ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            int n = ids++;
            spans.add(Otlp.child(root, "%016x".formatted(n), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of(Check.MAX_N_PLUS_ONE, 0.0,
                Check.MAX_QUERIES_PER_REQUEST, 2.0));

        assertThat(rule(result, Check.MAX_N_PLUS_ONE).actual()).isEqualTo(1.0);
        assertThat(rule(result, Check.MAX_N_PLUS_ONE).pass()).isFalse();
        assertThat(rule(result, Check.MAX_N_PLUS_ONE).detail()).contains("6 times per request");
        assertThat(rule(result, Check.MAX_QUERIES_PER_REQUEST).actual()).isEqualTo(6.0);
        assertThat(rule(result, Check.MAX_QUERIES_PER_REQUEST).pass()).isFalse();
    }

    @Test
    void theRulesCountPastAHundredErrorGroupsAndFindings() {
        // A hundred and one error groups: more errors than a top hundred sums, and
        // more error findings, which rank before an N+1, than a top hundred holds.
        for (int i = 0; i < 101; i++) {
            String type = "orders.Failure" + (char) ('A' + i % 26) + (char) ('A' + i / 26);
            decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.failing(entry("/ship", 10), type,
                    "failed", "at orders.Ship.run(Ship.java:1)")));
        }
        Span.Builder root = entry("/orders/{id}", 60);
        List<Span.Builder> spans = new java.util.ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            int n = ids++;
            spans.add(Otlp.child(root, "%016x".formatted(n), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        Check.CheckResult result = check.check(window, null, null, Map.of(Check.MAX_ERRORS, 0.0,
                Check.MAX_N_PLUS_ONE, 0.0));

        assertThat(rule(result, Check.MAX_ERRORS).actual()).isEqualTo(101.0);
        assertThat(rule(result, Check.MAX_N_PLUS_ONE).actual()).isEqualTo(1.0);
        assertThat(rule(result, Check.MAX_N_PLUS_ONE).pass()).isFalse();
    }

    @Test
    void aResolvedNPlusOneThatCameBackFailsMaxRegressionsAndStillCountsAsAnNPlusOne() {
        Span.Builder root = entry("/orders/{id}", 60);
        List<Span.Builder> spans = new java.util.ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            int n = ids++;
            spans.add(Otlp.child(root, "%016x".formatted(n), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();
        assertThat(rule(check.check(window, null, null, Map.of()), Check.MAX_REGRESSIONS).detail())
                .isEqualTo("no resolved finding came back");

        String id = findings.findings(window, null, 20).get(0).id();
        store.sql().update("MERGE INTO ack (finding_id, at_ms, note, resolved) KEY(finding_id)"
                + " VALUES (?, ?, ?, TRUE)", List.of(id, NOW - 30_000, "fetch join"));

        Check.CheckResult result = check.check(window, null, null, Map.of());

        assertThat(result.pass()).isFalse();
        assertThat(rule(result, Check.MAX_REGRESSIONS).limit()).isZero();
        assertThat(rule(result, Check.MAX_REGRESSIONS).actual()).isEqualTo(1.0);
        assertThat(rule(result, Check.MAX_REGRESSIONS).pass()).isFalse();
        assertThat(rule(result, Check.MAX_REGRESSIONS).detail())
                .startsWith("1 finding: n-plus-one GET /orders/{id} runs SELECT order_line");
        assertThat(rule(result, Check.MAX_N_PLUS_ONE).actual())
                .as("a regressed N+1 is still an N+1").isEqualTo(1.0);

        Check.CheckResult allowed = check.check(window, null, null, Map.of(Check.MAX_REGRESSIONS, 1.0));
        assertThat(allowed.checks()).extracting(Check.RuleCheck::rule)
                .containsExactly(Check.MAX_REGRESSIONS);
        assertThat(allowed.pass()).isTrue();
    }
}
