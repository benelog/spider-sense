package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Store;

/** Before and after: the verdicts agent.md defines, over two windows of the same data. */
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
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans));
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

    private Span.Builder query(Span.Builder parent, long at) {
        int n = ids++;
        return Otlp.child(parent, "%016x".formatted(n), "SELECT order_line",
                Span.SpanKind.SPAN_KIND_CLIENT, at, 2,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "order_line"));
    }
}
