package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Store;

/** The five rules of agent.md, each over the data that makes it fire. */
class FindingsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String STACKTRACE = """
            java.lang.IllegalStateException: no such order 42
            \tat orders.OrderService.load(OrderService.java:41)
            \tat org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:1)
            \tat orders.OrderController.show(OrderController.java:23)""";
    private static final String QUERY_STACKTRACE = """
            \tat org.h2.jdbc.JdbcPreparedStatement.executeQuery(JdbcPreparedStatement.java:112)
            \tat orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)
            \tat orders.OrderService.lines(OrderService.java:54)""";

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
    private final Queries queries = new Queries(store.sql(), store.tingles(), store.services());
    private final MetricQueries metrics = new MetricQueries(store.sql());
    private final Findings findings = new Findings(store.sql(), queries, metrics, store.services(),
            store.tingles(), new CodeFrames(""));
    private final Window window = Window.of(NOW - 60_000, NOW + 60_000);

    @AfterEach
    void close() {
        store.close();
    }

    private void flush() {
        store.writer().awaitIdle(5_000);
    }

    private static String traceId(int n) {
        return "%032x".formatted(n);
    }

    private static String spanId(int n) {
        return "%016x".formatted(n);
    }

    private List<Findings.Finding> of(String kind) {
        List<Findings.Finding> matching = new ArrayList<>();
        for (Findings.Finding finding : findings.findings(window, null, 20)) {
            if (finding.kind().equals(kind)) {
                matching.add(finding);
            }
        }
        return matching;
    }

    private Span.Builder entry(int n, String route, long durationMs) {
        return Otlp.span(traceId(n), spanId(n), "GET " + route, Span.SpanKind.SPAN_KIND_SERVER,
                NOW, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", route),
                Otlp.attr("http.response.status_code", 200));
    }

    private static Span.Builder query(Span.Builder parent, int id, String statement, String table,
            long startMs, long durationMs) {
        return Otlp.child(parent, spanId(id), "SELECT " + table, Span.SpanKind.SPAN_KIND_CLIENT,
                startMs, durationMs,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", statement),
                Otlp.attr("db.name", "orders"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", table));
    }

    @Test
    void anErrorGroupIsAFindingWithItsApplicationFrames() {
        Span.Builder failing = Otlp.failing(entry(1, "/orders/{id}", 10),
                "java.lang.IllegalStateException", "no such order 42", STACKTRACE);
        decoder.accept(Otlp.traces(Otlp.service("orders"), failing));
        flush();

        List<Findings.Finding> errors = of(Findings.ERROR);

        assertThat(errors).hasSize(1);
        Findings.Finding finding = errors.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.title()).isEqualTo("IllegalStateException in GET /orders/{id}");
        assertThat(finding.service()).isEqualTo("orders");
        assertThat(finding.subject().errorId()).isNotBlank();
        assertThat(finding.numbers().get("count")).isEqualTo(1L);
        assertThat(finding.numbers().get("type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(finding.numbers().get("message")).isEqualTo("no such order ?");
        assertThat(finding.code()).containsExactly(
                "orders.OrderService.load(OrderService.java:41)",
                "orders.OrderController.show(OrderController.java:23)");
        assertThat(finding.traces()).containsExactly(traceId(1));
    }

    @Test
    void aStatementRepeatedUnderOneEntrySpanIsAnNPlusOne() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(query(root, 100 + i, "select * from order_line where order_id = ?", "order_line",
                    NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        List<Findings.Finding> repeated = of(Findings.N_PLUS_ONE);

        assertThat(repeated).hasSize(1);
        Findings.Finding finding = repeated.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("GET /orders/{id} runs SELECT order_line 6 times per request");
        assertThat(finding.numbers().get("requests")).isEqualTo(1L);
        assertThat(finding.numbers().get("affected")).isEqualTo(1L);
        assertThat(finding.numbers().get("medianRepeats")).isEqualTo(6L);
        assertThat(finding.numbers().get("maxRepeats")).isEqualTo(6L);
        assertThat(finding.statement()).isEqualTo("select * from order_line where order_id = ?");
        assertThat(finding.subject().endpointId()).isNotBlank();
        assertThat(finding.subject().queryId()).isNotBlank();
        assertThat(finding.traces()).containsExactly(traceId(1));
    }

    @Test
    void theNPlusOneTakesItsCodeFromTheRepeatThatCarriesTheStack() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            Span.Builder repeat = query(root, 100 + i,
                    "select * from order_line where order_id = ?", "order_line", NOW + i, 2);
            // The extension captures the stack on the fifth repeat and on no other.
            if (i == 4) {
                repeat.addAttributes(Otlp.attr("code.stacktrace", QUERY_STACKTRACE));
            }
            spans.add(repeat);
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        List<Findings.Finding> repeated = of(Findings.N_PLUS_ONE);

        assertThat(repeated).hasSize(1);
        assertThat(repeated.get(0).code()).containsExactly(
                "orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)",
                "orders.OrderService.lines(OrderService.java:54)");
    }

    @Test
    void anNPlusOneWithoutTheExtensionNamesNoLine() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(query(root, 100 + i, "select * from order_line where order_id = ?", "order_line",
                    NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        List<Findings.Finding> repeated = of(Findings.N_PLUS_ONE);

        assertThat(repeated).hasSize(1);
        assertThat(repeated.get(0).code()).isEmpty();
    }

    @Test
    void aQueryGroupOverTheThresholdIsASlowQuery() {
        Span.Builder root = entry(1, "/books", 400);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from book where title like ?", "book", NOW, 300)));
        flush();

        List<Findings.Finding> slow = of(Findings.SLOW_QUERY);

        assertThat(slow).hasSize(1);
        Findings.Finding finding = slow.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("SELECT book is slow");
        assertThat(finding.numbers().get("calls")).isEqualTo(1L);
        assertThat(finding.numbers().get("slowCalls")).isEqualTo(1L);
        assertThat((Double) finding.numbers().get("p95Ms")).isEqualTo(300.0);
        assertThat(finding.traces()).containsExactly(traceId(1));
    }

    @Test
    void anEndpointOverTheThresholdIsASlowEndpointWithItsDatabaseShare() {
        Span.Builder root = entry(1, "/orders/report", 1000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from orders", "orders", NOW, 400)));
        flush();

        List<Findings.Finding> slow = of(Findings.SLOW_ENDPOINT);

        assertThat(slow).hasSize(1);
        Findings.Finding finding = slow.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("GET /orders/report is slow");
        assertThat(finding.numbers().get("calls")).isEqualTo(1L);
        assertThat((Double) finding.numbers().get("p95Ms")).isEqualTo(1000.0);
        assertThat((Double) finding.numbers().get("dbCallsPerRequest")).isEqualTo(1.0);
        assertThat((Double) finding.numbers().get("dbMsPerRequest")).isEqualTo(400.0);
        assertThat((Double) finding.numbers().get("dbShare")).isEqualTo(0.4);
    }

    @Test
    void aPoolWithSomebodyWaitingIsExhausted() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        pool("db.client.connections.usage", 10, Otlp.attr("state", "used"));
        pool("db.client.connections.usage", 0, Otlp.attr("state", "idle"));
        pool("db.client.connections.max", 10);
        pool("db.client.connections.pending_requests", 2);
        flush();

        List<Findings.Finding> exhausted = of(Findings.POOL_EXHAUSTED);

        assertThat(exhausted).hasSize(1);
        Findings.Finding finding = exhausted.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.title()).isEqualTo("HikariPool-1 ran out of connections");
        assertThat(finding.subject().pool()).isEqualTo("HikariPool-1");
        assertThat(finding.numbers().get("pool")).isEqualTo("HikariPool-1");
        assertThat((Double) finding.numbers().get("usedMax")).isEqualTo(10.0);
        assertThat((Double) finding.numbers().get("pendingMax")).isEqualTo(2.0);
        assertThat(finding.numbers().get("at")).isEqualTo(NOW);
        assertThat(finding.traces()).isEmpty();
    }

    private void pool(String metric, double value, io.opentelemetry.proto.common.v1.KeyValue... extra) {
        io.opentelemetry.proto.common.v1.KeyValue[] attributes =
                new io.opentelemetry.proto.common.v1.KeyValue[extra.length + 1];
        attributes[0] = Otlp.attr("pool.name", "HikariPool-1");
        System.arraycopy(extra, 0, attributes, 1, extra.length);
        decoder.accept(Otlp.sum(Otlp.service("orders"), metric, "{connection}", NOW, value, false,
                attributes));
    }

    @Test
    void highSeverityComesFirstAndAnIdIsTheSameInAnotherWindow() {
        Span.Builder slow = entry(1, "/orders/report", 2500);
        Span.Builder failing = Otlp.failing(
                Otlp.span(traceId(2), spanId(2), "POST /orders", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 20,
                        Otlp.attr("http.request.method", "POST"),
                        Otlp.attr("http.route", "/orders")),
                "java.lang.IllegalStateException", "already shipped", STACKTRACE);
        Span.Builder mild = entry(3, "/orders/list", 700);
        decoder.accept(Otlp.traces(Otlp.service("orders"), slow, failing, mild));
        flush();

        List<Findings.Finding> found = findings.findings(window, null, 20);

        assertThat(found).hasSizeGreaterThanOrEqualTo(3);
        assertThat(found.get(0).kind()).isEqualTo(Findings.ERROR);
        assertThat(found.get(0).severity()).isEqualTo(Findings.HIGH);
        assertThat(found.get(1).severity()).isEqualTo(Findings.HIGH);
        assertThat(found.get(1).title()).isEqualTo("GET /orders/report is slow");
        assertThat(found.get(2).severity()).isEqualTo(Findings.MEDIUM);

        List<Findings.Finding> wider = findings.findings(Window.of(NOW - 600_000, NOW + 600_000),
                null, 20);
        assertThat(wider.get(0).id()).isEqualTo(found.get(0).id());
        assertThat(wider.get(1).id()).isEqualTo(found.get(1).id());
        assertThat(found.get(0).id()).startsWith("error:");
        assertThat(found.get(0).id()).hasSize("error:".length() + 12);
    }

    @Test
    void theLimitIsHonoured() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry(1, "/a", 900), entry(2, "/b", 900), entry(3, "/c", 900)));
        flush();

        assertThat(findings.findings(window, null, 2)).hasSize(2);
    }
}
