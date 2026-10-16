package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.KeyValue;
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

    private final Store store = new Store(Store.Settings.defaults(TestStore.memoryUrl()));
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
    void percentilesArePercentileDiscOverTheEntrySpansOfTheWindow() {
        for (int i = 1; i <= 10; i++) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
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

    /** A window, a service or a filter with no request has no percentile and no max, not a 0 ms one. */
    @Test
    void noRequestIsNoPercentileAndNoMax() {
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 10, Otlp.attr("http.route", "/orders"), Otlp.attr("http.request.method", "GET"))));
        flush();
        Window quiet = Window.of(NOW - 120_000, NOW - 61_000);

        for (Stats.Totals totals : List.of(queries.totals(quiet, null), queries.totals(window, "nope"),
                queries.services(quiet).get(0).totals())) {
            assertThat(totals.requests()).isZero();
            assertThat(totals.p50Ms()).isNaN();
            assertThat(totals.p95Ms()).isNaN();
            assertThat(totals.p99Ms()).isNaN();
            assertThat(totals.maxMs()).isNaN();
            assertThat(totals.apdex()).isNull();
        }
        assertThat(queries.totals(window, null).p95Ms()).isEqualTo(10.0);
    }

    /** A percentile read row by row names the rank H2's PERCENTILE_DISC names over the same values. */
    @Test
    void theJavaPercentileIsTheDefinitionOfPercentileDisc() {
        for (String fraction : List.of("0.5", "0.95", "0.99")) {
            for (int n = 1; n <= 120; n++) {
                double[] values = new double[n];
                for (int i = 0; i < n; i++) {
                    values[i] = i + 1;
                }
                Long disc = store.sql().queryOne("SELECT PERCENTILE_DISC(" + fraction
                        + ") WITHIN GROUP (ORDER BY X) FROM SYSTEM_RANGE(1, " + n + ")", List.of(),
                        rs -> rs.getLong(1));
                assertThat(Queries.percentile(values, Double.parseDouble(fraction)))
                        .as("p%s of %d values", fraction, n).isEqualTo(disc.doubleValue());
            }
        }
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
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(i + 1), spanId(i + 1), "GET /orders",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, durations[i])));
        }
        // An error is counted in the fifth slot only, however fast it answered.
        decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.failing(
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
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
        assertThat(queries.tingles(window, null, 50)).isEmpty();

        Queries.TraceDetail trace = queries.trace(traceId(2));
        assertThat(trace).isNotNull();
        assertThat(trace.spans()).extracting(net.benelog.spidersense.store.SpanRecord::name)
                .containsExactly("GET /actuator/health");
        assertThat(queries.traces(
                new Queries.TraceFilter(window, null, null, null, null, null, null, null, 50)))
                .extracting(Stats.TraceSummary::traceId)
                .containsExactlyInAnyOrder(traceId(1), traceId(2));
    }

    /** An array among the resource attributes reads as JSON, so elements that hold a comma stay apart. */
    @Test
    void aResourceArrayIsAnsweredAsJson() {
        KeyValue args = KeyValue.newBuilder().setKey("process.command_args")
                .setValue(AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(AnyValue.newBuilder().setStringValue("/usr/bin/java"))
                        .addValues(AnyValue.newBuilder().setStringValue("-Dnames=a, b"))))
                .build();
        decoder.ingest(Otlp.traces(Otlp.resource(Otlp.attr("service.name", "orders"), Otlp.attr("process.pid", 42),
                args), Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)));
        flush();

        assertThat(queries.resource("orders"))
                .containsEntry("process.command_args", "[\"/usr/bin/java\",\"-Dnames=a, b\"]")
                .containsEntry("process.pid", "42")
                .containsEntry("service.name", "orders");
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
            decoder.ingest(Otlp.traces(Otlp.service("orders"), root, query));
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"), all));
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
        decoder.ingest(Otlp.traces(Otlp.service("batch-worker"), job, query));
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

    /** A span id is unique within its trace only: a call to a traced service does not hide another trace's call. */
    @Test
    void equalSpanIdsInTwoTracesKeepTheOutboundCallOnTheMap() {
        Span.Builder root = Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 80, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders"));
        Span.Builder toBookstore = Otlp.child(root, spanId(2), "GET", Span.SpanKind.SPAN_KIND_CLIENT,
                NOW + 5, 40, Otlp.attr("http.request.method", "GET"),
                Otlp.attr("url.full", "http://localhost:8081/books"),
                Otlp.attr("server.address", "localhost"), Otlp.attr("server.port", 8081));
        Span.Builder served = Otlp.child(toBookstore, spanId(3), "GET /books", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 6, 30, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/books"));
        decoder.ingest(Otlp.traces(
                Otlp.resourceSpans(Otlp.service("spring-orders"), root, toBookstore),
                Otlp.resourceSpans(Otlp.service("silk-bookstore"), served)));
        // A second trace with the same span ids: its client span calls a host nobody traces.
        Span.Builder other = Otlp.span(traceId(2), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 100, 80, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders"));
        Span.Builder toPayments = Otlp.child(other, spanId(2), "POST", Span.SpanKind.SPAN_KIND_CLIENT,
                NOW + 105, 40, Otlp.attr("http.request.method", "POST"),
                Otlp.attr("url.full", "https://payments.example/charge"),
                Otlp.attr("server.address", "payments.example"), Otlp.attr("server.port", 443));
        decoder.ingest(Otlp.traces(Otlp.service("spring-orders"), other, toPayments));
        flush();

        Stats.ServiceMap map = queries.map(window);

        List<String> edges = map.edges().stream().map(edge -> edge.from() + " -> " + edge.to()).toList();
        assertThat(edges).contains("svc:spring-orders -> svc:silk-bookstore");
        assertThat(edges).anySatisfy(edge -> assertThat(edge)
                .startsWith("svc:spring-orders -> http:").contains("payments.example"));
        assertThat(edges).noneSatisfy(edge -> assertThat(edge).contains("localhost:8081"));
    }

    /**
     * Past twenty thousand outbound spans, the targets called later in the window are still on
     * the map and in the dependencies, and the busiest one counts every call.
     */
    @Test
    void theMapCountsEveryOutboundSpanOfABusyWindow() {
        traceWithQueries(1, 30_000, 20_001);
        Span.Builder charge = Otlp.span(traceId(2), spanId(2), "POST /charge", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 30_000, 20, Otlp.attr("http.request.method", "POST"), Otlp.attr("http.route", "/charge"));
        Span.Builder job = Otlp.span(traceId(3), spanId(3), "archive-events", Span.SpanKind.SPAN_KIND_INTERNAL,
                NOW + 40_000, 900);
        decoder.ingest(Otlp.traces(Otlp.service("orders"), charge,
                Otlp.child(charge, spanId(20), "INSERT payments", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 30_001, 5,
                        Otlp.attr("db.system", "postgresql"), Otlp.attr("db.name", "payments"),
                        Otlp.attr("db.statement", "insert into payments values (?)"))));
        decoder.ingest(Otlp.traces(Otlp.service("batch-worker"), job,
                Otlp.child(job, spanId(30), "DELETE events", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 40_001, 50,
                        Otlp.attr("db.system", "h2"), Otlp.attr("db.name", "worker"),
                        Otlp.attr("db.statement", "delete from events where at < ?"))));
        flush();

        Stats.ServiceMap map = queries.map(window);

        List<String> ids = map.nodes().stream().map(Stats.Node::id).toList();
        assertThat(ids).contains("db:postgresql:payments", "svc:batch-worker", "db:h2:worker");
        assertThat(map.edges()).filteredOn(edge -> edge.to().equals("db:h2"))
                .extracting(Stats.Edge::calls).containsExactly(20_001L);
        assertThat(queries.dependencies("orders", window)).extracting(Stats.Dependency::target)
                .containsExactly("h2", "postgresql:payments");
    }

    /** A call a service makes to its own endpoint is no call to an outside host. */
    @Test
    void aCallToTheServicesOwnPortIsNoExternalHost() {
        Span.Builder job = Otlp.span(traceId(1), spanId(1), "RefreshJob.run", Span.SpanKind.SPAN_KIND_INTERNAL,
                NOW, 300);
        Span.Builder call = Otlp.child(job, spanId(2), "GET", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 5, 200,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("url.full", "http://localhost:8090/api/items"),
                Otlp.attr("server.address", "localhost"), Otlp.attr("server.port", 8090));
        Span.Builder items = Otlp.child(call, spanId(3), "GET /api/items", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 6, 190, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/api/items"));
        decoder.ingest(Otlp.traces(Otlp.service("inventory"), job, call, items,
                Otlp.child(items, spanId(4), "SELECT items", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 7, 20,
                        Otlp.attr("db.system", "h2"), Otlp.attr("db.name", "inventory"),
                        Otlp.attr("db.statement", "select * from items"))));
        flush();

        Stats.ServiceMap map = queries.map(window);

        assertThat(map.nodes()).extracting(Stats.Node::id)
                .containsExactlyInAnyOrder("svc:inventory", "db:h2:inventory");
        assertThat(map.edges()).extracting(edge -> edge.from() + " -> " + edge.to())
                .containsExactly("svc:inventory -> db:h2:inventory");
    }

    /**
     * A health check a service answers is an ignored SERVER span, written with {@code entry}
     * false: no call the service made, and a traced caller's call to it is no external host.
     */
    @Test
    void anIgnoredEndpointIsNoDependencyAndNoHostOnTheMap() {
        Span.Builder[] served = new Span.Builder[4];
        served[0] = Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 10,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders"));
        for (int n = 2; n <= 4; n++) {
            served[n - 1] = Otlp.span(traceId(n), spanId(n), "GET /health", Span.SpanKind.SPAN_KIND_SERVER,
                    NOW + n, 3, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/health"),
                    Otlp.attr("server.address", "localhost"), Otlp.attr("server.port", 8083));
        }
        decoder.ingest(Otlp.traces(Otlp.service("warehouse"), served));
        Span.Builder poll = Otlp.span(traceId(5), spanId(5), "GET", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 5, 5,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("url.full", "http://localhost:8083/health"),
                Otlp.attr("server.address", "localhost"), Otlp.attr("server.port", 8083));
        Span.Builder health = Otlp.child(poll, spanId(6), "GET /health", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 6, 3, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/health"),
                Otlp.attr("server.address", "localhost"), Otlp.attr("server.port", 8083));
        decoder.ingest(Otlp.traces(
                Otlp.resourceSpans(Otlp.service("load-gen"), poll),
                Otlp.resourceSpans(Otlp.service("warehouse"), health)));
        flush();

        assertThat(queries.dependencies("warehouse", window)).isEmpty();
        Stats.ServiceMap map = queries.map(window);
        assertThat(map.nodes()).extracting(Stats.Node::id).noneMatch(id -> id.startsWith("http:"));
        assertThat(map.edges()).extracting(edge -> edge.from() + " -> " + edge.to() + " " + edge.calls())
                .containsExactly("user -> svc:warehouse 1");
    }

    /** A message a service consumes itself still went through the broker: the destination is on the map. */
    @Test
    void aQueueAServiceProducesToAndConsumesFromIsOnTheMap() {
        Span.Builder publish = Otlp.span(traceId(1), spanId(1), "orders publish", Span.SpanKind.SPAN_KIND_PRODUCER,
                NOW, 1_000, Otlp.attr("messaging.system", "kafka"), Otlp.attr("messaging.destination.name", "orders"));
        Span.Builder process = Otlp.child(publish, spanId(2), "orders process", Span.SpanKind.SPAN_KIND_CONSUMER,
                NOW + 5, 20, Otlp.attr("messaging.system", "kafka"), Otlp.attr("messaging.destination.name", "orders"));
        decoder.ingest(Otlp.traces(Otlp.service("events"), publish, process));
        flush();

        Stats.ServiceMap map = queries.map(window);

        assertThat(map.nodes()).extracting(Stats.Node::id)
                .containsExactlyInAnyOrder("svc:events", "messaging:orders");
        assertThat(map.edges()).extracting(edge -> edge.from() + " -> " + edge.to() + " " + edge.calls())
                .containsExactly("svc:events -> messaging:orders 1");
        assertThat(queries.dependencies("events", window)).extracting(Stats.Dependency::target)
                .containsExactly("orders");
    }

    /** A traced load generator's requests are root CLIENT spans to a host nobody traces. */
    @Test
    void aServiceWhoseRequestsAreRootCallsIsOnTheMapWithWhatItCalls() {
        for (int n = 1; n <= 3; n++) {
            decoder.ingest(Otlp.traces(Otlp.service("load-gen"), Otlp.span(traceId(n), spanId(n), "GET",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + n, 30, Otlp.attr("http.request.method", "GET"),
                    Otlp.attr("url.full", "http://untraced.example:9000/x"),
                    Otlp.attr("server.address", "untraced.example"), Otlp.attr("server.port", 9000))));
        }
        flush();

        Stats.ServiceMap map = queries.map(window);

        assertThat(map.nodes()).extracting(Stats.Node::id)
                .contains("svc:load-gen", "http:untraced.example:9000");
        assertThat(map.edges()).extracting(edge -> edge.from() + " -> " + edge.to() + " " + edge.calls())
                .containsExactly("svc:load-gen -> http:untraced.example:9000 3");
        assertThat(queries.dependencies("load-gen", window))
                .extracting(dependency -> dependency.target() + " " + dependency.calls())
                .containsExactly("untraced.example:9000 3");
    }

    @Test
    void errorsGroupOnTheNormalisedMessage() {
        for (int i = 1; i <= 2; i++) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.failing(
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"), Otlp.failing(
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"), root));
        flush();
        // The second service's spans arrive in an export of their own, as they do in life.
        decoder.ingest(Otlp.traces(Otlp.service("bookstore"),
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                        Otlp.attr("enduser.id", "grumpy-badger"))));
        flush();

        assertThat(queries.traces(filterWithQuery("GRUMPY"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("/orders"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("nothing-like-this"))).isEmpty();
    }

    /**
     * A value holding a quote or a backslash is found as it reads, though the attribute JSON
     * holds it escaped, and the JSON text itself is still found.
     */
    @Test
    void theFreeTextSearchFindsAValueTheJsonEscapes() {
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /report", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                        Otlp.attr("file.path", "C:\\Users\\me\\report.csv"),
                        Otlp.attr("db.statement", "select \"ID\" from \"ORDERS\" where \"STATUS\" = ?"))));
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.Report",
                Otlp.log(NOW, 9, "written", traceId(1), spanId(1), Otlp.attr("file.path", "C:\\tmp\\out.csv"))));
        flush();

        assertThat(queries.traces(filterWithQuery("C:\\Users\\me"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("from \"ORDERS\" where"))).hasSize(1);
        assertThat(queries.traces(filterWithQuery("\"file.path\":\"c:"))).hasSize(1);
        assertThat(queries.logs(new Queries.LogFilter(window, null, null, "C:\\tmp", null, null, 50)))
                .extracting(LogRecord::body).containsExactly("written");
    }

    /** A bound past 2,147 ms must not overflow a 32-bit multiplication in SQL. */
    @Test
    void theDurationFilterTakesBoundsOfSeveralSeconds() {
        long[] durations = {1_000, 3_500, 12_000};
        for (int i = 0; i < durations.length; i++) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(i + 1), spanId(i + 1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                            NOW, durations[i])));
        }
        flush();

        assertThat(queries.traces(new Queries.TraceFilter(
                window, null, null, 3_000L, 10_000L, null, null, null, 50)))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
        assertThat(queries.traceTotal(new Queries.TraceFilter(
                window, null, null, 3_000L, null, null, null, null, 50))).isEqualTo(2);
        assertThat(queries.traces(new Queries.TraceFilter(
                window, null, null, null, Long.MAX_VALUE, null, null, null, 50))).hasSize(3);
        assertThat(queries.traces(new Queries.TraceFilter(
                window, null, null, Long.MAX_VALUE, null, null, null, null, 50))).isEmpty();
    }

    private Queries.TraceFilter filterWithQuery(String q) {
        return new Queries.TraceFilter(window, null, null, null, null, null, q, null, 50);
    }

    /** A request on a bucket bound is in the lower bucket, so two neighbouring bars never list it both. */
    @Test
    void theMinimumDurationIsOpenAndTheMaximumClosed() {
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /a", Span.SpanKind.SPAN_KIND_SERVER, NOW, 125),
                Otlp.span(traceId(2), spanId(2), "GET /b", Span.SpanKind.SPAN_KIND_SERVER, NOW, 126)));
        flush();

        assertThat(queries.traces(new Queries.TraceFilter(window, null, null, 125L, null, null, null, null, 50)))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
        assertThat(queries.traces(new Queries.TraceFilter(window, null, null, null, 125L, null, null, null, 50)))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1));
    }

    /**
     * A downstream request and a handled error: by its entry spans a trace is listed where the
     * Scatter draws it and the Response summary counts it, by itself it is not.
     */
    @Test
    void byEntryTheFiltersReadTheRequestsOfTheTrace() {
        // GET /orders, 200 ms, calls the bookstore 50 ms in; the bookstore answers in 30 ms.
        Span.Builder orders = Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 200);
        Span.Builder call = Otlp.child(orders, spanId(2), "GET", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 50, 30);
        Span.Builder books = Otlp.child(call, spanId(3), "GET /books", Span.SpanKind.SPAN_KIND_SERVER, NOW + 50, 30);
        decoder.ingest(Otlp.traces(Otlp.service("orders"), orders, call));
        decoder.ingest(Otlp.traces(Otlp.service("bookstore"), books));
        // GET /fast answers in 20 ms, after a statement under it failed and was handled.
        Span.Builder fast = Otlp.span(traceId(2), spanId(4), "GET /fast", Span.SpanKind.SPAN_KIND_SERVER,
                NOW + 1_000, 20);
        decoder.ingest(Otlp.traces(Otlp.service("orders"), fast, Otlp.failing(
                Otlp.child(fast, spanId(5), "INSERT", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1_005, 5),
                "SQLException", "duplicate key", "at Orders.save")));
        flush();

        // The rectangle around the bookstore point, and its Response summary's first bar.
        Window rectangle = Window.of(NOW + 40, NOW + 60);
        assertThat(queries.traces(byEntry(rectangle, null, 29L, 31L, null))).extracting(Stats.TraceSummary::traceId)
                .containsExactly(traceId(1));
        assertThat(queries.traces(new Queries.TraceFilter(rectangle, null, null, 29L, 31L, null, null, null, 50)))
                .isEmpty();
        assertThat(queries.traces(byEntry(window, "bookstore", null, 125L, "ok")))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1));
        assertThat(queries.traces(byEntry(window, "orders", 125L, 500L, "ok")))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1));
        // The handled error: its request is in the first bar, and not behind the error bar.
        assertThat(queries.traces(byEntry(window, "orders", null, 125L, "ok")))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
        assertThat(queries.traceTotal(byEntry(window, "orders", null, null, "error"))).isZero();
        assertThat(queries.traces(new Queries.TraceFilter(window, "orders", null, null, null, "error", null, null, 50)))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
    }

    private Queries.TraceFilter byEntry(Window in, String service, Long minMs,
            Long maxMs, String status) {
        return new Queries.TraceFilter(in, service, null, minMs, maxMs, status, null, null, null, 50, true);
    }

    @Test
    void tracesThatStartInTheSameMillisecondPageByTheirTraceId() {
        for (int i = 1; i <= 5; i++) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
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

    /** The slowest traces of a query are those of its slowest calls, each with that call's duration. */
    @Test
    void theSlowestTracesOfAGroupCarryTheSpanTheyAreOrderedBy() {
        // Trace 1 lasts 3 s around an 80 ms query; trace 2 lasts 900 ms around an 850 ms one.
        long[][] shapes = {{3_000, 80}, {900, 850}};
        for (int n = 1; n <= 2; n++) {
            Span.Builder root = Otlp.span(traceId(n), spanId(10 * n), "GET /report", Span.SpanKind.SPAN_KIND_SERVER,
                    NOW, shapes[n - 1][0]);
            decoder.ingest(Otlp.traces(Otlp.service("orders"), root,
                    Otlp.child(root, spanId(10 * n + 1), "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT, NOW,
                            shapes[n - 1][1], Otlp.attr("db.system", "h2"),
                            Otlp.attr("db.statement", "select * from orders"))));
        }
        flush();
        String queryId = queries.queries(window, null, "total", 10, null).get(0).queryId();

        assertThat(queries.slowestContaining(window, Queries.SpanMatch.query(queryId), 20))
                .extracting(matched -> matched.trace().traceId(), Queries.MatchedTrace::matchMs,
                        matched -> matched.trace().durationMs())
                .containsExactly(tuple(traceId(2), 850.0, 900.0), tuple(traceId(1), 80.0, 3_000.0));
    }

    /**
     * A request inside the window whose trace began before it, under a job running since then, is
     * evidence of its group as it is counted in it: the matching span bounds the read, not the trace.
     */
    @Test
    void aRequestInTheWindowIsEvidenceWhenItsTraceStartedBeforeIt() {
        Span.Builder job = Otlp.span(traceId(1), spanId(1), "nightly", Span.SpanKind.SPAN_KIND_INTERNAL,
                NOW - 120_000, 200_000);
        Span.Builder call = Otlp.child(job, spanId(2), "GET", Span.SpanKind.SPAN_KIND_CLIENT, NOW, 950,
                Otlp.attr("http.request.method", "GET"), Otlp.attr("server.address", "localhost"));
        Span.Builder request = Otlp.child(call, spanId(3), "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 900, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders/{id}"));
        decoder.ingest(Otlp.traces(Otlp.service("orders"), job, call, request,
                Otlp.child(request, spanId(4), "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT, NOW, 800,
                        Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select * from orders"))));
        flush();
        Queries.SpanMatch endpoint = Queries.SpanMatch.endpoint(Ids.endpointId("orders", "GET /orders/{id}"));

        assertThat(queries.slowestContaining(window, endpoint, 20))
                .extracting(matched -> matched.trace().traceId(), Queries.MatchedTrace::matchMs)
                .containsExactly(tuple(traceId(1), 900.0));
        assertThat(queries.tracesContaining(window, endpoint, 20, Queries.TraceOrder.NEWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1));
        assertThat(queries.tracesAmong(window, List.of(traceId(1)), 3))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1));
        assertThat(queries.timeSplit(window, "orders", endpoint, List.of(traceId(1))).breakdown().get("db"))
                .isCloseTo(800 / 900.0, within(1e-9));
    }

    /** Traces tied on duration or start come back in trace id order, whatever H2 scanned first. */
    @Test
    void tracesTiedOnTheOrderingColumnAreOrderedByTheirId() {
        for (int n : new int[] {3, 1, 2}) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(n), spanId(n), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)));
        }
        flush();

        assertThat(queries.tracesAmong(window, List.of(traceId(3), traceId(1), traceId(2)), 2))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(3), traceId(1));
        assertThat(queries.tracesContaining(window, Queries.SpanMatch.named("GET /orders"), 2,
                Queries.TraceOrder.SLOWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
        assertThat(queries.tracesContaining(window, Queries.SpanMatch.named("GET /orders"), 2,
                Queries.TraceOrder.NEWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
    }

    /**
     * A trace of {@code queries} one-millisecond statements, one after another, under a root of {@code rootMs},
     * the root exported last as a server span ends last, so a read cut short misses it first.
     */
    private void traceWithQueries(int n, long rootMs, int queries) {
        Span.Builder root = Otlp.span(traceId(n), spanId(n), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, rootMs, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/orders"));
        List<Span.Builder> spans = new ArrayList<>();
        for (int i = 0; i < queries; i++) {
            spans.add(Otlp.child(root, "%016x".formatted(n * 1_000_000L + i + 1), "SELECT orders",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 1,
                    Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select * from orders where id = ?")));
        }
        spans.add(root);
        decoder.ingest(Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])));
    }

    /**
     * A sample whose spans pass the cap is read whole trace by whole trace: the second
     * trace is left out rather than read in part, so the shares describe the first.
     */
    @Test
    void aTimeSplitReadsWholeTracesAndStopsBeforeTheCap() {
        traceWithQueries(1, 30_000, 14_999);
        traceWithQueries(2, 10_000, 9_999);
        flush();

        Queries.TimeSplit split = queries.timeSplit(window, "orders",
                Queries.SpanMatch.endpoint(Ids.endpointId("orders", "GET /orders")),
                List.of(traceId(1), traceId(2)));

        assertThat(split.breakdown().get("db")).isCloseTo(14_999 / 30_000.0, within(1e-9));
        assertThat(split.breakdown().get("self")).isCloseTo(15_001 / 30_000.0, within(1e-9));
        assertThat(split.hotSpans().get(0).count()).isEqualTo(14_999);
    }

    /** Siblings that start in the same nanosecond are listed by span id, whatever order they arrived in. */
    @Test
    void spansTiedOnTheirStartAreOrderedByTheirSpanId() {
        Span.Builder root = Otlp.span(traceId(1), spanId(1), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 50);
        Span.Builder later = Otlp.child(root, spanId(0x20), "SELECT b", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 5);
        Span.Builder earlier = Otlp.child(root, spanId(0x10), "SELECT a", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 5);
        decoder.ingest(Otlp.traces(Otlp.service("orders"), later, root, earlier));
        flush();

        Queries.TraceDetail trace = queries.trace(traceId(1));

        assertThat(trace).isNotNull();
        assertThat(trace.spans()).extracting(net.benelog.spidersense.store.SpanRecord::spanId)
                .containsExactly(spanId(1), spanId(0x10), spanId(0x20));
    }

    /**
     * Three rows read in trace id order sort the same with or without the tie-break; enough tied
     * rows that H2's partial sort for the LIMIT reorders them do not, and the tie-break is what
     * keeps a finding's evidence traces the lowest ids.
     */
    @Test
    void manyTracesTiedOnTheOrderingColumnAreCutByTheirId() {
        for (int n = 40; n >= 1; n--) {
            decoder.ingest(Otlp.traces(Otlp.service("orders"),
                    Otlp.span(traceId(n), spanId(n), "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)));
        }
        flush();

        List<String> lowest = List.of(traceId(1), traceId(2), traceId(3));
        assertThat(queries.tracesContaining(window, Queries.SpanMatch.named("GET /orders"), 3,
                Queries.TraceOrder.SLOWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactlyElementsOf(lowest);
        assertThat(queries.tracesContaining(window, Queries.SpanMatch.named("GET /orders"), 3,
                Queries.TraceOrder.NEWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactlyElementsOf(lowest);
    }

    @Test
    void logsWrittenInTheSameMillisecondPageByTheirId() {
        io.opentelemetry.proto.logs.v1.LogRecord[] burst = new io.opentelemetry.proto.logs.v1.LogRecord[5];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = Otlp.log(NOW, 17, "line " + i, null, null);
        }
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "o.e.Orders", burst));
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
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.Job",
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
        decoder.ingest(Otlp.logs(Otlp.service("orders"), "orders.web.OrderController",
                Otlp.log(NOW, 17, "Payment gateway timeout", null, null)));
        flush();

        List<LogRecord> rows = queries.logs(new Queries.LogFilter(
                window, null, "ERROR", "orders.web.OrderController", null, null, 50));

        assertThat(rows).extracting(LogRecord::body).containsExactly("Payment gateway timeout");
    }

    @Test
    void bucketsLineUpWithTheWindowAndCountWhatFellInThem() {
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                Otlp.span(traceId(1), spanId(1), "GET /old", Span.SpanKind.SPAN_KIND_SERVER, old, 5)));
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
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
        decoder.ingest(Otlp.traces(Otlp.service("orders"),
                late, Otlp.child(late, spanId(11), "lookup", Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 65_000, 5),
                early, Otlp.child(early, spanId(12), "lookup", Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 1_000, 5)));
        flush();

        assertThat(queries.tracesContaining(window, Queries.SpanMatch.named("lookup"), 10,
                Queries.TraceOrder.NEWEST))
                .as("the span of the first trace starts after the window ends")
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(2));
        assertThat(queries.tracesContaining(Window.of(NOW - 60_000, NOW + 70_000),
                Queries.SpanMatch.named("lookup"), 10, Queries.TraceOrder.NEWEST))
                .extracting(Stats.TraceSummary::traceId).containsExactly(traceId(1), traceId(2));
    }
}
