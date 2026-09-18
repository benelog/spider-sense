package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

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
                .containsExactly(Check.MAX_P95_MS, Check.MAX_ERRORS, Check.MAX_N_PLUS_ONE);
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
}
