package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

/** The rules of findings.adoc#rules, each over the data that makes it fire and data that does not. */
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

    /** An outbound HTTP call, as the agent's http-client instrumentation records one. */
    private static Span.Builder outbound(Span.Builder parent, int id, String host, long port,
            long startMs, long durationMs) {
        return Otlp.child(parent, spanId(id), "GET", Span.SpanKind.SPAN_KIND_CLIENT,
                startMs, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("url.full", "http://" + host + ":" + port + "/api/books/1"),
                Otlp.attr("server.address", host),
                Otlp.attr("server.port", port),
                Otlp.attr("http.response.status_code", 200));
    }

    /** An outbound HTTP call to one path, as a loop over items makes one per item. */
    private static Span.Builder call(Span.Builder parent, int id, String path, long startMs,
            long durationMs) {
        return Otlp.child(parent, spanId(id), "GET", Span.SpanKind.SPAN_KIND_CLIENT,
                startMs, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("url.full", "http://localhost:8081" + path),
                Otlp.attr("server.address", "localhost"),
                Otlp.attr("server.port", 8081L),
                Otlp.attr("http.response.status_code", 200));
    }

    /** A job: a root {@code INTERNAL} span, named the way a scheduler names one. */
    private Span.Builder job(int n, String name, long durationMs) {
        return Otlp.span(traceId(n), spanId(n), name, Span.SpanKind.SPAN_KIND_INTERNAL,
                NOW, durationMs,
                Otlp.attr("code.namespace", "orders." + name.substring(0, name.indexOf('.'))),
                Otlp.attr("code.function", name.substring(name.indexOf('.') + 1)));
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
    void anErrorAsFrequentAtTwoEndpointsIsNamedAfterTheFirstByName() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.failing(entry(1, "/orders/{id}/ship", 10), "java.lang.IllegalStateException",
                        "no such order 42", STACKTRACE),
                Otlp.failing(entry(2, "/orders/{id}", 10), "java.lang.IllegalStateException",
                        "no such order 43", STACKTRACE)));
        flush();

        Findings.Finding finding = of(Findings.ERROR).get(0);

        assertThat(finding.numbers().get("count")).isEqualTo(2L);
        assertThat(finding.title()).isEqualTo("IllegalStateException in GET /orders/{id}");
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

    /** Past the first few hundred affected requests, affected and requests still count the same ones. */
    @Test
    void everyAffectedRequestCountsNotOnlyTheFirstFewHundred() {
        for (int n = 1; n <= 600; n++) {
            Span.Builder root = entryAt(n, "/orders/{id}", NOW - n * 10L, 60);
            List<Span.Builder> spans = new ArrayList<>();
            spans.add(root);
            for (int i = 0; i < 5; i++) {
                spans.add(query(root, 10_000 + n * 10 + i, "select * from order_line where order_id = ?", "order_line",
                        NOW - n * 10L + i, 2));
            }
            decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        }
        flush();

        Findings.Finding finding = of(Findings.N_PLUS_ONE).get(0);

        assertThat(finding.numbers().get("requests")).isEqualTo(600L);
        assertThat(finding.numbers().get("affected")).isEqualTo(600L);
        assertThat(finding.why()).startsWith("600 of 600 requests repeated it");
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
    void theSameCallRepeatedUnderOneEntrySpanIsAnNPlusOneHttp() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            // Only the digits differ, which is what a loop over items varies.
            spans.add(call(root, 100 + i, "/api/books/" + (155 + i), NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        List<Findings.Finding> repeated = of(Findings.N_PLUS_ONE_HTTP);

        assertThat(repeated).hasSize(1);
        Findings.Finding finding = repeated.get(0);
        assertThat(finding.title())
                .isEqualTo("GET /orders/{id} calls GET localhost:8081/api/books/? 6 times per request");
        assertThat(finding.numbers().get("requests")).isEqualTo(1L);
        assertThat(finding.numbers().get("affected")).isEqualTo(1L);
        assertThat(finding.numbers().get("medianRepeats")).isEqualTo(6L);
        assertThat(finding.numbers().get("maxRepeats")).isEqualTo(6L);
        assertThat(finding.statement()).isNull();
        assertThat(finding.subject().endpointId()).isNotBlank();
        assertThat(finding.subject().target()).isEqualTo("localhost:8081");
        assertThat(finding.traces()).containsExactly(traceId(1));
    }

    @Test
    void fourRepeatsOfACallAreNotAnNPlusOneHttp() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 4; i++) {
            spans.add(call(root, 100 + i, "/api/books/" + i, NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        assertThat(of(Findings.N_PLUS_ONE_HTTP)).isEmpty();
    }

    @Test
    void twoCallsThatDifferInMoreThanTheirDigitsAreTwoCalls() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 3; i++) {
            spans.add(call(root, 100 + i, "/api/books/" + i, NOW + i, 2));
        }
        for (int i = 0; i < 3; i++) {
            spans.add(call(root, 200 + i, "/api/authors/" + i, NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        assertThat(of(Findings.N_PLUS_ONE_HTTP))
                .as("three of each is six calls and no repeat worth reporting")
                .isEmpty();
    }

    @Test
    void theNPlusOneHttpTakesItsCodeFromTheRepeatThatCarriesTheStack() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            Span.Builder repeat = call(root, 100 + i, "/api/books/" + i, NOW + i, 2);
            if (i == 4) {
                repeat.addAttributes(Otlp.attr("code.stacktrace", QUERY_STACKTRACE));
            }
            spans.add(repeat);
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        assertThat(of(Findings.N_PLUS_ONE_HTTP).get(0).code()).containsExactly(
                "orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)",
                "orders.OrderService.lines(OrderService.java:54)");
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
    void aSlowQueryTakesItsCodeFromTheSlowCallNotTheNewest() {
        Span.Builder root = entry(1, "/books", 400);
        String statement = "select * from book where title like ?";
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, statement, "book", NOW, 300)
                        .addAttributes(Otlp.attr("code.stacktrace", QUERY_STACKTRACE)),
                query(root, 101, statement, "book", NOW + 310, 5),
                query(root, 102, statement, "book", NOW + 320, 5),
                query(root, 103, statement, "book", NOW + 330, 5)));
        flush();

        List<Findings.Finding> slow = of(Findings.SLOW_QUERY);

        assertThat(slow).hasSize(1);
        assertThat(slow.get(0).code()).containsExactly(
                "orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)",
                "orders.OrderService.lines(OrderService.java:54)");
    }

    /** The catalog of one table, as the extension sends it: a log record (design.adoc#index-catalog). */
    private void catalog(String table, String indexes) {
        decoder.accept(Otlp.logs(Otlp.service("orders"), "spider-sense",
                Otlp.log(NOW, 9, "index catalog of " + table, null, null,
                        Otlp.attr("spidersense.schema.table", table),
                        Otlp.attr("spidersense.schema.schema", "PUBLIC"),
                        Otlp.attr("spidersense.schema.product", "H2"),
                        Otlp.attr("spidersense.schema.indexes", indexes))));
    }

    private static final String ITEMS_INDEXES = """
            [{"name":"PRIMARY_KEY_8","unique":true,"columns":["ID"]},\
            {"name":"IDX_ITEMS_SUPPLIER","unique":false,"columns":["SUPPLIER_ID","NAME"]}]""";

    @Test
    void aSlowQueryCarriesTheIndexesOfItsTablesAndTheColumnsNoneLeadsWith() {
        Span.Builder root = entry(1, "/items", 400);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from items where name = ? and supplier_id = ?", "items",
                        NOW, 300)));
        catalog("ITEMS", ITEMS_INDEXES);
        flush();

        SchemaBlock schema = of(Findings.SLOW_QUERY).get(0).schema();

        assertThat(schema).isNotNull();
        assertThat(schema.predicates()).containsExactly("items.name", "items.supplier_id");
        assertThat(schema.unindexed()).as("an index that carries a column second cannot seek on it")
                .containsExactly("items.name");
        assertThat(schema.tables()).singleElement().satisfies(table -> {
            assertThat(table.table()).isEqualTo("ITEMS");
            assertThat(table.schema()).isEqualTo("PUBLIC");
            assertThat(table.indexes()).extracting(SchemaBlock.Index::name)
                    .containsExactly("PRIMARY_KEY_8", "IDX_ITEMS_SUPPLIER");
            assertThat(table.indexes().get(0).unique()).isTrue();
            assertThat(table.indexes().get(1).columns()).containsExactly("SUPPLIER_ID", "NAME");
        });
    }

    @Test
    void aStatementWhoseTableTheCatalogDoesNotKnowHasNoBlockAtAll() {
        Span.Builder root = entry(1, "/items", 400);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from items where name = ? and supplier_id = ?", "items",
                        NOW, 300)));
        flush();

        assertThat(of(Findings.SLOW_QUERY).get(0).schema())
                .as("an application that ran without the extension says nothing about its schema")
                .isNull();
    }

    @Test
    void anNPlusOneCarriesTheBlockToo() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(query(root, 100 + i, "select * from order_line where order_id = ?",
                    "order_line", NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        catalog("ORDER_LINE",
                "[{\"name\":\"PRIMARY_KEY_3\",\"unique\":true,\"columns\":[\"ID\"]}]");
        flush();

        SchemaBlock schema = of(Findings.N_PLUS_ONE).get(0).schema();

        assertThat(schema).isNotNull();
        assertThat(schema.predicates()).containsExactly("order_line.order_id");
        assertThat(schema.unindexed()).containsExactly("order_line.order_id");
        assertThat(schema.tables()).extracting(SchemaBlock.Table::table).containsExactly("ORDER_LINE");
    }

    @Test
    void aFindingOfAnotherKindHasNoSchemaBlock() {
        Span.Builder root = entry(1, "/orders/report", 1000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from orders", "orders", NOW, 400)));
        catalog("ORDERS", "[]");
        flush();

        assertThat(of(Findings.SLOW_ENDPOINT).get(0).schema()).isNull();
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
    void aRootInternalSpanOverTheThresholdIsASlowJobWithItsDatabaseShare() {
        Span.Builder slow = job(11, "ReportJob.run", 900);
        Span.Builder alsoSlow = job(12, "ReportJob.run", 800);
        Span.Builder quick = job(13, "ReportJob.run", 100);
        decoder.accept(Otlp.traces(Otlp.service("orders"), slow,
                query(slow, 101, "select * from orders", "orders", NOW, 360),
                alsoSlow, quick));
        flush();

        List<Findings.Finding> slowJobs = of(Findings.SLOW_JOB);

        assertThat(slowJobs).hasSize(1);
        Findings.Finding finding = slowJobs.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("ReportJob.run is slow");
        assertThat(finding.service()).isEqualTo("orders");
        assertThat(finding.subject().job()).isEqualTo("ReportJob.run");
        assertThat(finding.subject().endpointId()).isNull();
        assertThat(finding.numbers().get("runs")).isEqualTo(3L);
        assertThat((Double) finding.numbers().get("p50Ms")).isEqualTo(800.0);
        assertThat((Double) finding.numbers().get("p95Ms")).isEqualTo(900.0);
        assertThat((Double) finding.numbers().get("maxMs")).isEqualTo(900.0);
        assertThat((Double) finding.numbers().get("totalMs")).isEqualTo(1800.0);
        assertThat((Double) finding.numbers().get("dbMsPerRun")).isEqualTo(120.0);
        assertThat((Double) finding.numbers().get("dbShare")).isEqualTo(0.2);
        assertThat(finding.why()).contains("p95 900.0 ms over 3 runs");
        assertThat(finding.code()).containsExactly("orders.ReportJob.run");
        assertThat(finding.traces()).hasSizeLessThanOrEqualTo(3);
        assertThat(finding.traces()).containsExactly(traceId(11), traceId(12), traceId(13));
    }

    @Test
    void aJobUnderTheThresholdIsNoFinding() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                job(11, "ReportJob.run", 100), job(12, "ReportJob.run", 120)));
        flush();

        assertThat(of(Findings.SLOW_JOB)).isEmpty();
    }

    @Test
    void onlyARootInternalSpanIsAJob() {
        Span.Builder request = entry(1, "/orders/report", 900);
        Span.Builder inside = Otlp.child(request, spanId(21), "ReportJob.run",
                Span.SpanKind.SPAN_KIND_INTERNAL, NOW, 800);
        Span.Builder seed = Otlp.span(traceId(22), spanId(22), "INSERT orders",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW, 900,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "insert into orders values (?)"),
                Otlp.attr("db.operation", "INSERT"),
                Otlp.attr("db.sql.table", "orders"));
        decoder.accept(Otlp.traces(Otlp.service("orders"), request, inside, seed));
        flush();

        assertThat(of(Findings.SLOW_JOB)).isEmpty();
        assertThat(of(Findings.SLOW_ENDPOINT)).extracting(Findings.Finding::title)
                .containsExactly("GET /orders/report is slow");
    }

    @Test
    void aSlowJobIsRankedAfterASlowEndpointOfTheSameSeverity() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry(1, "/orders/report", 700), job(11, "ReportJob.run", 700)));
        flush();

        List<Findings.Finding> found = findings.findings(window, null, 20);

        List<String> kinds = new ArrayList<>();
        for (Findings.Finding finding : found) {
            assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
            kinds.add(finding.kind());
        }
        assertThat(kinds).containsSubsequence(Findings.SLOW_ENDPOINT, Findings.SLOW_JOB);
    }

    /** findings.adoc's kind order: an N+1 comes before a log-error of the same severity. */
    @Test
    void aLogErrorIsRankedAfterAnNPlusOneOfTheSameSeverity() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders/{id}", 10)));
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "Payment gateway timeout", traceId(1), spanId(1))));
        Span.Builder root = entry(2, "/orders", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 20; i++) {
            spans.add(query(root, 200 + i, "select * from order_line where order_id = ?", "order_line",
                    NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();

        List<String> kinds = new ArrayList<>();
        for (Findings.Finding finding : findings.findings(window, null, 20)) {
            if (finding.severity().equals(Findings.HIGH)) {
                kinds.add(finding.kind());
            }
        }
        assertThat(kinds).containsSubsequence(Findings.N_PLUS_ONE, Findings.LOG_ERROR);
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

    @Test
    void aPoolFullAtAnotherPointThanTheMostWaitingReadsAsFull() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        // Three waiting with eight of ten in use, then nobody waiting with all ten in use.
        long first = NOW - 30_000;
        pool(first, "db.client.connections.usage", 8, Otlp.attr("state", "used"));
        pool(first, "db.client.connections.max", 10);
        pool(first, "db.client.connections.pending_requests", 3);
        pool(NOW, "db.client.connections.usage", 10, Otlp.attr("state", "used"));
        pool(NOW, "db.client.connections.max", 10);
        pool(NOW, "db.client.connections.pending_requests", 0);
        flush();

        Findings.Finding finding = of(Findings.POOL_EXHAUSTED).get(0);

        assertThat((Double) finding.numbers().get("pendingMax")).isEqualTo(3.0);
        assertThat(finding.numbers().get("at")).isEqualTo(first);
        assertThat((Double) finding.numbers().get("usedMax")).isEqualTo(10.0);
        assertThat(finding.numbers().get("max")).isEqualTo(10.0);
        assertThat(finding.why()).isEqualTo("up to 10.0 of 10.0 connections in use and up to 3.0 requests waiting");
    }

    private void pool(String metric, double value, io.opentelemetry.proto.common.v1.KeyValue... extra) {
        pool(NOW, metric, value, extra);
    }

    private void pool(long at, String metric, double value,
            io.opentelemetry.proto.common.v1.KeyValue... extra) {
        io.opentelemetry.proto.common.v1.KeyValue[] attributes =
                new io.opentelemetry.proto.common.v1.KeyValue[extra.length + 1];
        attributes[0] = Otlp.attr("pool.name", "HikariPool-1");
        System.arraycopy(extra, 0, attributes, 1, extra.length);
        decoder.accept(Otlp.sum(Otlp.service("orders"), metric, "{connection}", at, value, false,
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
    void aSlowOutboundCallIsASlowExternalWithItsCallersAndItsLine() {
        Span.Builder root = entry(1, "/orders/{id}", 900);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                outbound(root, 100, "localhost", 8081, NOW, 800)
                        .addAttributes(Otlp.attr("code.stacktrace", QUERY_STACKTRACE))));
        flush();

        List<Findings.Finding> slow = of(Findings.SLOW_EXTERNAL);

        assertThat(slow).hasSize(1);
        Findings.Finding finding = slow.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("GET localhost:8081 is slow");
        assertThat(finding.service()).isEqualTo("orders");
        assertThat(finding.subject().target()).isEqualTo("localhost:8081");
        assertThat(finding.numbers().get("calls")).isEqualTo(1L);
        assertThat(finding.numbers().get("errors")).isEqualTo(0L);
        assertThat((Double) finding.numbers().get("p50Ms")).isEqualTo(800.0);
        assertThat((Double) finding.numbers().get("p95Ms")).isEqualTo(800.0);
        assertThat((Double) finding.numbers().get("totalMs")).isEqualTo(800.0);
        assertThat(finding.why()).contains("p95 800.0 ms over 1 call, 0 errors");
        assertThat(finding.code()).containsExactly(
                "orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)",
                "orders.OrderService.lines(OrderService.java:54)");
        assertThat(finding.traces()).containsExactly(traceId(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) finding.numbers().get("callers");
        assertThat(callers).hasSize(1);
        assertThat(callers.get(0).get("endpoint")).isEqualTo("GET /orders/{id}");
        assertThat(callers.get(0).get("calls")).isEqualTo(1L);
    }

    @Test
    void aSlowExternalTakesItsCodeFromTheSlowCallNotTheNewest() {
        Span.Builder root = entry(1, "/orders/{id}", 2000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                outbound(root, 100, "localhost", 8081, NOW, 800)
                        .addAttributes(Otlp.attr("code.stacktrace", QUERY_STACKTRACE)),
                outbound(root, 101, "localhost", 8081, NOW + 900, 20)));
        flush();

        List<Findings.Finding> slow = of(Findings.SLOW_EXTERNAL);

        assertThat(slow).hasSize(1);
        assertThat(slow.get(0).code()).containsExactly(
                "orders.OrderLineRepository.findByOrderId(OrderLineRepository.java:29)",
                "orders.OrderService.lines(OrderService.java:54)");
    }

    @Test
    void aFastOutboundCallIsNoFinding() {
        Span.Builder root = entry(1, "/orders/{id}", 100);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                outbound(root, 100, "localhost", 8081, NOW, 40)));
        flush();

        assertThat(of(Findings.SLOW_EXTERNAL)).isEmpty();
    }

    @Test
    void aSlowEndpointNamesTheSpanItsTimeWentInto() {
        Span.Builder root = entry(1, "/orders/report", 1000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from orders", "orders", NOW, 800)));
        flush();

        Findings.Finding finding = of(Findings.SLOW_ENDPOINT).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> hot = (Map<String, Object>) finding.numbers().get("hotSpan");
        assertThat(hot).isNotNull();
        assertThat(hot.get("name")).isEqualTo("SELECT orders");
        assertThat(hot.get("category")).isEqualTo("db");
        assertThat((Double) hot.get("selfMs")).isEqualTo(800.0);
        assertThat((Double) hot.get("share")).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void aSlowEndpointSumsItsHotSpansOverEveryTraceOfTheSample() {
        // Two requests, each one slow query and one outbound call to a different item.
        for (int n = 1; n <= 2; n++) {
            Span.Builder root = entry(n, "/orders/report", 1000);
            decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                    query(root, 10 * n, "select * from orders", "orders", NOW, 600),
                    call(root, 10 * n + 1, "/api/books/" + n, NOW, 200)));
        }
        flush();

        Findings.Finding finding = of(Findings.SLOW_ENDPOINT).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hot =
                (List<Map<String, Object>>) finding.numbers().get("hotSpans");
        assertThat(hot).hasSize(2);
        assertThat(hot.get(0).get("name")).isEqualTo("SELECT orders");
        assertThat((Double) hot.get(0).get("selfMs"))
                .as("600 ms in each of the two traces")
                .isCloseTo(1200.0, within(1e-6));
        assertThat(hot.get(0).get("count")).isEqualTo(2L);
        assertThat((Double) hot.get(0).get("share")).isCloseTo(0.6, within(1e-9));
        assertThat(hot.get(1).get("name"))
                .as("two URLs that differ only in their digits are one row")
                .isEqualTo("GET localhost:8081/api/books/?");
        assertThat(hot.get(1).get("count")).isEqualTo(2L);
    }

    @Test
    void theBreakdownOfASlowEndpointSumsToOne() {
        Span.Builder root = entry(1, "/orders/report", 1000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root,
                query(root, 100, "select * from orders", "orders", NOW, 600),
                call(root, 101, "/api/books/1", NOW, 200)));
        flush();

        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown =
                (Map<String, Double>) of(Findings.SLOW_ENDPOINT).get(0).numbers().get("breakdown");
        assertThat(breakdown).containsOnlyKeys("db", "http", "internal", "self");
        assertThat(breakdown.get("db")).isCloseTo(0.6, within(1e-9));
        assertThat(breakdown.get("http")).isCloseTo(0.2, within(1e-9));
        assertThat(breakdown.get("internal")).isCloseTo(0.0, within(1e-9));
        assertThat(breakdown.get("self"))
                .as("the entry span's own time is what is left")
                .isCloseTo(0.2, within(1e-9));
        assertThat(breakdown.values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void theSpansOfADownstreamServiceBelongToItsOwnEndpoint() {
        Span.Builder root = entry(1, "/orders/{id}", 1000);
        Span.Builder outbound = call(root, 100, "/api/books/1", NOW, 900);
        decoder.accept(Otlp.traces(Otlp.service("orders"), root, outbound));
        // The callee's own server span and query, under the caller's outbound call.
        Span.Builder downstream = Otlp.child(outbound, spanId(200), "GET /api/books/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 880,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/api/books/{id}"),
                Otlp.attr("http.response.status_code", 200));
        decoder.accept(Otlp.traces(Otlp.service("bookstore"), downstream,
                query(downstream, 201, "select * from book where id = ?", "book", NOW, 850)));
        flush();

        Findings.Finding caller = of(Findings.SLOW_ENDPOINT).stream()
                .filter(f -> f.title().startsWith("GET /orders/{id}")).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown = (Map<String, Double>) caller.numbers().get("breakdown");
        assertThat(breakdown.get("http"))
                .as("the wait on the call is the caller's, and the callee's server span is not")
                .isCloseTo(0.9, within(1e-9));
        assertThat(breakdown.get("db"))
                .as("the callee's query belongs to the callee's endpoint")
                .isCloseTo(0.0, within(1e-9));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hot = (List<Map<String, Object>>) caller.numbers().get("hotSpans");
        assertThat(hot).extracting(row -> row.get("name"))
                .doesNotContain("SELECT book");
    }

    @Test
    void anErrorLogNoTraceReportsIsALogError() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders/{id}", 10)));
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "Payment gateway timeout for order 42", traceId(1), spanId(1)),
                Otlp.log(NOW + 1, 17, "Payment gateway timeout for order 43", traceId(1), spanId(1),
                        Otlp.attr("exception.stacktrace", STACKTRACE))));
        flush();

        List<Findings.Finding> found = of(Findings.LOG_ERROR);

        assertThat(found).hasSize(1);
        Findings.Finding finding = found.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.title())
                .isEqualTo("ERROR in OrderController: Payment gateway timeout for order ?");
        assertThat(finding.service()).isEqualTo("orders");
        assertThat(finding.subject().logger()).isEqualTo("orders.web.OrderController");
        assertThat(finding.numbers().get("count")).isEqualTo(2L);
        assertThat(finding.numbers().get("firstSeen")).isEqualTo(NOW);
        assertThat(finding.numbers().get("lastSeen")).isEqualTo(NOW + 1);
        assertThat(finding.numbers().get("logger")).isEqualTo("orders.web.OrderController");
        assertThat(finding.numbers().get("message")).isEqualTo("Payment gateway timeout for order ?");
        assertThat(finding.why()).contains("2 records in GET /orders/{id}");
        assertThat(finding.code()).containsExactly(
                "orders.OrderService.load(OrderService.java:41)",
                "orders.OrderController.show(OrderController.java:23)");
        assertThat(finding.traces()).containsExactly(traceId(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints =
                (List<Map<String, Object>>) finding.numbers().get("endpoints");
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).get("name")).isEqualTo("GET /orders/{id}");
        assertThat(endpoints.get(0).get("count")).isEqualTo(2L);
    }

    @Test
    void anErrorLogOnAFailedTraceIsNotCountedTwice() {
        Span.Builder failing = Otlp.failing(entry(1, "/orders/{id}", 10),
                "java.lang.IllegalStateException", "no such order 42", STACKTRACE);
        decoder.accept(Otlp.traces(Otlp.service("orders"), failing));
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "no such order 42", traceId(1), spanId(1))));
        flush();

        assertThat(of(Findings.LOG_ERROR))
                .as("the trace has an error span, so an error finding already reports it")
                .isEmpty();
        assertThat(of(Findings.ERROR)).hasSize(1);
    }

    @Test
    void aWarningIsNotALogError() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders/{id}", 10)));
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 13, "Retrying the payment gateway", traceId(1), spanId(1))));
        flush();

        assertThat(of(Findings.LOG_ERROR)).isEmpty();
    }

    @Test
    void aLongCollectionIsAGcPause() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        gc(NOW - 1000, 1, 0.01, 0.01);
        gc(NOW, 2, 0.62, 0.61);
        flush();

        List<Findings.Finding> paused = of(Findings.GC_PAUSE);

        assertThat(paused).hasSize(1);
        Findings.Finding finding = paused.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.title()).isEqualTo("G1 Young Generation paused for 610.0 ms");
        assertThat(finding.subject().jvm()).isEqualTo("gc:G1 Young Generation");
        assertThat(finding.numbers().get("gc")).isEqualTo("G1 Young Generation");
        assertThat(finding.numbers().get("action")).isEqualTo("end of minor GC");
        assertThat((Double) finding.numbers().get("worstMs")).isCloseTo(610.0, within(1e-6));
        assertThat((Double) finding.numbers().get("shareMax")).isCloseTo(0.61, within(1e-6));
        assertThat(finding.numbers().get("collections")).isEqualTo(1L);
        assertThat(finding.numbers().get("at")).isEqualTo(NOW);
        assertThat(finding.code()).isEmpty();
        assertThat(finding.traces()).isEmpty();
    }

    @Test
    void aLongCollectionBeforeTheWindowIsNoGcPauseInIt() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        gc(NOW - 1000, 2, 0.62, 0.61);
        gc(NOW, 3, 0.63, 0.61);
        flush();

        assertThat(of(Findings.GC_PAUSE)).isEmpty();
    }

    @Test
    void shortCollectionsAreNoFinding() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        gc(NOW - 1000, 10, 0.02, 0.004);
        gc(NOW, 20, 0.04, 0.004);
        flush();

        assertThat(of(Findings.GC_PAUSE)).isEmpty();
    }

    private void gc(long at, long count, double sum, double max) {
        decoder.accept(Otlp.histogram(Otlp.service("orders"), "jvm.gc.duration", "s",
                at, count, sum, max,
                Otlp.attr("jvm.gc.name", "G1 Young Generation"),
                Otlp.attr("jvm.gc.action", "end of minor GC")));
    }

    @Test
    void aHeapNearItsLimitIsHeapPressure() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        heap(NOW - 1000, 400_000_000, 1_000_000_000);
        heap(NOW, 950_000_000, 1_000_000_000);
        flush();

        List<Findings.Finding> pressure = of(Findings.HEAP_PRESSURE);

        assertThat(pressure).hasSize(1);
        Findings.Finding finding = pressure.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.title()).isEqualTo("heap at 95.0% of its limit");
        assertThat(finding.subject().jvm()).isEqualTo("heap");
        assertThat((Double) finding.numbers().get("usedMax")).isEqualTo(950_000_000.0);
        assertThat((Double) finding.numbers().get("limit")).isEqualTo(1_000_000_000.0);
        assertThat((Double) finding.numbers().get("ratioMax")).isCloseTo(0.95, within(1e-9));
        assertThat(finding.numbers().get("at")).isEqualTo(NOW);
        assertThat(finding.traces()).isEmpty();
    }

    @Test
    void aHeapWithRoomIsNoFinding() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        heap(NOW, 400_000_000, 1_000_000_000);
        flush();

        assertThat(of(Findings.HEAP_PRESSURE)).isEmpty();
    }

    private void heap(long at, double used, double limit) {
        decoder.accept(Otlp.gauge(Otlp.service("orders"), "jvm.memory.used", "By", at, used,
                Otlp.attr("jvm.memory.type", "heap"),
                Otlp.attr("jvm.memory.pool.name", "G1 Old Gen")));
        decoder.accept(Otlp.gauge(Otlp.service("orders"), "jvm.memory.limit", "By", at, limit,
                Otlp.attr("jvm.memory.type", "heap"),
                Otlp.attr("jvm.memory.pool.name", "G1 Old Gen")));
    }

    @Test
    void threadsThatKeepGrowingAreAFinding() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        threads(NOW - 1000, 40);
        threads(NOW, 140);
        flush();

        List<Findings.Finding> growth = of(Findings.THREAD_GROWTH);

        assertThat(growth).hasSize(1);
        Findings.Finding finding = growth.get(0);
        assertThat(finding.severity()).isEqualTo(Findings.MEDIUM);
        assertThat(finding.title()).isEqualTo("threads grew from 40 to 140");
        assertThat(finding.subject().jvm()).isEqualTo("threads");
        assertThat(finding.numbers().get("first")).isEqualTo(40L);
        assertThat(finding.numbers().get("last")).isEqualTo(140L);
        assertThat(finding.numbers().get("max")).isEqualTo(140L);
        assertThat(finding.numbers().get("at")).isEqualTo(NOW);
        assertThat(finding.traces()).isEmpty();
    }

    @Test
    void aSteadyThreadCountIsNoFinding() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entry(1, "/orders", 10)));
        threads(NOW - 1000, 40);
        threads(NOW, 50);
        flush();

        assertThat(of(Findings.THREAD_GROWTH)).isEmpty();
    }

    private void threads(long at, double count) {
        decoder.accept(Otlp.gauge(Otlp.service("orders"), "jvm.thread.count", "{thread}", at, count));
    }

    @Test
    void theLimitIsHonoured() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry(1, "/a", 900), entry(2, "/b", 900), entry(3, "/c", 900)));
        flush();

        assertThat(findings.findings(window, null, 2)).hasSize(2);
    }

    // --- acknowledgements (findings.adoc#acknowledgements) -----------------------------------------

    /** Three slow endpoints, so the acknowledged one has somewhere to fall to. */
    private void threeSlowEndpoints() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entry(1, "/a", 2000), entry(2, "/b", 1500), entry(3, "/c", 900)));
        flush();
    }

    @Test
    void anAcknowledgedFindingIsRankedLastAndCarriesItsAck() {
        threeSlowEndpoints();
        String first = findings.findings(window, null, 20).get(0).id();
        store.acks().ack(first, "slow by design");

        List<Findings.Finding> ranked = findings.findings(window, null, 20);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(2).id()).isEqualTo(first);
        assertThat(ranked.get(2).ack()).isNotNull();
        assertThat(ranked.get(2).ack().note()).isEqualTo("slow by design");
        assertThat(ranked.get(2).ack().at()).isPositive();
        assertThat(ranked.get(0).ack()).as("the others are untouched").isNull();
    }

    @Test
    void hideAckedLeavesItOutAndTheCountStandsEitherWay() {
        threeSlowEndpoints();
        String first = findings.findings(window, null, 20).get(0).id();
        store.acks().ack(first, null);

        Findings.Answer shown = findings.answer(window, null, 20, false);
        assertThat(shown.findings()).hasSize(3);
        assertThat(shown.acked()).isEqualTo(1);

        Findings.Answer hidden = findings.answer(window, null, 20, true);
        assertThat(hidden.findings()).hasSize(2);
        assertThat(hidden.findings()).noneMatch(f -> f.id().equals(first));
        assertThat(hidden.acked()).as("counted before the limit and before hiding").isEqualTo(1);
    }

    @Test
    void theAckedCountIsTakenBeforeTheLimit() {
        threeSlowEndpoints();
        for (Findings.Finding finding : findings.findings(window, null, 20)) {
            store.acks().ack(finding.id(), null);
        }

        Findings.Answer answer = findings.answer(window, null, 1, false);

        assertThat(answer.findings()).hasSize(1);
        assertThat(answer.acked()).isEqualTo(3);
    }

    // --- resolutions and states (findings.adoc#resolutions) -------------------------------------

    /** A resolution at a chosen instant: the store stamps its own with the clock. */
    private void resolveAt(String findingId, long at, @org.jspecify.annotations.Nullable String note) {
        store.sql().update("MERGE INTO ack (finding_id, at_ms, note, resolved) KEY(finding_id)"
                + " VALUES (?, ?, ?, TRUE)", java.util.Arrays.asList(findingId, at, note));
    }

    private Span.Builder entryAt(int n, String route, long at, long durationMs) {
        return Otlp.span(traceId(n), spanId(n), "GET " + route, Span.SpanKind.SPAN_KIND_SERVER,
                at, durationMs,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", route),
                Otlp.attr("http.response.status_code", 200));
    }

    @Test
    void aResolvedFindingThatCameBackIsARegressionRankedFirst() {
        threeSlowEndpoints();
        List<Findings.Finding> before = findings.findings(window, null, 20);
        Findings.Finding last = before.get(before.size() - 1);
        resolveAt(last.id(), NOW - 30_000, "added the index");

        List<Findings.Finding> ranked = findings.findings(window, null, 20);

        assertThat(ranked).hasSize(3);
        Findings.Finding regression = ranked.get(0);
        assertThat(regression.id()).as("the id stays the finding's own").isEqualTo(last.id());
        assertThat(regression.kind()).isEqualTo(Findings.REGRESSION);
        assertThat(regression.baseKind()).isEqualTo(Findings.SLOW_ENDPOINT);
        assertThat(regression.severity()).isEqualTo(Findings.HIGH);
        assertThat(regression.state()).isEqualTo(Findings.REGRESSED);
        assertThat(regression.numbers()).containsEntry("resolvedAt", NOW - 30_000)
                .containsEntry("note", "added the index")
                .containsEntry("originalKind", Findings.SLOW_ENDPOINT)
                .containsKey("p95Ms");
        assertThat(regression.subject()).isEqualTo(last.subject());
        assertThat(regression.traces()).isEqualTo(last.traces());
        assertThat(regression.resolution()).isNotNull();
        assertThat(regression.why()).startsWith("came back after it was resolved (added the index); ");
        assertThat(ranked.subList(1, 3)).noneMatch(f -> f.kind().equals(Findings.REGRESSION));
    }

    @Test
    void aResolutionOlderThanTheWindowMakesAnyOccurrenceInItARegression() {
        threeSlowEndpoints();
        String id = findings.findings(window, null, 20).get(1).id();
        resolveAt(id, NOW - 3_600_000, "fixed");

        assertThat(findings.findings(window, null, 20).get(0).id()).isEqualTo(id);
        assertThat(of(Findings.REGRESSION)).hasSize(1);
    }

    @Test
    void aResolvedFindingThatHasNotComeBackIsSetAsideAndCounted() {
        threeSlowEndpoints();
        String first = findings.findings(window, null, 20).get(0).id();
        resolveAt(first, NOW + 1_000, "fixed in this run");

        Findings.Answer shown = findings.answer(window, null, 20, false);
        assertThat(shown.findings()).hasSize(3);
        assertThat(shown.resolved()).isEqualTo(1);
        assertThat(shown.acked()).isZero();
        Findings.Finding aside = shown.findings().get(2);
        assertThat(aside.id()).as("its occurrences all precede the fix").isEqualTo(first);
        assertThat(aside.kind()).isEqualTo(Findings.SLOW_ENDPOINT);
        assertThat(aside.resolution()).isNotNull();
        assertThat(aside.ack()).isNull();
        assertThat(aside.setAside()).isTrue();

        Findings.Answer hidden = findings.answer(window, null, 20, true);
        assertThat(hidden.findings()).noneMatch(f -> f.id().equals(first));
        assertThat(hidden.resolved()).isEqualTo(1);
    }

    @Test
    void anOccurrenceAfterAResolutionInsideTheWindowIsTheRegressionsEvidence() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entryAt(1, "/a", NOW - 20_000, 2000), entryAt(2, "/a", NOW + 20_000, 3000)));
        flush();
        String id = findings.findings(window, null, 20).get(0).id();
        resolveAt(id, NOW, null);

        Findings.Finding regression = findings.findings(window, null, 20).get(0);

        assertThat(regression.kind()).isEqualTo(Findings.REGRESSION);
        assertThat(regression.traces()).as("only the traffic after the fix").containsExactly(traceId(2));
        assertThat(regression.numbers()).containsEntry("calls", 1L).containsEntry("note", null);
        assertThat(regression.why()).startsWith("came back after it was resolved; ");
    }

    @Test
    void anAcknowledgedFindingThatRecursStaysAcknowledged() {
        threeSlowEndpoints();
        String first = findings.findings(window, null, 20).get(0).id();
        resolveAt(first, NOW - 30_000, "fixed");
        store.acks().ack(first, "accepted after all");

        List<Findings.Finding> ranked = findings.findings(window, null, 20);

        assertThat(of(Findings.REGRESSION)).as("the newer decision replaced the resolution").isEmpty();
        assertThat(ranked.get(2).id()).isEqualTo(first);
        assertThat(ranked.get(2).ack()).isNotNull();
        assertThat(ranked.get(2).resolution()).isNull();
    }

    @Test
    void aFindingIsNewUnlessThePreviousRunOfItsServiceHadItToo() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entryAt(1, "/a", NOW - 50_000, 2000)));
        flush();
        store.marks().create("start", "orders", "pid 2", NOW - 30_000);
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entryAt(2, "/a", NOW, 2000), entryAt(3, "/b", NOW, 1500)));
        flush();

        List<Findings.Finding> ranked = findings.findings(Window.of(NOW - 30_000, NOW + 60_000), null, 20);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).title()).contains("/a");
        assertThat(ranked.get(0).state()).isEqualTo(Findings.ONGOING);
        assertThat(ranked.get(1).title()).contains("/b");
        assertThat(ranked.get(1).state()).isEqualTo(Findings.NEW);
    }

    @Test
    void theRunBeforeTheOneBeforeDoesNotCount() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entryAt(1, "/a", NOW - 50_000, 2000)));
        flush();
        store.marks().create("start", "orders", "pid 2", NOW - 40_000);
        store.marks().create("start", "orders", "pid 3", NOW - 30_000);
        decoder.accept(Otlp.traces(Otlp.service("orders"), entryAt(2, "/a", NOW, 2000)));
        flush();

        List<Findings.Finding> ranked = findings.findings(Window.of(NOW - 30_000, NOW + 60_000), null, 20);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).state()).as("the run between the two marks had nothing").isEqualTo(Findings.NEW);
    }

    @Test
    void aServiceThatNeverRestartedHasOnlyNewFindings() {
        threeSlowEndpoints();

        assertThat(findings.findings(window, null, 20)).extracting(Findings.Finding::state)
                .containsOnly(Findings.NEW);
    }

    @Test
    void anNPlusOneIsTheSameFindingWhenItsServiceIsNamed() {
        Span.Builder root = entry(1, "/orders/{id}", 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(query(root, 100 + i, "select * from order_line where order_id = ?", "order_line",
                    NOW + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        decoder.accept(Otlp.traces(Otlp.service("stock"), entry(2, "/stock", 5)));
        flush();

        List<Findings.Finding> named = findings.findings(window, "orders", 20);

        assertThat(named).extracting(Findings.Finding::id).containsExactlyElementsOf(
                of(Findings.N_PLUS_ONE).stream().map(Findings.Finding::id).toList());
        assertThat(named.get(0).title()).isEqualTo("GET /orders/{id} runs SELECT order_line 6 times per request");
    }

    @Test
    void anNPlusOneOfThePreviousRunIsOngoing() {
        nPlusOneAt(1, NOW - 50_000);
        store.marks().create("start", "orders", "pid 2", NOW - 30_000);
        nPlusOneAt(2, NOW);

        List<Findings.Finding> ranked = findings.findings(Window.of(NOW - 30_000, NOW + 60_000), null, 20);

        assertThat(ranked).extracting(Findings.Finding::kind).containsExactly(Findings.N_PLUS_ONE);
        assertThat(ranked.get(0).state()).isEqualTo(Findings.ONGOING);
    }

    @Test
    void whatThePreviousRunHadIsAskedOnceAndKept() {
        decoder.accept(Otlp.traces(Otlp.service("orders"), entryAt(1, "/a", NOW - 50_000, 2000)));
        flush();
        store.marks().create("start", "orders", "pid 2", NOW - 30_000);
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                entryAt(2, "/a", NOW, 2000), entryAt(3, "/b", NOW, 1500)));
        flush();
        Window afterRestart = Window.of(NOW - 30_000, NOW + 60_000);
        assertThat(findings.findings(afterRestart, null, 20)).extracting(Findings.Finding::state)
                .containsExactly(Findings.ONGOING, Findings.NEW);

        // A span of the closed run arriving late changes nothing the server already knows.
        decoder.accept(Otlp.traces(Otlp.service("orders"), entryAt(4, "/b", NOW - 45_000, 1500)));
        flush();

        assertThat(findings.findings(afterRestart, null, 20)).extracting(Findings.Finding::state)
                .containsExactly(Findings.ONGOING, Findings.NEW);
        Findings fresh = new Findings(store.sql(), queries, metrics, store.services(), store.tingles(),
                new CodeFrames(""));
        assertThat(fresh.findings(afterRestart, null, 20)).extracting(Findings.Finding::state)
                .as("a server that never asked reads the run as it is now")
                .containsExactly(Findings.ONGOING, Findings.ONGOING);
    }

    private void nPlusOneAt(int n, long at) {
        Span.Builder root = entryAt(n, "/orders/{id}", at, 60);
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(query(root, n * 100 + i, "select * from order_line where order_id = ?", "order_line",
                    at + i, 2));
        }
        decoder.accept(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
        flush();
    }
}
