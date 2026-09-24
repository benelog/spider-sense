package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.store.Batch;
import net.benelog.spidersense.store.IgnoredEndpoints;
import net.benelog.spidersense.store.IngestCap;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Store;

class OtlpDecoderTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String ROOT = "00f067aa0ba902b7";
    private static final String CHILD = "00f067aa0ba902b8";

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void decodesAServerSpanAndItsDatabaseChild() {
        Span.Builder root = Otlp.span(TRACE, ROOT, "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                1_700_000_000_000L, 152,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        Span.Builder child = Otlp.child(root, CHILD, "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT,
                1_700_000_000_010L, 200,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from orders where id = ?"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "orders"));

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("spring-orders"), root, child));

        assertThat(batch.spans()).hasSize(2);
        SpanRecord server = batch.spans().get(0);
        assertThat(server.traceId()).isEqualTo(TRACE);
        assertThat(server.spanId()).isEqualTo(ROOT);
        assertThat(server.parentSpanId()).isNull();
        assertThat(server.kind()).isEqualTo("SERVER");
        assertThat(server.service()).isEqualTo("spring-orders");
        assertThat(server.scope()).isEqualTo(Otlp.SCOPE);
        assertThat(server.endpointName()).isEqualTo("GET /orders/{id}");
        assertThat(server.durationMillis()).isEqualTo(152.0);

        SpanRecord query = batch.spans().get(1);
        assertThat(query.parentSpanId()).isEqualTo(ROOT);
        assertThat(query.dbStatement()).isEqualTo("select * from orders where id = ?");
        assertThat(query.category()).isEqualTo("db");
        // 200 ms over the 100 ms threshold: one slow-query tingle, and nothing for the fast root.
        assertThat(batch.tingles()).hasSize(1);
    }

    @Test
    void decodesTheOlderGenerationOfAttributesToo() {
        Span.Builder root = Otlp.span(TRACE, ROOT, "GET", Span.SpanKind.SPAN_KIND_SERVER,
                1_700_000_000_000L, 5,
                Otlp.attr("http.method", "GET"),
                Otlp.attr("http.target", "/orders"),
                Otlp.attr("http.status_code", 404));

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("legacy"), root));

        SpanRecord span = batch.spans().get(0);
        assertThat(span.httpMethod()).isEqualTo("GET");
        assertThat(span.urlPath()).isEqualTo("/orders");
        assertThat(span.httpStatus()).isEqualTo(404L);
    }

    @Test
    void aResourceWithoutAServiceNameFallsBackToUnknownService() {
        Span.Builder root = Otlp.span(TRACE, ROOT, "GET", Span.SpanKind.SPAN_KIND_SERVER, 1, 1);

        Batch batch = decoder.accept(Otlp.traces(Otlp.resource(), root));

        assertThat(batch.spans().get(0).service()).isEqualTo(OtlpDecoder.UNKNOWN_SERVICE);
    }

    @Test
    void serverSpansOnOurOwnPortAreDroppedSoTheUiNeverMonitorsItself() {
        Span.Builder ours = Otlp.span(TRACE, ROOT, "GET /api/overview", Span.SpanKind.SPAN_KIND_SERVER,
                1_700_000_000_000L, 5, Otlp.attr("server.port", 4000));
        Span.Builder theirs = Otlp.span(TRACE, CHILD, "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                1_700_000_000_000L, 5, Otlp.attr("server.port", 8081));

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("silk-bookstore"), ours, theirs));

        assertThat(batch.spans()).hasSize(1);
        assertThat(batch.spans().get(0).spanId()).isEqualTo(CHILD);
    }

    @Test
    void aClientCallToOurOwnPortIsRealWorkAndStaysInItsTrace() {
        // An application posting OTLP to us, or a script reading the API: that call
        // happened, and dropping it would leave a hole in the caller's trace.
        Span.Builder caller = Otlp.span(TRACE, ROOT, "POST", Span.SpanKind.SPAN_KIND_CLIENT,
                1_700_000_000_000L, 5,
                Otlp.attr("http.request.method", "POST"),
                Otlp.attr("url.full", "http://localhost:4000/v1/traces"),
                Otlp.attr("server.port", 4000));

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("silk-bookstore"), caller));

        assertThat(batch.spans()).hasSize(1);
        assertThat(batch.spans().get(0).kind()).isEqualTo("CLIENT");
    }

    @Test
    void anExceptionEventBecomesAnErrorWithItsStacktrace() {
        Span.Builder failing = Otlp.failing(
                Otlp.span(TRACE, ROOT, "POST /orders", Span.SpanKind.SPAN_KIND_SERVER, 1, 1),
                "java.lang.IllegalStateException", "Order 42 is already shipped", "at Orders.ship(..)");

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("spring-orders"), failing));

        SpanRecord span = batch.spans().get(0);
        assertThat(span.isError()).isTrue();
        assertThat(span.status()).isEqualTo("ERROR");
        assertThat(span.errorType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(span.stacktrace()).isEqualTo("at Orders.ship(..)");
    }

    @Test
    void decodesGaugeAndHistogramMetrics() {
        decoder.accept(Otlp.gauge(Otlp.service("spring-orders"), "jvm.memory.used", "By",
                1_700_000_000_000L, 1024, Otlp.attr("jvm.memory.type", "heap")));
        Batch batch = decoder.accept(Otlp.histogram(Otlp.service("spring-orders"), "jvm.gc.duration",
                1_700_000_000_000L, 4, 0.2, new double[]{0.01, 0.1}, new long[]{2, 1, 1},
                Otlp.attr("jvm.gc.name", "G1 Young Generation")));

        assertThat(batch.metrics()).hasSize(1);
        Batch.MetricSample sample = batch.metrics().get(0);
        assertThat(sample.type()).isEqualTo("histogram");
        assertThat(sample.temporality()).isEqualTo("CUMULATIVE");
        assertThat(sample.point().count()).isEqualTo(4);
        assertThat(sample.point().hasBuckets()).isTrue();
    }

    @Test
    void decodesLogsWithTheSeverityTableAndTheScopeAsLogger() {
        Batch batch = decoder.accept(Otlp.logs(Otlp.service("spring-orders"), "o.s.boot.StartupInfoLogger",
                Otlp.log(1_700_000_000_000L, 9, "Started in 2.1 seconds", TRACE, ROOT),
                Otlp.log(1_700_000_000_001L, 17, "Boom", null, null)));

        assertThat(batch.logs()).hasSize(2);
        assertThat(batch.logs().get(0).severity()).isEqualTo("INFO");
        assertThat(batch.logs().get(0).logger()).isEqualTo("o.s.boot.StartupInfoLogger");
        assertThat(batch.logs().get(0).traceId()).isEqualTo(TRACE);
        assertThat(batch.logs().get(1).severity()).isEqualTo("ERROR");
        assertThat(batch.logs().get(1).traceId()).isNull();
    }

    /**
     * The extension's index catalog rides in on a log record and is not one
     * (storage.adoc, api.adoc): it describes the schema, not a moment.
     */
    @Test
    void aSchemaRecordBecomesACatalogRowAndNeverALogLine() {
        String indexes = "[{\"name\":\"PRIMARY_KEY_8\",\"unique\":true,\"columns\":[\"ID\"]}]";

        Batch batch = decoder.accept(Otlp.logs(Otlp.service("spring-orders"), "spider-sense",
                Otlp.log(1_700_000_000_000L, 9, "index catalog of ITEMS", null, null,
                        Otlp.attr("spidersense.schema.table", "ITEMS"),
                        Otlp.attr("spidersense.schema.schema", "PUBLIC"),
                        Otlp.attr("spidersense.schema.product", "H2"),
                        Otlp.attr("spidersense.schema.indexes", indexes))));

        assertThat(batch.logs()).isEmpty();
        assertThat(batch.catalogs()).hasSize(1);
        Batch.Catalog row = batch.catalogs().get(0);
        assertThat(row.service()).isEqualTo("spring-orders");
        assertThat(row.schemaName()).isEqualTo("PUBLIC");
        assertThat(row.table()).isEqualTo("ITEMS");
        assertThat(row.product()).isEqualTo("H2");
        assertThat(row.indexes()).isEqualTo(indexes);
        assertThat(row.at()).isEqualTo(1_700_000_000_000L);
        assertThat(batch.records()).as("it is a record like any other for the flush counters")
                .isEqualTo(1);

        store.writer().awaitIdle(5_000);
        assertThat(store.sql().count("SELECT COUNT(*) FROM log", List.of())).isZero();
        assertThat(store.sql().count("SELECT COUNT(*) FROM db_table", List.of())).isEqualTo(1);

        // The same table again, looked up after a restart: one row, the newer one.
        decoder.accept(Otlp.logs(Otlp.service("spring-orders"), "spider-sense",
                Otlp.log(1_700_000_100_000L, 9, "index catalog of ITEMS", null, null,
                        Otlp.attr("spidersense.schema.table", "ITEMS"),
                        Otlp.attr("spidersense.schema.schema", "PUBLIC"),
                        Otlp.attr("spidersense.schema.indexes", "[]"))));
        store.writer().awaitIdle(5_000);

        assertThat(store.sql().query("SELECT indexes, seen_ms FROM db_table", List.of(),
                rs -> rs.getString(1) + " @ " + rs.getLong(2)))
                .containsExactly("[] @ 1700000100000");
    }

    /** A schema record without the optional attributes still says what it knows. */
    @Test
    void aCatalogRowWithoutASchemaOrIndexesIsStillARow() {
        Batch batch = decoder.accept(Otlp.logs(Otlp.service("spring-orders"), "spider-sense",
                Otlp.log(1_700_000_000_000L, 9, "index catalog of items", null, null,
                        Otlp.attr("spidersense.schema.table", "items"))));

        assertThat(batch.catalogs()).singleElement().satisfies(row -> {
            assertThat(row.schemaName()).isEmpty();
            assertThat(row.product()).isNull();
            assertThat(row.indexes()).isEqualTo("[]");
        });
    }

    @Test
    void protobufAndTheTwoJsonIdEncodingsAllDecodeToTheSameSpan() throws Exception {
        ExportTraceServiceRequest request = Otlp.traces(Otlp.service("spring-orders"),
                Otlp.span(TRACE, ROOT, "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, 1_700_000_000_000L,
                        5, Otlp.attr("http.route", "/orders")));

        // OTLP/JSON as the specification writes it: hex ids.
        String hexJson = """
                {"resourceSpans":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"stringValue":"spring-orders"}}]},
                 "scopeSpans":[{"scope":{"name":"io.opentelemetry.test"},"spans":[
                  {"traceId":"%s","spanId":"%s","name":"GET /orders","kind":2,
                   "startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000005000000000",
                   "attributes":[{"key":"http.route","value":{"stringValue":"/orders"}}]}]}]}]}
                """.formatted(TRACE, ROOT);
        // Protobuf's own JSON mapping: bytes are base64.
        String base64Json = hexJson
                .replace(TRACE, java.util.Base64.getEncoder()
                        .encodeToString(java.util.HexFormat.of().parseHex(TRACE)))
                .replace(ROOT, java.util.Base64.getEncoder()
                        .encodeToString(java.util.HexFormat.of().parseHex(ROOT)));

        for (String json : List.of(hexJson, base64Json)) {
            ExportTraceServiceRequest.Builder parsed = ExportTraceServiceRequest.newBuilder();
            OtlpJson.merge(json, parsed);
            Batch batch = decoder.accept(parsed.build());

            assertThat(batch.spans()).hasSize(1);
            assertThat(batch.spans().get(0).traceId()).isEqualTo(TRACE);
            assertThat(batch.spans().get(0).spanId()).isEqualTo(ROOT);
            assertThat(batch.spans().get(0).kind()).isEqualTo("SERVER");
        }
        assertThat(request.getResourceSpansCount()).isEqualTo(1);
    }

    /**
     * A uint64 written as a JSON number past 2^63 is legal OTLP that Spider Silk's parser
     * refuses; the fallback through protobuf's own parser must still read the ids as hex.
     */
    @Test
    void hexIdsAreHexWhenOnlyProtobufsParserReadsTheDocument() throws Exception {
        String json = """
                {"resourceSpans":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"stringValue":"spring-orders"}}]},
                 "scopeSpans":[{"spans":[
                  {"traceId":"%s","spanId":"%s","name":"GET /orders","kind":2,
                   "startTimeUnixNano":1700000000000000000,"endTimeUnixNano":18446744073709551615}]}]}]}
                """.formatted(TRACE, ROOT);
        ExportTraceServiceRequest.Builder parsed = ExportTraceServiceRequest.newBuilder();
        OtlpJson.merge(json, parsed);

        Batch batch = decoder.accept(parsed.build());

        assertThat(batch.spans()).singleElement().satisfies(span -> {
            assertThat(span.traceId()).isEqualTo(TRACE);
            assertThat(span.spanId()).isEqualTo(ROOT);
        });
    }

    /**
     * Protobuf's JSON parser also accepts the proto field names, so a hex id under
     * {@code trace_id} must be read as hex too, not as 24 bytes of base64.
     */
    @Test
    void snakeCaseJsonIdsAreHexToo() throws Exception {
        String json = """
                {"resource_spans":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"string_value":"spring-orders"}}]},
                 "scope_spans":[{"spans":[
                  {"trace_id":"%s","span_id":"%s","parent_span_id":"%s","name":"SELECT orders","kind":3,
                   "start_time_unix_nano":"1700000000000000000","end_time_unix_nano":"1700000005000000000"}]}]}]}
                """.formatted(TRACE, CHILD, ROOT);
        ExportTraceServiceRequest.Builder parsed = ExportTraceServiceRequest.newBuilder();
        OtlpJson.merge(json, parsed);

        Batch batch = decoder.accept(parsed.build());

        assertThat(batch.spans()).singleElement().satisfies(span -> {
            assertThat(span.traceId()).isEqualTo(TRACE);
            assertThat(span.spanId()).isEqualTo(CHILD);
            assertThat(span.parentSpanId()).isEqualTo(ROOT);
        });
        store.writer().awaitIdle(5_000);
        assertThat(store.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(1);
    }

    /**
     * Decoding is total: a span without a valid trace or span id is skipped, and
     * the rest of its export is still stored (api.adoc#status-and-ingest).
     */
    @Test
    void aSpanWithoutValidIdsIsSkippedAndTheRestOfTheExportIsStored() {
        Span.Builder good = Otlp.span(TRACE, ROOT, "GET /orders", Span.SpanKind.SPAN_KIND_SERVER,
                1_700_000_000_000L, 5, Otlp.attr("http.route", "/orders"));
        Span.Builder noTraceId = Otlp.span(TRACE, CHILD, "no trace id", Span.SpanKind.SPAN_KIND_INTERNAL,
                1_700_000_000_000L, 5).clearTraceId();
        Span.Builder noSpanId = Otlp.span(TRACE, CHILD, "no span id", Span.SpanKind.SPAN_KIND_INTERNAL,
                1_700_000_000_000L, 5).clearSpanId();
        Span.Builder longTraceId = Otlp.span(TRACE + "00000000", CHILD, "a 20-byte trace id",
                Span.SpanKind.SPAN_KIND_INTERNAL, 1_700_000_000_000L, 5);
        Span.Builder zeroSpanId = Otlp.span(TRACE, "0000000000000000", "an all-zero span id",
                Span.SpanKind.SPAN_KIND_INTERNAL, 1_700_000_000_000L, 5);
        Span.Builder badParent = Otlp.span(TRACE, CHILD, "a 4-byte parent", Span.SpanKind.SPAN_KIND_INTERNAL,
                1_700_000_000_000L, 5).setParentSpanId(Otlp.id("00f067aa"));

        Batch batch = decoder.accept(Otlp.traces(Otlp.service("spring-orders"),
                good, noTraceId, noSpanId, longTraceId, zeroSpanId, badParent));

        assertThat(batch.spans()).extracting(SpanRecord::name)
                .containsExactly("GET /orders", "a 4-byte parent");
        assertThat(batch.spans().get(1).parentSpanId()).as("an invalid parent is none").isNull();
        store.writer().awaitIdle(5_000);
        assertThat(store.sql().count("SELECT COUNT(*) FROM span", List.of())).isEqualTo(2);
    }

    /** A log line's invalid ids are read as none, and the line itself is kept. */
    @Test
    void aLogWithInvalidIdsIsKeptUncorrelated() {
        var wrongLength = Otlp.log(1_700_000_000_000L, 17, "wrong length", null, null).toBuilder()
                .setTraceId(Otlp.id(TRACE + "0000")).setSpanId(Otlp.id(ROOT + "00")).build();
        var zeros = Otlp.log(1_700_000_000_001L, 17, "all zeros", "0".repeat(32), "0".repeat(16));

        Batch batch = decoder.accept(Otlp.logs(Otlp.service("worker"), "worker.Jobs", wrongLength, zeros));

        assertThat(batch.logs()).hasSize(2).allSatisfy(log -> {
            assertThat(log.traceId()).isNull();
            assertThat(log.spanId()).isNull();
        });
        store.writer().awaitIdle(5_000);
        assertThat(store.sql().count("SELECT COUNT(*) FROM log", List.of())).isEqualTo(2);
    }

    // --- the ingest cap (storage.adoc#ingest-cap) -------------------------------------

    private static Span.Builder capSpan(int n, long at) {
        return Otlp.span("%032x".formatted(n), "%016x".formatted(n), "GET /orders",
                Span.SpanKind.SPAN_KIND_SERVER, at, 5, Otlp.attr("http.route", "/orders"));
    }

    /** A store whose cap runs on a clock the test holds still, so one export is one second. */
    private static Store capped(Long maxSpansPerSecond, AtomicLong clock) {
        return new Store(TestStore.memoryUrl(), null, 24, 500, 100, null,
                IgnoredEndpoints.DEFAULT, 0, new IngestCap(maxSpansPerSecond, clock::get));
    }

    @Test
    void aBurstAboveTheCapKeepsWholeTracesAndCountsTheRest() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        try (Store capped = capped(10L, clock)) {
            OtlpDecoder capping = new OtlpDecoder(capped, () -> 4000);
            Span.Builder[] burst = new Span.Builder[30];
            for (int n = 0; n < burst.length; n++) {
                burst[n] = capSpan(n + 1, clock.get());
            }

            Batch first = capping.accept(Otlp.traces(Otlp.service("orders"), burst));

            assertThat(first.spans()).as("the tenth span of the second is the last one kept")
                    .hasSize(10);
            assertThat(capped.droppedSpans()).isEqualTo(20);
            assertThat(first.spans()).extracting(SpanRecord::traceId)
                    .containsExactlyElementsOf(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).stream()
                            .map("%032x"::formatted).toList());

            Span.Builder[] more = new Span.Builder[10];
            for (int n = 0; n < more.length; n++) {
                more[n] = Otlp.child(capSpan(n + 1, clock.get()), "%016x".formatted(100 + n),
                        "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT, clock.get(), 3,
                        Otlp.attr("db.system", "h2"), Otlp.attr("db.statement", "select 1"));
            }

            Batch second = capping.accept(Otlp.traces(Otlp.service("orders"), more));

            assertThat(second.spans()).as("a trace already being stored stays complete").hasSize(10);
            assertThat(capped.droppedSpans()).isEqualTo(20);
        }
    }

    @Test
    void withNoCapNothingIsDropped() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        try (Store uncapped = capped(null, clock)) {
            OtlpDecoder plain = new OtlpDecoder(uncapped, () -> 4000);
            Span.Builder[] burst = new Span.Builder[30];
            for (int n = 0; n < burst.length; n++) {
                burst[n] = capSpan(n + 1, clock.get());
            }

            assertThat(plain.accept(Otlp.traces(Otlp.service("orders"), burst)).spans()).hasSize(30);
            assertThat(uncapped.droppedSpans()).isZero();
        }
    }
}
