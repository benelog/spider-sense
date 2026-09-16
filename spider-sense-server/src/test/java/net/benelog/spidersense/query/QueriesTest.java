package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Ids;
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
                Otlp.span(traceId(3), spanId(3), "GET /health", Span.SpanKind.SPAN_KIND_SERVER,
                        NOW, 1, Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/*"))));
        flush();

        List<Stats.EndpointStats> endpoints = queries.endpoints(window, null, null);

        assertThat(endpoints).extracting(Stats.EndpointStats::name)
                .containsExactlyInAnyOrder("GET /orders/{id}", "GET /health");
        Stats.EndpointStats orders = endpoints.stream()
                .filter(e -> e.name().equals("GET /orders/{id}")).findFirst().orElseThrow();
        assertThat(orders.calls()).isEqualTo(2);
        assertThat(orders.totalMs()).isEqualTo(40.0);
        assertThat(orders.avgMs()).isEqualTo(20.0);
        assertThat(orders.statusCodes()).containsEntry("200", 1L).containsEntry("500", 1L);
        assertThat(orders.endpointId()).isEqualTo(Ids.endpointId("orders", "GET /orders/{id}"));
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
}
