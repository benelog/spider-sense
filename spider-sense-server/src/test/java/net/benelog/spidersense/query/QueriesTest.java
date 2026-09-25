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
import net.benelog.spidersense.store.Ids;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Sweeper;

/** The SQL behind the API: grouping, percentiles, trace summaries, retention. */
class QueriesTest {

    private static final long NOW = 1_700_000_000_000L;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
    private final Queries queries = new Queries(store.sql(), store.tingles(), store.services());
    private final Window window = Window.of(NOW - 60_000, NOW + 60_000);

    @AfterEach
    void close() {
        store.close();
    }

    private static String traceId(int n) {
        return "%032x".formatted(n);
    }

    private static String spanId(int n) {
        return "%016x".formatted(n);
    }

    private void flush() {
        store.writer().awaitIdle(5_000);
    }

    @Test
    void percentilesAreNearestRankOverTheEntrySpansOfTheWindow() {
        for (int i = 1; i <= 10; i++) {
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(i), spanId(i), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                            NOW, i * 10L, Otlp.attr("http.route", "/orders"),
                            Otlp.attr("http.request.method", "GET"))));
        }
        flush();

        Stats.Totals totals = queries.totals(window, null);
        assertThat(totals.requests()).isEqualTo(10);
        assertThat(totals.p50Ms()).isEqualTo(50.0);
        assertThat(totals.p95Ms()).isEqualTo(100.0);
        assertThat(totals.p99Ms()).isEqualTo(100.0);
        assertThat(totals.maxMs()).isEqualTo(100.0);
        assertThat(totals.errors()).isZero();
    }

    @Test
    void theResponseBucketsAreDerivedFromTheSlowRequestThresholdAndApdexFollowsFromThem() {
        ResponseBuckets buckets = new ResponseBuckets(500);

        assertThat(buckets.bounds()).containsExactly(125L, 500L, 2000L);
        // Satisfied up to T, tolerating up to 4T at half weight, errors frustrated.
        assertThat(ResponseBuckets.apdex(new long[]{1, 1, 2, 4, 2}, 10)).isEqualTo(0.3);
        assertThat(ResponseBuckets.apdex(new long[]{0, 0, 0, 0, 0}, 0)).isNull();
    }

    @Test
    void theHistogramCountsEachResponseTimeBucketAndKeepsTheErrorsApart() {
        long[] durations = {50, 300, 1000, 5000};
        for (int i = 0; i < durations.length; i++) {
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(i + 1), spanId(i + 1), "GET /orders",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, durations[i])));
        }
        // An error is counted in the fifth slot only, however fast it answered.
        decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.failing(
                Otlp.span(traceId(5), spanId(5), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 1),
                "java.lang.IllegalStateException", "no", "at Orders.list")));
        flush();

        Stats.Totals totals = queries.totals(window, null);

        assertThat(totals.requests()).isEqualTo(5);
        assertThat(totals.histogram()).containsExactly(1, 1, 1, 1, 1);
        assertThat(totals.apdex()).isEqualTo(0.5);
        Stats.Buckets buckets = queries.buckets(window, null, null);
        assertThat(buckets.histogram()).hasDimensions(4, buckets.t().length);
    }

    @Test
    void endpointsGroupOnTheEndpointIdentityRule() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders/42", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 10, Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/orders/{id}"),
                        Otlp.attr("http.response.status_code", 200)),
                Otlp.span(traceId(2), spanId(2), "GET /orders/43", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 30, Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/orders/{id}"),
                        Otlp.attr("http.response.status_code", 500)),
                // A servlet wildcard route must not collapse every endpoint into one.
                Otlp.span(traceId(3), spanId(3), "GET /inventory", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 1, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/*"))));
        flush();

        List<Stats.EndpointStats> endpoints = queries.endpoints(window, null, null);

        assertThat(endpoints).extracting(Stats.EndpointStats::name)
                .containsExactlyInAnyOrder("GET /orders/{id}", "GET /inventory");
        Stats.EndpointStats orders = endpoints.stream()
                .filter(e -> e.name().equals("GET /orders/{id}")).findFirst().orElseThrow();
        assertThat(orders.calls()).isEqualTo(2);
        assertThat(orders.totalMs()).isEqualTo(40.0);
        assertThat(orders.avgMs()).isEqualTo(20.0);
        assertThat(orders.statusCodes()).containsEntry("200", 1L).containsEntry("500", 1L);
        assertThat(orders.endpointId()).isEqualTo(Ids.endpointId("orders", "GET /orders/{id}"));
    }

    @Test
    void anIgnoredEndpointIsStoredAndInItsTraceButIsNeverARequest() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 10, Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/orders")),
                Otlp.span(traceId(2), spanId(2), "GET /actuator/health", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 900, Otlp.attr("http.request.method", "GET"),
                        Otlp.attr("http.route", "/actuator/health"),
                        Otlp.attr("http.response.status_code", 200))));
        flush();

        assertThat(queries.endpoints(window, null, null)).extracting(Stats.EndpointStats::name)
                .containsExactly("GET /orders");
        assertThat(queries.totals(window, null).requests()).isEqualTo(1);
        // Slower than the 500 ms threshold, and still not a slow-request tingle.
        assertThat(queries.tingles(window, 50)).isEmpty();

        Queries.TraceDetail trace = queries.trace(traceId(2));
        assertThat(trace).isNotNull();
        assertThat(trace.spans()).extracting(net.benelog.spidersense.store.SpanRecord::name)
                .containsExactly("GET /actuator/health");
        assertThat(queries.traces(
                new Queries.TraceFilter(window, null, null, null, null, null, null, null, 50)))
                .extracting(Stats.TraceSummary::traceId)
                .containsExactlyInAnyOrder(traceId(1), traceId(2));
    }

    @Test
    void queriesGroupByStatementAndKnowWhichEndpointCalledThem() {
        for (int i = 1; i <= 3; i++) {
            Span.Builder root = Otlp.span(traceId(i), spanId(i), "GET /orders/report",
                    Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                    Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders/report"));
            Span.Builder query = Otlp.child(root, spanId(100 + i), "SELECT orders",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 200,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from orders where name like ?"),
                    Otlp.attr("db.name", "orders"));
            decoder.accept(Otlp.traces(Otlp.service("orders"), root, query));
        }
        flush();

        List<Stats.QueryStats> stats = queries.queries(window, null, "total", 100, null);

        assertThat(stats).hasSize(1);
        Stats.QueryStats query = stats.get(0);
        assertThat(query.calls()).isEqualTo(3);
        assertThat(query.system()).isEqualTo("h2");
        assertThat(query.namespace()).isEqualTo("orders");
        assertThat(query.slowCalls()).isEqualTo(3);
        assertThat(query.statement()).isEqualTo("select * from orders where name like ?");
        assertThat(query.callers()).hasSize(1);
        assertThat(query.callers().get(0).endpoint()).isEqualTo("GET /orders/report");
        assertThat(query.callers().get(0).calls()).isEqualTo(3);
        assertThat(query.queryId())
                .isEqualTo(Ids.queryId("orders", "h2", "select * from orders where name like ?"));
    }

    @Test
    void everyDocumentedSortRunsAndAverageIsTotalOverCalls() {
        // Ten fast calls of one statement (the largest total) and one slow call of
        // another (the largest average): the two orders disagree on purpose.
        Span.Builder root = Otlp.span(traceId(1), spanId(1), "GET /orders/report",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders/report"));
        Span.Builder[] children = new Span.Builder[11];
        for (int i = 0; i < 10; i++) {
            children[i] = Otlp.child(root, spanId(100 + i), "SELECT orders",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 50,
                    Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select * from orders where id = ?"));
        }
        children[10] = Otlp.child(root, spanId(200), "SELECT customers",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW + 20, 300,
                Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select * from customers"));
        Span.Builder[] all = new Span.Builder[12];
        all[0] = root;
        System.arraycopy(children, 0, all, 1, 11);
        decoder.accept(Otlp.traces(Otlp.service("orders"), all));
        flush();

        for (String sort : List.of("total", "avg", "p95", "max", "calls")) {
            assertThat(queries.queries(window, null, sort, 100, null)).as(sort).hasSize(2);
        }
        assertThat(queries.queries(window, null, "total", 100, null).get(0).statement())
                .isEqualTo("select * from orders where id = ?");
        assertThat(queries.queries(window, null, "avg", 100, null).get(0).statement())
                .isEqualTo("select * from customers");
    }

    @Test
    void aServiceWithOnlyJobsIsStillANodeOnTheMap() {
        // A worker: a root INTERNAL span, never a request, with a database span under it.
        Span.Builder job = Otlp.span(traceId(1), spanId(1), "archive-events",
                Span.SpanKind.SPAN_KIND_INTERNAL, NOW, 900);
        Span.Builder query = Otlp.child(job, spanId(2), "SELECT events",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 200,
                Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select count(*) from events"),
                Otlp.attr("db.name", "worker"));
        decoder.accept(Otlp.traces(Otlp.service("batch-worker"), job, query));
        flush();

        Stats.ServiceMap map = queries.map(window);

        List<String> ids = map.nodes().stream().map(Stats.Node::id).toList();
        assertThat(ids).contains("svc:batch-worker");
        assertThat(map.edges()).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("svc:batch-worker");
            assertThat(ids).contains(edge.to());
        });
        assertThat(map.edges()).allSatisfy(edge -> assertThat(ids).contains(edge.from(), edge.to()));
    }

    @Test
    void errorsGroupOnTheNormalisedMessage() {
        for (int i = 1; i <= 2; i++) {
            decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.failing(
                    Otlp.span(traceId(i), spanId(i), "POST /orders/{id}/ship",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                            Otlp.attr("http.request.method", "POST"),
                            Otlp.attr("http.route", "/orders/{id}/ship")),
                    "java.lang.IllegalStateException", "Order " + (41 + i) + " is already shipped",
                    "at Orders.ship")));
        }
        flush();

        List<Stats.ErrorGroup> groups = queries.errors(window, null, 100, null);

        assertThat(groups).hasSize(1);
        Stats.ErrorGroup group = groups.get(0);
        assertThat(group.count()).isEqualTo(2);
        assertThat(group.type()).isEqualTo("java.lang.IllegalStateException");
        assertThat(group.message()).isEqualTo("Order ? is already shipped");
        assertThat(group.endpoints()).extracting(Stats.EndpointCount::name)
                .containsExactly("POST /orders/{id}/ship");
        assertThat(group.sample()).isNotNull();
        assertThat(group.sample().stacktrace()).isEqualTo("at Orders.ship");
        assertThat(group.sample().message()).isEqualTo("Order 43 is already shipped");
    }

    private static String wrapped(String outerMessage, String rootType, String rootFrame) {
        return """
                org.springframework.dao.DataIntegrityViolationException: %s
                \tat org.springframework.orm.jpa.EntityManagerFactoryUtils.convert(EntityManagerFactoryUtils.java:360)
                \tat orders.OrderService.place(OrderService.java:30)
                \tat org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:1089)
                Caused by: %s: constraint violated
                \tat org.h2.message.DbException.get(DbException.java:223)
                \tat %s
                \t... 2 more""".formatted(outerMessage, rootType, rootFrame);
    }

    private void fail(int n, String type, String message, String stacktrace) {
        decoder.accept(Otlp.traces(Otlp.service("orders"), Otlp.failing(
                Otlp.span(traceId(n), spanId(n), "POST /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                        Otlp.attr("http.request.method", "POST"), Otlp.attr("http.route", "/orders")),
                type, message, stacktrace)));
    }

    @Test
    void wrappedExceptionsWithTheSameRootCauseAndLineAreOneGroupWhateverTheirMessage() {
        fail(1, "org.springframework.dao.DataIntegrityViolationException",
                "could not execute statement [insert into orders (id, customer) values (?, ?)]",
                wrapped("could not execute statement [insert into orders (id, customer) values (?, ?)]",
                        "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException",
                        "orders.OrderRepository.save(OrderRepository.java:41)"));
        fail(2, "org.springframework.dao.DataIntegrityViolationException",
                "could not execute statement [update orders set status=? where id=?]",
                wrapped("could not execute statement [update orders set status=? where id=?]",
                        "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException",
                        "orders.OrderRepository.save(OrderRepository.java:44)"));
        flush();

        List<Stats.ErrorGroup> groups = queries.errors(window, null, 100, null);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).count()).isEqualTo(2);
        assertThat(groups.get(0).type()).isEqualTo("org.springframework.dao.DataIntegrityViolationException");
    }

    @Test
    void theSameWrapperOverUnrelatedCausesIsTwoGroups() {
        String message = "could not execute statement";
        fail(1, "org.springframework.dao.DataIntegrityViolationException", message,
                wrapped(message, "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException",
                        "orders.OrderRepository.save(OrderRepository.java:41)"));
        fail(2, "org.springframework.dao.DataIntegrityViolationException", message,
                wrapped(message, "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException",
                        "orders.CustomerRepository.save(CustomerRepository.java:18)"));
        fail(3, "org.springframework.dao.DataIntegrityViolationException", message,
                wrapped(message, "org.h2.jdbc.JdbcSQLDataException",
                        "orders.OrderRepository.save(OrderRepository.java:41)"));
        flush();

        assertThat(queries.errors(window, null, 100, null)).hasSize(3);
    }

    @Test
    void withoutAnApplicationFrameTheGroupIsTheTypeAndTheNormalisedMessage() {
        String trace = """
                java.lang.IllegalStateException: Order %d is already shipped
                \tat org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:1089)""";
        fail(1, "java.lang.IllegalStateException", "Order 41 is already shipped", trace.formatted(41));
        fail(2, "java.lang.IllegalStateException", "Order 42 is already shipped", trace.formatted(42));
        fail(3, "java.lang.IllegalStateException", "No stock for 'ABC'", trace.formatted(0));
        flush();

        List<Stats.ErrorGroup> groups = queries.errors(window, null, 100, null);

        assertThat(groups).extracting(Stats.ErrorGroup::count).containsExactly(2L, 1L);
        assertThat(groups.get(0).errorId()).isEqualTo(Ids.errorId("orders", "java.lang.IllegalStateException",
                "Order ? is already shipped"));
    }

    @Test
    void theWriterMaintainsOneTraceRowPerTraceAcrossSeveralExports() {
        Span.Builder root = Otlp.span(traceId(1), spanId(1), "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 152,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        decoder.accept(Otlp.traces(Otlp.service("orders"), root));
        flush();
        // The second service's spans arrive in an export of their own, as they do in life.
        decoder.accept(Otlp.traces(Otlp.service("bookstore"),
                Otlp.child(root, spanId(2), "SELECT books", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 10, 30,
                        Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select * from books"))));
        flush();

        List<Stats.TraceSummary> traces = queries.traces(new Queries.TraceFilter(
                window, null, null, null, null, null, null, null, 50));

        assertThat(traces).hasSize(1);
        Stats.TraceSummary trace = traces.get(0);
        assertThat(trace.spanCount()).isEqualTo(2);
        assertThat(trace.dbCount()).isEqualTo(1);
        assertThat(trace.errorCount()).isZero();
        assertThat(trace.rootName()).isEqualTo("GET /orders/{id}");
        assertThat(trace.rootService()).isEqualTo("orders");
        assertThat(trace.httpStatus()).isEqualTo(200L);
        assertThat(trace.services()).containsExactlyInAnyOrder("orders", "bookstore");
        assertThat(trace.slow()).isFalse();
    }

    @Test
    void theFreeTextSearchLooksInSpanNamesAndAttributeValues() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                        Otlp.attr("enduser.id", "grumpy-badger"))));
        flush();

        assertThat(queries.traces(filterWithQuery("GRUMPY"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("/orders"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("nothing-like-this"))).isEmpty();
    }

    private Queries.TraceFilter filterWithQuery(String q) {
        return new Queries.TraceFilter(window, null, null, null, null, null, q, null, 50);
    }

    @Test
    void tracesThatStartInTheSameMillisecondPageByTheirTraceId() {
        for (int i = 1; i <= 5; i++) {
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(i), spanId(i), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)));
        }
        flush();

        List<String> seen = new ArrayList<>();
        Long before = null;
        String beforeId = null;
        for (int page = 0; page < 5; page++) {
            List<Stats.TraceSummary> rows = queries.traces(new Queries.TraceFilter(
                    window, null, null, null, null, null, null, before, beforeId, 2));
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> seen.add(row.traceId()));
            Stats.TraceSummary last = rows.get(rows.size() - 1);
            before = last.start();
            beforeId = last.traceId();
        }

        assertThat(seen).containsExactly(traceId(5), traceId(4), traceId(3), traceId(2), traceId(1));
        // The time alone still pages strictly before it.
        assertThat(queries.traces(new Queries.TraceFilter(
                window, null, null, null, null, null, null, NOW, 50))).isEmpty();
    }

    /** Traces tied on duration or start come back in trace id order, whatever H2 scanned first. */
    @Test
    void tracesTiedOnTheOrderingColumnAreOrderedByTheirId() {
        for (int n : new int[] {3, 1, 2}) {
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(n), spanId(n), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)));
        }
        flush();

        assertThat(queries.slowestOf(window, List.of(traceId(3), traceId(1), traceId(2)), 2))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
        assertThat(queries.tracesContaining(window, "name = ?", List.of("GET /orders"), 2, true))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
        assertThat(queries.tracesContaining(window, "name = ?", List.of("GET /orders"), 2, false))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
    }

    /** Siblings that start in the same nanosecond are listed by span id, whatever order they arrived in. */
    @Test
    void spansTiedOnTheirStartAreOrderedByTheirSpanId() {
        Span.Builder root = Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 50);
        Span.Builder later = Otlp.child(root, spanId(0x20), "SELECT b", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 5);
        Span.Builder earlier = Otlp.child(root, spanId(0x10), "SELECT a", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 5);
        decoder.accept(Otlp.traces(Otlp.service("orders"), later, root, earlier));
        flush();

        Queries.TraceDetail trace = queries.trace(traceId(1));

        assertThat(trace).isNotNull();
        assertThat(trace.spans()).extracting(net.benelog.spidersense.store.SpanRecord::spanId)
                .containsExactly(spanId(1), spanId(0x10), spanId(0x20));
    }

    @Test
    void logsWrittenInTheSameMillisecondPageByTheirId() {
        io.opentelemetry.proto.logs.v1.LogRecord[] burst = new io.opentelemetry.proto.logs.v1.LogRecord[5];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = Otlp.log(NOW, 17, "line " + i, null, null);
        }
        decoder.accept(Otlp.logs(Otlp.service("orders"), "o.e.Orders", burst));
        flush();

        List<String> seen = new ArrayList<>();
        Long before = null;
        Long beforeId = null;
        for (int page = 0; page < 5; page++) {
            List<LogRecord> rows = queries.logs(new Queries.LogFilter(
                    window, null, null, null, null, before, beforeId, 2));
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> seen.add(row.body()));
            LogRecord last = rows.get(rows.size() - 1);
            before = last.at();
            beforeId = last.id();
        }

        assertThat(seen).containsExactly("line 4", "line 3", "line 2", "line 1", "line 0");
    }

    /** q is a substring: its % and _ match themselves, not any text. */
    @Test
    void freeTextIsASubstringNotALikePattern() {
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.Job",
                Otlp.log(NOW, 9, "100% done", null, null),
                Otlp.log(NOW + 1, 9, "1000 done", null, null),
                Otlp.log(NOW + 2, 9, "a_b", null, null),
                Otlp.log(NOW + 3, 9, "axb", null, null)));
        flush();

        assertThat(queries.logs(new Queries.LogFilter(window, null, null, "100%", null, null, 50)))
                .extracting(LogRecord::body).containsExactly("100% done");
        assertThat(queries.logs(new Queries.LogFilter(window, null, null, "a_b", null, null, 50)))
                .extracting(LogRecord::body).containsExactly("a_b");
    }

    /** A log-error finding's link searches for its logger, which is a column of its own. */
    @Test
    void freeTextOverLogsMatchesTheLogger() {
        decoder.accept(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "Payment gateway timeout", null, null)));
        flush();

        List<LogRecord> rows = queries.logs(new Queries.LogFilter(
                window, null, "ERROR", "orders.web.OrderController", null, null, 50));

        assertThat(rows).extracting(LogRecord::body).containsExactly("Payment gateway timeout");
    }

    @Test
    void bucketsLineUpWithTheWindowAndCountWhatFellInThem() {
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5),
                Otlp.span(traceId(2), spanId(2), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 7)));
        flush();

        Stats.Buckets buckets = queries.buckets(window, null, null);

        assertThat(buckets.t()).hasSize(window.bucketCount());
        int index = window.indexOf(NOW);
        assertThat(buckets.requests()[index]).isEqualTo(2);
        assertThat(java.util.Arrays.stream(buckets.requests()).sum()).isEqualTo(2);
    }

    @Test
    void theSweeperDeletesWhatIsOlderThanTheRetentionAndClearEmptiesEverything() {
        long old = System.currentTimeMillis() - 48 * 3_600_000L;
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /old", Span.SpanKind.SPAN_KIND_SERVER, old, 5)));
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(2), spanId(2), "GET /new", Span.SpanKind.SPAN_KIND_SERVER,
                        System.currentTimeMillis(), 5)));
        flush();
        assertThat(queries.spanCount()).isEqualTo(2);

        new Sweeper(store.sql(), 24).sweep();

        assertThat(queries.spanCount()).isEqualTo(1);
        assertThat(queries.traceCount()).isEqualTo(1);

        store.clear();

        assertThat(queries.spanCount()).isZero();
        assertThat(queries.traceCount()).isZero();
        // Services survive: the picker should not empty itself because a window was cleared.
        assertThat(store.services().count()).isEqualTo(1);
    }

    @Test
    void aTraceContainsAGroupOnlyThroughASpanThatStartsInTheWindow() {
        Span.Builder late = Otlp.span(traceId(1), spanId(1), "GET /late", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 50_000, 20_000);
        Span.Builder early = Otlp.span(traceId(2), spanId(2), "GET /early", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 2_000);
        decoder.accept(Otlp.traces(Otlp.service("orders"),
                late, Otlp.child(late, spanId(11), "lookup", Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 65_000, 5),
                early, Otlp.child(early, spanId(12), "lookup", Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 1_000, 5)));
        flush();

        assertThat(queries.tracesContaining(window, "name = ?", "lookup", 10, false))
                .as("the span of the first trace starts after the window ends")
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
        assertThat(queries.tracesContaining(Window.of(NOW - 60_000, NOW + 70_000), "name = ?", "lookup", 10, false))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
    }
}
