package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.Ids;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonValue;
import net.benelog.silkjson.JsonObject;
import net.benelog.silkjson.JsonArray;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * The contract in api.adoc, exercised through the real request path.
 *
 * <p>Ingest is write-behind, so the tests' server is configured with
 * {@code --await-writes} and the OTLP handler flushes before it answers. Without it a POST
 * followed by a GET would be a race against the writer thread rather than a test.
 */
class ApiTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String ROOT = "00f067aa0ba902b7";
    private static final String CHILD = "00f067aa0ba902b8";
    private static final String FAILING_TRACE = "4bf92f3577b34da6a3ce929d0e0e4737";
    private static final String PROTOBUF = "application/x-protobuf";

    private interface Body {
        void run(TestClient client, SpiderSenseServer.Assembly assembly);
    }

    /** One assembled server per test, on an in-memory database of its own. */
    private static void serve(Body body) {
        Config config = TestStore.config("--await-writes");
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(config);
        try {
            WebTest.test(assembly.app(), client -> body.run(client, assembly));
        } finally {
            assembly.store().close();
        }
    }

    private static HttpResponse<String> postProtobuf(TestClient client, String path, byte[] body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", PROTOBUF)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private static HttpResponse<String> postGzippedProtobuf(TestClient client, String path, byte[] body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", PROTOBUF)
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Otlp.gzip(body))));
    }

    private static JsonObject json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body()).asObject();
    }

    /** A JSON array of counts, as a list one assertion can compare. */
    private static List<Long> counts(JsonArray array) {
        List<Long> values = new ArrayList<>();
        for (JsonValue value : array) {
            values.add(value.asLong());
        }
        return values;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonValue value : array) {
            values.add(value.asString());
        }
        return values;
    }

    /** The node of a service map with this id, or null. */
    private static JsonObject node(JsonObject map, String id) {
        for (JsonValue value : map.getArray("nodes")) {
            if (value.asObject().getString("id").equals(id)) {
                return value.asObject();
            }
        }
        return null;
    }

    /** A server span, a slow database child, and a second trace that failed. */
    private static byte[] sampleTraces() {
        Span.Builder root = Otlp.span(TRACE, ROOT, "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 152,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200),
                Otlp.attr("server.port", 8082));
        Span.Builder query = Otlp.child(root, CHILD, "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT,
                NOW + 10, 200,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from orders where name like ?"),
                Otlp.attr("db.name", "orders"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "orders"));
        Span.Builder failing = Otlp.failing(
                Otlp.span(FAILING_TRACE, "00f067aa0ba902b9", "POST /orders/{id}/ship",
                        Span.SpanKind.SPAN_KIND_SERVER, NOW + 100, 600,
                        Otlp.attr("http.request.method", "POST"),
                        Otlp.attr("http.route", "/orders/{id}/ship"),
                        Otlp.attr("http.response.status_code", 500)),
                "java.lang.IllegalStateException", "Order 42 is already shipped", "at Orders.ship(..)");
        return Otlp.traces(Otlp.service("spring-orders"), root, query, failing).toByteArray();
    }

    private static String windowQuery() {
        return "?from=" + (NOW - 60_000) + "&to=" + (NOW + 60_000);
    }

    @Test
    void ingestAnswersWithAnEmptyResponseInTheRequestsOwnEncoding() {
        serve((client, assembly) -> {
            HttpResponse<String> protobuf = postProtobuf(client, "/v1/traces", sampleTraces());
            assertThat(protobuf.statusCode()).isEqualTo(200);
            assertThat(protobuf.headers().firstValue("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith(PROTOBUF));

            HttpResponse<String> gzipped = postGzippedProtobuf(client, "/v1/traces", sampleTraces());
            assertThat(gzipped.statusCode()).isEqualTo(200);

            HttpResponse<String> asJson = client.send(request -> request
                    .uri(URI.create(client.url("/v1/traces")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"resourceSpans\":[]}")));
            assertThat(asJson.statusCode()).isEqualTo(200);
            assertThat(asJson.body()).isEqualTo("{}");
        });
    }

    /**
     * OTLP asks a partially accepted export to say so: the spans the ingest cap drops are
     * answered as rejected in a partial success, in the request's own encoding.
     */
    @Test
    void anExportTheIngestCapCutsIsAnsweredWithAPartialSuccess() throws Exception {
        String json = JsonFormat.printer().print(tenTraces(0));
        byte[] protobuf = tenTraces(10).toByteArray();
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(
                TestStore.config("--await-writes", "--ingest.max-spans-per-second=1"));
        try {
            WebTest.test(assembly.app(), client -> {
                long dropped = assembly.store().droppedSpans();
                HttpResponse<String> asJson = client.send(request -> request
                        .uri(URI.create(client.url("/v1/traces")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)));

                assertThat(asJson.statusCode()).isEqualTo(200);
                JsonObject partial = Json.parse(asJson.body()).asObject().getObject("partialSuccess");
                long rejected = Long.parseLong(partial.getString("rejectedSpans"));
                assertThat(rejected).as("at most one span a second is kept").isGreaterThanOrEqualTo(8)
                        .isEqualTo(assembly.store().droppedSpans() - dropped);
                assertThat(partial.getString("errorMessage")).contains("spidersense.ingest.max-spans-per-second");

                long droppedBefore = assembly.store().droppedSpans();
                HttpResponse<byte[]> asProtobuf = client.send(request -> request
                        .uri(URI.create(client.url("/v1/traces")))
                        .header("Content-Type", PROTOBUF)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(protobuf)),
                        HttpResponse.BodyHandlers.ofByteArray());

                assertThat(asProtobuf.statusCode()).isEqualTo(200);
                assertThat(rejectedSpans(asProtobuf.body())).isGreaterThanOrEqualTo(8)
                        .isEqualTo(assembly.store().droppedSpans() - droppedBefore);
            });
        } finally {
            assembly.store().close();
        }
    }

    /** An export of ten traces of one span each, numbered from {@code first}. */
    private static ExportTraceServiceRequest tenTraces(int first) {
        Span.Builder[] spans = new Span.Builder[10];
        for (int n = 0; n < spans.length; n++) {
            spans[n] = Otlp.span("%032x".formatted(first + n + 1), "%016x".formatted(first + n + 1), "GET /orders",
                    Span.SpanKind.SPAN_KIND_SERVER, NOW, 5);
        }
        return Otlp.traces(Otlp.service("spring-orders"), spans);
    }

    /** {@code partial_success.rejected_spans} of a protobuf answer to a trace export. */
    private static long rejectedSpans(byte[] answer) {
        try {
            return ExportTraceServiceResponse.parseFrom(answer).getPartialSuccess().getRejectedSpans();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anotherContentTypeIs415AndAnUndecodableBodyIs400() {
        serve((client, assembly) -> {
            HttpResponse<String> text = client.send(request -> request
                    .uri(URI.create(client.url("/v1/traces")))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("hello")));
            assertThat(text.statusCode()).isEqualTo(415);
            assertThat(Json.parse(text.body()).asObject().getString("error")).isNotBlank();

            HttpResponse<String> rubbish = client.send(request -> request
                    .uri(URI.create(client.url("/v1/logs")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("not json at all")));
            assertThat(rubbish.statusCode()).isEqualTo(400);
            assertThat(Json.parse(rubbish.body()).asObject().getString("error")).isNotBlank();
        });
    }

    @Test
    void aGzipBodyThatIsNotGzipIs400() {
        serve((client, assembly) -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/v1/traces")))
                    .header("Content-Type", PROTOBUF)
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofString("plain, not gzip")));

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(Json.parse(response.body()).asObject().getString("error")).startsWith("Undecodable");
        });
    }

    /** A small gzipped body that expands past the cap is refused before it fills the heap. */
    @Test
    void aBodyPastTheCapOnceGunzippedIs413() {
        serve((client, assembly) -> {
            byte[] expanded = new byte[65 * 1024 * 1024];
            assertThat(Otlp.gzip(expanded).length).isLessThan(1024 * 1024);

            HttpResponse<String> response = postGzippedProtobuf(client, "/v1/traces", expanded);

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(Json.parse(response.body()).asObject().getString("error")).contains("64 MB");
        });
    }

    @Test
    void tracesAndTheTraceDetailCarryTheFieldsTheContractNames() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            JsonObject traces = json(client.get("/api/traces" + windowQuery()));
            assertThat(traces.getLong("total")).isEqualTo(2);
            assertThat(traces.getObject("window").has("bucketMs")).isTrue();

            JsonObject summary = null;
            for (JsonValue value : traces.getArray("traces")) {
                if (value.asObject().getString("traceId").equals(TRACE)) {
                    summary = value.asObject();
                }
            }
            assertThat(summary).isNotNull();
            assertThat(summary.getString("rootName")).isEqualTo("GET /orders/{id}");
            assertThat(summary.getString("rootService")).isEqualTo("spring-orders");
            assertThat(summary.getString("rootKind")).isEqualTo("SERVER");
            assertThat(summary.getLong("spanCount")).isEqualTo(2);
            assertThat(summary.getLong("dbCount")).isEqualTo(1);
            assertThat(summary.getLong("errorCount")).isZero();
            assertThat(summary.getLong("httpStatus")).isEqualTo(200);
            assertThat(summary.getBoolean("error")).isFalse();

            JsonObject detail = json(client.get("/api/traces/" + TRACE));
            assertThat(detail.getString("traceId")).isEqualTo(TRACE);
            assertThat(detail.getArray("spans")).hasSize(2);
            JsonObject root = detail.getArray("spans").get(0).asObject();
            assertThat(root.getString("spanId")).isEqualTo(ROOT);
            assertThat(root.get("parentSpanId").isNull()).isTrue();
            assertThat(root.getString("category")).isEqualTo("http");
            assertThat(root.getString("summary")).isEqualTo("GET /orders/{id} → 200");
            assertThat(root.getObject("attributes").getString("http.route")).isEqualTo("/orders/{id}");
            JsonObject child = detail.getArray("spans").get(1).asObject();
            assertThat(child.getString("parentSpanId")).isEqualTo(ROOT);
            assertThat(child.getString("category")).isEqualTo("db");
            assertThat(child.getBoolean("slow")).isTrue();

            assertThat(client.get("/api/traces/" + "f".repeat(32)).statusCode()).isEqualTo(404);
        });
    }

    @Test
    void filtersNarrowTheTraceList() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());
            String base = "/api/traces" + windowQuery();

            assertThat(json(client.get(base + "&status=error")).getArray("traces")).hasSize(1);
            assertThat(json(client.get(base + "&status=ok")).getArray("traces")).hasSize(1);
            assertThat(json(client.get(base + "&minMs=500")).getArray("traces")).hasSize(1);
            assertThat(json(client.get(base + "&q=ship")).getArray("traces")).hasSize(1);
            assertThat(json(client.get(base + "&service=spring-orders")).getArray("traces")).hasSize(2);
            assertThat(json(client.get(base + "&service=nobody")).getArray("traces")).isEmpty();
            assertThat(json(client.get(base + "&service=")).getArray("traces")).as("no filter").hasSize(2);
            assertThat(json(client.get(base + "&before=" + NOW)).getArray("traces")).isEmpty();

            assertThat(client.get(base + "&minMs=abc").statusCode()).isEqualTo(400);
            assertThat(Json.parse(client.get("/api/traces?from=nonsense").body()).asObject()
                    .getString("error")).isNotBlank();
        });
    }

    @Test
    void servicesEndpointsQueriesAndErrorsAggregateTheWindow() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            JsonObject services = json(client.get("/api/services" + windowQuery()));
            JsonObject service = services.getArray("services").get(0).asObject();
            assertThat(service.getString("name")).isEqualTo("spring-orders");
            assertThat(service.getString("language")).isEqualTo("java");
            assertThat(service.getBoolean("embedded")).isFalse();
            assertThat(service.getLong("requests")).isEqualTo(2);
            assertThat(service.getLong("errors")).isEqualTo(1);
            assertThat(service.getArray("sparkline").size()).isGreaterThan(0);
            assertThat(service.getBoolean("hasJvm")).isFalse();
            assertThat(service.getDouble("apdex")).isEqualTo(0.5);
            assertThat(counts(service.getArray("histogram"))).containsExactly(0L, 1L, 0L, 0L, 1L);

            JsonObject detail = json(client.get("/api/services/spring-orders" + windowQuery()));
            assertThat(detail.getObject("resource").getString("service.name")).isEqualTo("spring-orders");
            assertThat(detail.getObject("series").getArray("p95Ms").size())
                    .isEqualTo(detail.getObject("series").getArray("t").size());
            assertThat(detail.getArray("endpoints")).hasSize(2);
            JsonObject ok = null;
            for (JsonValue value : detail.getArray("endpoints")) {
                if (value.asObject().getString("name").equals("GET /orders/{id}")) {
                    ok = value.asObject();
                }
            }
            assertThat(ok).isNotNull();
            assertThat(ok.getDouble("apdex")).isEqualTo(1.0);
            assertThat(counts(ok.getArray("histogram"))).containsExactly(0L, 1L, 0L, 0L, 0L);
            assertThat(detail.getArray("queries")).hasSize(1);
            assertThat(detail.getArray("errors")).hasSize(1);
            JsonObject dependency = detail.getArray("dependencies").get(0).asObject();
            assertThat(dependency.getString("kind")).isEqualTo("db");
            assertThat(dependency.getString("target")).isEqualTo("h2:orders");

            JsonObject endpoints = json(client.get("/api/endpoints" + windowQuery()));
            JsonObject endpoint = endpoints.getArray("endpoints").get(0).asObject();
            assertThat(endpoint.getString("name")).isIn("GET /orders/{id}", "POST /orders/{id}/ship");
            assertThat(endpoint.getString("method")).isIn("GET", "POST");
            assertThat(endpoint.getObject("statusCodes").size()).isEqualTo(1);
            JsonObject endpointDetail =
                    json(client.get("/api/endpoints/" + endpoint.getString("endpointId") + windowQuery()));
            assertThat(endpointDetail.getObject("endpoint").getString("endpointId"))
                    .isEqualTo(endpoint.getString("endpointId"));
            assertThat(endpointDetail.has("recent")).isTrue();

            JsonObject queries = json(client.get("/api/queries" + windowQuery()));
            JsonObject query = queries.getArray("queries").get(0).asObject();
            assertThat(query.getString("statement")).isEqualTo("select * from orders where name like ?");
            assertThat(query.getString("system")).isEqualTo("h2");
            assertThat(query.getString("namespace")).isEqualTo("orders");
            assertThat(query.getString("operation")).isEqualTo("SELECT");
            assertThat(query.getString("table")).isEqualTo("orders");
            assertThat(query.getLong("slowCalls")).isEqualTo(1);
            assertThat(query.getArray("callers").get(0).asObject().getString("endpoint"))
                    .isEqualTo("GET /orders/{id}");
            // The caller links to the endpoint page (api.adoc#callers).
            assertThat(query.getArray("callers").get(0).asObject().getString("endpointId"))
                    .isEqualTo(Ids.endpointId("spring-orders", "GET /orders/{id}"));
            assertThat(json(client.get("/api/queries/" + query.getString("queryId") + windowQuery()))
                    .getObject("series").has("calls")).isTrue();

            JsonObject errors = json(client.get("/api/errors" + windowQuery()));
            JsonObject error = errors.getArray("errors").get(0).asObject();
            assertThat(error.getString("type")).isEqualTo("java.lang.IllegalStateException");
            assertThat(error.getString("message")).isEqualTo("Order ? is already shipped");
            assertThat(error.getLong("count")).isEqualTo(1);
            assertThat(error.getObject("sample").getString("stacktrace")).isEqualTo("at Orders.ship(..)");
            JsonObject errorDetail =
                    json(client.get("/api/errors/" + error.getString("errorId") + windowQuery()));
            assertThat(errorDetail.getObject("series").has("count")).isTrue();
            JsonObject place = errorDetail.getObject("error").getArray("endpoints").get(0).asObject();
            assertThat(place.getString("name")).isEqualTo("POST /orders/{id}/ship");
            assertThat(place.getString("endpointId"))
                    .isEqualTo(Ids.endpointId("spring-orders", "POST /orders/{id}/ship"));
            JsonObject cause = errorDetail.getArray("chain").get(0).asObject();
            assertThat(cause.getString("type")).isEmpty();
            assertThat(cause.getArray("frames").get(0).asString()).isEqualTo("Orders.ship(..)");
        });
    }

    /** Two letters for {@code n}, so that no digit is normalised away and two names stay two groups. */
    private static String letters(int n) {
        return "" + (char) ('a' + n / 26) + (char) ('a' + n % 26);
    }

    /**
     * An endpoint is its service and its name: two services with the same route never
     * share their queries or errors, and a group of the endpoint that ranks below the
     * first hundred of the window is still on its page (api.adoc#endpoints).
     */
    @Test
    void anEndpointsQueriesAndErrorsAreItsServicesAndAllOfThem() {
        serve((client, assembly) -> {
            for (String service : List.of("svc-a", "svc-b")) {
                String n = service.equals("svc-a") ? "1" : "2";
                Span.Builder root = Otlp.failing(Otlp.span("%032x".formatted(Integer.parseInt(n)),
                        "%016x".formatted(Integer.parseInt(n)), "GET /same/{id}",
                        Span.SpanKind.SPAN_KIND_SERVER, NOW, 20,
                        Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/same/{id}")),
                        "java.lang." + (n.equals("1") ? "A" : "B") + "Error", "no", "at x");
                Span.Builder query = Otlp.child(root, "%016x".formatted(10 + Integer.parseInt(n)),
                        "SELECT t", Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 1,
                        Otlp.attr("db.system", "h2"),
                        Otlp.attr("db.statement", "select " + service.replace("-", "_") + " from t"));
                postProtobuf(client, "/v1/traces", Otlp.traces(Otlp.service(service), root, query).toByteArray());
            }
            // A hundred heavier query groups and a hundred more frequent error groups of svc-a,
            // from another endpoint, rank the target's own group below the first hundred.
            for (int t = 0; t < 2; t++) {
                Span.Builder other = Otlp.span("%032x".formatted(100 + t), "%016x".formatted(100 + t),
                        "GET /other", Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                        Otlp.attr("http.request.method", "GET"), Otlp.attr("http.route", "/other"));
                List<Span.Builder> spans = new ArrayList<>(List.of(other));
                for (int i = 0; i < 100; i++) {
                    spans.add(Otlp.child(other, "%016x".formatted(1_000 + t * 1_000 + i), "SELECT t",
                            Span.SpanKind.SPAN_KIND_CLIENT, NOW + 1, 5,
                            Otlp.attr("db.system", "h2"),
                            Otlp.attr("db.statement", "select col_" + letters(i) + " from t")));
                    spans.add(Otlp.failing(Otlp.child(other, "%016x".formatted(5_000 + t * 1_000 + i),
                            "work", Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 1, 1),
                            "java.lang.Filler" + letters(i) + "Error", "no", "at x"));
                }
                postProtobuf(client, "/v1/traces", Otlp.traces(Otlp.service("svc-a"),
                        spans.toArray(new Span.Builder[0])).toByteArray());
            }

            String endpointId = net.benelog.spidersense.store.Ids.endpointId("svc-a", "GET /same/{id}");
            JsonObject detail = json(client.get("/api/endpoints/" + endpointId + windowQuery()));

            List<String> statements = new ArrayList<>();
            for (JsonValue query : detail.getArray("queries")) {
                statements.add(query.asObject().getString("statement"));
            }
            assertThat(statements).containsExactly("select svc_a from t");
            List<String> types = new ArrayList<>();
            for (JsonValue error : detail.getArray("errors")) {
                types.add(error.asObject().getString("type"));
            }
            assertThat(types).containsExactly("java.lang.AError");
        });
    }

    @Test
    void theOverviewAndTheScatterDescribeTheSameWindow() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            JsonObject overview = json(client.get("/api/overview" + windowQuery()));
            assertThat(overview.getObject("totals").getLong("requests")).isEqualTo(2);
            assertThat(overview.getObject("totals").getLong("errors")).isEqualTo(1);
            assertThat(overview.getObject("totals").getDouble("errorRate")).isEqualTo(0.5);
            // A 152 ms request lands in the second bucket, the failing one in the error slot.
            assertThat(overview.getObject("totals").getDouble("apdex")).isEqualTo(0.5);
            assertThat(counts(overview.getObject("totals").getArray("histogram")))
                    .containsExactly(0L, 1L, 0L, 0L, 1L);
            assertThat(overview.getArray("services")).hasSize(1);
            assertThat(overview.getArray("tingles")).isNotEmpty();
            JsonObject tingle = overview.getArray("tingles").get(0).asObject();
            assertThat(tingle.getString("kind")).isIn("slow-request", "slow-query", "error");
            JsonObject series = overview.getObject("series");
            assertThat(series.getArray("requests").size()).isEqualTo(series.getArray("t").size());
            assertThat(series.getArray("p95Ms").size()).isEqualTo(series.getArray("t").size());
            JsonArray seriesHistogram = series.getArray("histogram");
            assertThat(seriesHistogram).hasSize(4);
            for (JsonValue bucket : seriesHistogram) {
                assertThat(bucket.asArray().size()).isEqualTo(series.getArray("t").size());
            }

            // A service narrows the totals, the series and the tingles; the services stay whole.
            JsonObject one = json(client.get("/api/overview" + windowQuery() + "&service=spring-orders"));
            assertThat(one.getObject("totals").getLong("requests")).isEqualTo(2);
            assertThat(one.getArray("tingles")).hasSameSizeAs(overview.getArray("tingles"));
            JsonObject other = json(client.get("/api/overview" + windowQuery() + "&service=nope"));
            assertThat(other.getObject("totals").getLong("requests")).isZero();
            assertThat(other.getArray("tingles")).isEmpty();
            assertThat(counts(other.getObject("series").getArray("requests"))).containsOnly(0L);
            assertThat(other.getArray("services")).hasSize(1);

            JsonObject scatter = json(client.get("/api/scatter" + windowQuery()));
            assertThat(scatter.getBoolean("truncated")).isFalse();
            JsonArray points = scatter.getArray("points");
            assertThat(points).hasSize(2);
            JsonArray point = points.get(0).asArray();
            assertThat(point.size()).isEqualTo(6);
            assertThat(point.get(2).asString()).isEqualTo("spring-orders");
            assertThat(point.get(3).asString()).isIn("GET /orders/{id}", "POST /orders/{id}/ship");

            // Exactly as many points as the limit is a full list, not a cut one.
            JsonObject full = json(client.get("/api/scatter" + windowQuery() + "&limit=2"));
            assertThat(full.getBoolean("truncated")).isFalse();
            assertThat(full.getArray("points")).hasSize(2);
            JsonObject cut = json(client.get("/api/scatter" + windowQuery() + "&limit=1"));
            assertThat(cut.getBoolean("truncated")).isTrue();
            assertThat(cut.getArray("points")).hasSize(1);
        });
    }

    @Test
    void aWindowOutsideTheEpochAndTheYear9999IsA400AndAnyOtherKeepsItsBucketsFew() {
        serve((client, assembly) -> {
            // Nanoseconds pasted into to, and both ends at the limits of a long.
            for (String window : List.of("from=1790940000000&to=1790940000050000000",
                    "from=0&to=100000000000000000", "from=-9223372036854775808&to=9223372036854775807",
                    "from=-1&to=1000")) {
                HttpResponse<String> response = client.get("/api/overview?" + window);
                assertThat(response.statusCode()).as(window).isEqualTo(400);
                assertThat(Json.parse(response.body()).asObject().getString("error")).as(window)
                        .isNotBlank();
            }
            JsonObject everything = json(client.get("/api/overview?from=0&to=253402300799999"));
            assertThat(everything.getObject("series").getArray("t").size())
                    .isBetween(1, 75);
            assertThat(client.get("/api/services/spring-orders?from=0&to=253402300799999").statusCode())
                    .isIn(200, 404);
        });
    }

    /**
     * A two-service trace: the clients call spring-orders, which calls its database
     * and the bookstore over HTTP. The outbound HTTP span is the call to the
     * bookstore, so the map must show the bookstore rather than {@code localhost:8081}.
     */
    @Test
    void theMapShowsTheUserTheServicesAndTheirTargetsWithoutDoubleCountingACall() {
        serve((client, assembly) -> {
            Span.Builder root = Otlp.span(TRACE, ROOT, "GET /orders/{id}",
                    Span.SpanKind.SPAN_KIND_SERVER, NOW, 80,
                    Otlp.attr("http.request.method", "GET"),
                    Otlp.attr("http.route", "/orders/{id}"),
                    Otlp.attr("http.response.status_code", 200));
            Span.Builder outbound = Otlp.child(root, CHILD, "GET", Span.SpanKind.SPAN_KIND_CLIENT,
                    NOW + 5, 40,
                    Otlp.attr("http.request.method", "GET"),
                    Otlp.attr("url.full", "http://localhost:8081/books"),
                    Otlp.attr("server.address", "localhost"),
                    Otlp.attr("server.port", 8081));
            Span.Builder query = Otlp.child(root, "00f067aa0ba902c1", "SELECT orders",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + 50, 10,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from orders where id = ?"),
                    Otlp.attr("db.name", "orders"));
            Span.Builder served = Otlp.child(outbound, "00f067aa0ba902c2", "GET /books",
                    Span.SpanKind.SPAN_KIND_SERVER, NOW + 6, 30,
                    Otlp.attr("http.request.method", "GET"),
                    Otlp.attr("http.route", "/books"));

            postProtobuf(client, "/v1/traces", Otlp.traces(
                    Otlp.resourceSpans(Otlp.service("spring-orders"), root, outbound, query),
                    Otlp.resourceSpans(Otlp.service("silk-bookstore"), served)).toByteArray());

            JsonObject map = json(client.get("/api/map" + windowQuery()));
            assertThat(map.getObject("window").has("bucketMs")).isTrue();
            assertThat(map.getArray("nodes")).hasSize(4);
            assertThat(node(map, "user").getString("name")).isEqualTo("Clients");
            JsonObject orders = node(map, "svc:spring-orders");
            assertThat(orders.getString("kind")).isEqualTo("service");
            assertThat(orders.getLong("requests")).isEqualTo(1);
            assertThat(orders.getDouble("apdex")).isEqualTo(1.0);
            assertThat(counts(orders.getArray("histogram"))).containsExactly(1L, 0L, 0L, 0L, 0L);
            assertThat(orders.getBoolean("hasJvm")).isFalse();
            assertThat(node(map, "svc:silk-bookstore")).isNotNull();
            JsonObject database = node(map, "db:h2:orders");
            assertThat(database.getString("name")).isEqualTo("h2:orders");
            assertThat(database.getLong("calls")).isEqualTo(1);
            assertThat(database.getDouble("avgMs")).isEqualTo(10.0);
            assertThat(node(map, "http:localhost:8081")).isNull();

            List<String> edges = new ArrayList<>();
            for (JsonValue value : map.getArray("edges")) {
                edges.add(value.asObject().getString("from") + " -> "
                        + value.asObject().getString("to"));
            }
            assertThat(edges).containsExactlyInAnyOrder(
                    "user -> svc:spring-orders",
                    "svc:spring-orders -> svc:silk-bookstore",
                    "svc:spring-orders -> db:h2:orders");
        });
    }

    @Test
    void theJvmViewCuratesTheConnectionPoolsOfEveryDataSource() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/metrics", Otlp.sum(Otlp.service("spring-orders"),
                    "db.client.connections.usage", "{connection}", NOW, 3, false,
                    Otlp.attr("pool.name", "HikariPool-1"),
                    Otlp.attr("state", "used")).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.sum(Otlp.service("spring-orders"),
                    "db.client.connections.usage", "{connection}", NOW, 5, false,
                    Otlp.attr("pool.name", "HikariPool-1"),
                    Otlp.attr("state", "idle")).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.sum(Otlp.service("spring-orders"),
                    "db.client.connections.max", "{connection}", NOW, 10, false,
                    Otlp.attr("pool.name", "HikariPool-1")).toByteArray());

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            assertThat(jvm.getArray("connectionPools")).hasSize(1);
            JsonObject pool = jvm.getArray("connectionPools").get(0).asObject();
            assertThat(pool.getString("name")).isEqualTo("HikariPool-1");
            assertThat(pool.getArray("t")).hasSize(1);
            assertThat(pool.getArray("used").get(0).asDouble()).isEqualTo(3.0);
            assertThat(pool.getArray("idle").get(0).asDouble()).isEqualTo(5.0);
            assertThat(pool.getArray("max").get(0).asDouble()).isEqualTo(10.0);
            // Nothing reported a queue, so the pending series is null per point, never zero.
            assertThat(pool.getArray("pending").get(0).isNull()).isTrue();
        });
    }

    @Test
    void metricsAreCataloguedsampledAndCuratedIntoTheJvmView() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/metrics", Otlp.gauge(Otlp.service("spring-orders"),
                    "jvm.memory.used", "By", NOW, 1024,
                    Otlp.attr("jvm.memory.type", "heap"),
                    Otlp.attr("jvm.memory.pool.name", "G1 Eden Space")).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.gauge(Otlp.service("spring-orders"),
                    "jvm.memory.used", "By", NOW, 2048,
                    Otlp.attr("jvm.memory.type", "heap"),
                    Otlp.attr("jvm.memory.pool.name", "G1 Old Gen")).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.gauge(Otlp.service("spring-orders"),
                    "jvm.cpu.count", "{cpu}", NOW, 8).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.histogram(Otlp.service("spring-orders"),
                    "jvm.gc.duration", NOW, 4, 0.2, new double[]{0.01, 0.1}, new long[]{2, 1, 1},
                    Otlp.attr("jvm.gc.name", "G1 Young Generation"),
                    Otlp.attr("jvm.gc.action", "end of minor GC")).toByteArray());

            JsonObject catalog = json(client.get("/api/metrics"));
            assertThat(catalog.getArray("metrics").size()).isEqualTo(3);
            JsonObject memory = catalog.getArray("metrics").get(2).asObject();
            assertThat(memory.getString("name")).isEqualTo("jvm.memory.used");
            assertThat(memory.getString("type")).isEqualTo("gauge");
            assertThat(memory.getString("unit")).isEqualTo("By");
            assertThat(memory.getLong("series")).isEqualTo(2);
            assertThat(memory.getArray("services").get(0).asString()).isEqualTo("spring-orders");

            JsonObject series = json(client.get(
                    "/api/metrics/series?name=jvm.memory.used" + windowQuery().replace('?', '&')));
            assertThat(series.getString("name")).isEqualTo("jvm.memory.used");
            assertThat(series.getArray("series")).hasSize(2);
            JsonObject first = series.getArray("series").get(0).asObject();
            assertThat(first.getString("service")).isEqualTo("spring-orders");
            assertThat(first.getArray("t")).hasSize(1);
            assertThat(first.getArray("v").get(0).asDouble()).isIn(1024.0, 2048.0);

            JsonObject filtered = json(client.get("/api/metrics/series?name=jvm.memory.used"
                    + "&attr.jvm.memory.pool.name=G1+Eden+Space" + windowQuery().replace('?', '&')));
            assertThat(filtered.getArray("series")).hasSize(1);

            JsonObject histogram = json(client.get(
                    "/api/metrics/series?name=jvm.gc.duration" + windowQuery().replace('?', '&')));
            JsonObject gc = histogram.getArray("series").get(0).asObject();
            assertThat(gc.has("count")).isTrue();
            assertThat(gc.has("p95")).isTrue();
            assertThat(gc.has("max")).isTrue();
            assertThat(gc.getArray("v").get(0).asDouble()).isEqualTo(0.05);

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            assertThat(jvm.getString("service")).isEqualTo("spring-orders");
            assertThat(jvm.getObject("runtime").getLong("cpuCount")).isEqualTo(8);
            assertThat(jvm.getObject("heap").getArray("used").get(0).asDouble()).isEqualTo(3072.0);
            assertThat(jvm.getArray("pools")).hasSize(2);
            assertThat(jvm.getArray("gc")).hasSize(1);
            assertThat(jvm.getObject("classes").has("t")).isTrue();
        });
    }

    @Test
    void aQueryGroupSaysWhereInTheCodeItIsIssued() {
        serve((client, assembly) -> {
            Span.Builder root = Otlp.span(TRACE, ROOT, "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                    NOW, 152,
                    Otlp.attr("http.request.method", "GET"),
                    Otlp.attr("http.route", "/orders/{id}"));
            Span.Builder query = Otlp.child(root, CHILD, "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT,
                    NOW + 10, 200,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from orders where id = ?"),
                    Otlp.attr("code.stacktrace", """
                            \tat org.h2.jdbc.JdbcPreparedStatement.executeQuery(JdbcPreparedStatement.java:120)
                            \tat orders.OrderRepository.load(OrderRepository.java:64)
                            \tat orders.OrderService.report(OrderService.java:18)
                            """));
            postProtobuf(client, "/v1/traces",
                    Otlp.traces(Otlp.service("spring-orders"), root, query).toByteArray());

            String queryId = json(client.get("/api/queries" + windowQuery()))
                    .getArray("queries").get(0).asObject().getString("queryId");
            JsonObject detail = json(client.get("/api/queries/" + queryId + windowQuery()));

            // The frames a slow-query finding on it carries (api.adoc#query).
            assertThat(strings(detail.getArray("code"))).containsExactly(
                    "orders.OrderRepository.load(OrderRepository.java:64)",
                    "orders.OrderService.report(OrderService.java:18)");
        });
    }

    @Test
    void aDeepStackTraceIsCutRatherThanLosingTheSpanAndItsBatch() {
        serve((client, assembly) -> {
            String deep = "java.lang.StackOverflowError\n"
                    + "\tat net.example.Orders.recurse(Orders.java:42)\n".repeat(2000);
            Span.Builder failing = Otlp.failing(
                    Otlp.span(FAILING_TRACE, "00f067aa0ba902b9", "GET /orders/deep",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, 5,
                            Otlp.attr("http.request.method", "GET"),
                            Otlp.attr("code.stacktrace", deep)),
                    "java.lang.StackOverflowError", "deep", deep);
            Span.Builder healthy = Otlp.span(TRACE, ROOT, "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                    NOW + 1, 5, Otlp.attr("http.request.method", "GET"));
            postProtobuf(client, "/v1/traces",
                    Otlp.traces(Otlp.service("spring-orders"), failing, healthy).toByteArray());

            JsonObject detail = json(client.get("/api/traces/" + FAILING_TRACE));
            JsonObject span = detail.getArray("spans").get(0).asObject();
            assertThat(span.getObject("attributes").getString("code.stacktrace"))
                    .startsWith("java.lang.StackOverflowError").endsWith("…");
            assertThat(client.get("/api/traces/" + TRACE).statusCode()).isEqualTo(200);
        });
    }

    @Test
    void theJvmViewReportsGcTimeInMillisecondsFromASecondsHistogram() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/metrics", Otlp.histogram(Otlp.service("spring-orders"),
                    "jvm.gc.duration", "s", NOW - 1000, 2, 0.05, 0.03,
                    Otlp.attr("jvm.gc.name", "G1 Young Generation")).toByteArray());
            postProtobuf(client, "/v1/metrics", Otlp.histogram(Otlp.service("spring-orders"),
                    "jvm.gc.duration", "s", NOW, 3, 0.062, 0.03,
                    Otlp.attr("jvm.gc.name", "G1 Young Generation")).toByteArray());

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            JsonArray durations = jvm.getArray("gc").get(0).asObject().getArray("durationMs");
            double total = 0;
            for (int i = 0; i < durations.size(); i++) {
                total += durations.get(i).asDouble();
            }
            assertThat(total).isCloseTo(12.0, within(1e-6));
        });
    }

    /**
     * Two processes that export one service name at once, an application and its tests, keep a
     * series each: the collections are each process's own differences, and the gauges add up
     * rather than take turns, so no difference between the two totals reads as a pause.
     */
    @Test
    void twoProcessesOfOneServiceAddUpAndAreNeverDifferencedAgainstEachOther() {
        serve((client, assembly) -> {
            // The application has collected 500 times and runs 40 threads, the tests 20 times and
            // 25; each collects once, for 5 ms, every 10 s, and they export 5 s apart.
            for (int k = 0; k < 5; k++) {
                exportJvm(client, 100, NOW - 50_000 + k * 10_000L, 500 + k, 40);
                exportJvm(client, 200, NOW - 45_000 + k * 10_000L, 20 + k, 25);
            }

            List<Long> pids = new ArrayList<>();
            for (JsonValue series : json(client.get("/api/metrics/series?name=jvm.gc.duration"
                    + windowQuery().replace('?', '&'))).getArray("series")) {
                pids.add(series.asObject().getObject("attributes").getLong("process.pid"));
            }
            assertThat(pids).containsExactlyInAnyOrder(100L, 200L);

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            assertThat(jvm.getArray("gc")).hasSize(1);
            JsonObject gc = jvm.getArray("gc").get(0).asObject();
            List<Long> collections = counts(gc.getArray("count"));
            assertThat(collections).allMatch(count -> count <= 1);
            assertThat(collections.stream().mapToLong(Long::longValue).sum()).isEqualTo(8);
            double collected = 0;
            for (JsonValue ms : gc.getArray("durationMs")) {
                collected += ms.asDouble();
            }
            assertThat(collected).isCloseTo(40.0, within(1e-6));
            JsonArray threads = jvm.getObject("threads").getArray("count");
            assertThat(threads).hasSize(10);
            for (JsonValue count : threads) {
                assertThat(count.asDouble()).as("40 threads and 25 at every instant of either").isEqualTo(65.0);
            }

            for (JsonValue finding : json(client.get("/api/findings" + windowQuery())).getArray("findings")) {
                assertThat(finding.asObject().getString("kind")).isNotEqualTo("gc-pause");
            }
        });
    }

    /** A process restarted is followed by the one that replaced it, never added to it. */
    @Test
    void aRestartedProcessIsFollowedByTheNextOneNeverAddedToIt() {
        serve((client, assembly) -> {
            for (int k = 0; k < 3; k++) {
                exportJvm(client, 100, NOW - 50_000 + k * 5_000L, 10 + k, 40);
                exportJvm(client, 200, NOW - 20_000 + k * 5_000L, 1 + k, 25);
            }

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            List<Double> threads = new ArrayList<>();
            for (JsonValue count : jvm.getObject("threads").getArray("count")) {
                threads.add(count.asDouble());
            }
            assertThat(threads).containsExactly(40.0, 40.0, 40.0, 25.0, 25.0, 25.0);
            assertThat(counts(jvm.getArray("gc").get(0).asObject().getArray("count")).stream()
                    .mapToLong(Long::longValue).sum()).as("two collections in each run").isEqualTo(4);
        });
    }

    /** A process that starts beside another counts from its own first point, not before it. */
    @Test
    void aProcessThatStartsBesideAnotherCountsFromItsFirstPoint() {
        serve((client, assembly) -> {
            for (int k = 0; k < 5; k++) {
                exportJvm(client, 100, NOW - 50_000 + k * 10_000L, 10, 40);
            }
            for (int k = 0; k < 3; k++) {
                exportJvm(client, 200, NOW - 25_000 + k * 10_000L, 1, 25);
            }

            JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            List<Double> threads = new ArrayList<>();
            for (JsonValue count : jvm.getObject("threads").getArray("count")) {
                threads.add(count.asDouble());
            }
            // At -50, -40 and -30 s the application alone; from -25 s, both.
            assertThat(threads).containsExactly(40.0, 40.0, 40.0, 65.0, 65.0, 65.0, 65.0, 65.0);
        });
    }

    /** One export of one process of spring-orders: its collections so far, 5 ms each, and its threads. */
    private static void exportJvm(TestClient client, long pid, long at, long collections, double threads) {
        Resource process = Otlp.resource(Otlp.attr("service.name", "spring-orders"),
                Otlp.attr("process.pid", pid));
        postProtobuf(client, "/v1/metrics", Otlp.histogram(process, "jvm.gc.duration", "s", at, collections,
                collections * 0.005, 0.005, Otlp.attr("jvm.gc.name", "G1 Young Generation"),
                Otlp.attr("jvm.gc.action", "end of minor GC")).toByteArray());
        postProtobuf(client, "/v1/metrics", Otlp.sum(process, "jvm.thread.count", "{thread}", at, threads,
                false).toByteArray());
    }

    @Test
    void logsAreListedFilteredAndCorrelatedWithTheirTrace() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());
            postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("spring-orders"),
                    "o.s.boot.StartupInfoLogger",
                    Otlp.log(NOW + 5, 9, "Started OrdersApplication in 2.1 seconds", TRACE, ROOT),
                    Otlp.log(NOW + 6, 17, "Shipping failed", null, null)).toByteArray());

            JsonObject logs = json(client.get("/api/logs" + windowQuery()));
            assertThat(logs.getLong("total")).isEqualTo(2);
            JsonObject newest = logs.getArray("logs").get(0).asObject();
            assertThat(newest.getString("severity")).isEqualTo("ERROR");
            assertThat(newest.getLong("severityNumber")).isEqualTo(17);
            assertThat(newest.getString("body")).isEqualTo("Shipping failed");
            assertThat(newest.getString("logger")).isEqualTo("o.s.boot.StartupInfoLogger");
            assertThat(newest.getLong("id")).isPositive();
            assertThat(newest.getObject("attributes").getString("thread.name")).isEqualTo("main");

            assertThat(json(client.get("/api/logs" + windowQuery() + "&severity=ERROR"))
                    .getArray("logs")).hasSize(1);
            assertThat(json(client.get("/api/logs" + windowQuery() + "&q=started"))
                    .getArray("logs")).hasSize(1);
            assertThat(json(client.get("/api/logs" + windowQuery() + "&traceId=" + TRACE))
                    .getArray("logs")).hasSize(1);

            // The cursor of the next page is the last row's time and id.
            JsonObject page = json(client.get("/api/logs" + windowQuery() + "&limit=1"));
            JsonObject last = page.getArray("logs").get(0).asObject();
            JsonArray next = json(client.get("/api/logs" + windowQuery() + "&limit=1&before="
                    + last.getLong("at") + "&beforeId=" + last.getLong("id"))).getArray("logs");
            assertThat(next.get(0).asObject().getString("body"))
                    .isEqualTo("Started OrdersApplication in 2.1 seconds");

            JsonObject trace = json(client.get("/api/traces/" + TRACE));
            assertThat(trace.getArray("logs")).hasSize(1);
            assertThat(trace.getArray("logs").get(0).asObject().getString("traceId")).isEqualTo(TRACE);
        });
    }

    @Test
    void statusDescribesTheServerItsStorageAndItsCounts() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            JsonObject status = json(client.get("/api/status"));
            assertThat(status.getString("name")).isEqualTo("Spider Sense");
            assertThat(status.getString("version")).isEqualTo(Version.CURRENT);
            assertThat(status.getString("mode")).isEqualTo("standalone");
            assertThat(status.getString("endpoint")).startsWith("http://127.0.0.1:");
            assertThat(status.getObject("otlp").getString("traces")).endsWith("/v1/traces");
            assertThat(status.get("embeddedService").isNull()).isTrue();
            assertThat(status.getObject("thresholds").getLong("slowRequestMs")).isEqualTo(500);
            assertThat(counts(status.getObject("thresholds").getArray("responseBucketsMs")))
                    .containsExactly(125L, 500L, 2000L);
            assertThat(strings(status.getObject("ignore").getArray("endpoints")))
                    .containsExactly("/actuator/**", "/health", "/healthz", "/livez", "/readyz");
            assertThat(status.getObject("retention").getLong("hours")).isEqualTo(24);
            assertThat(status.getObject("retention").getLong("spans")).isEqualTo(1_000_000);
            assertThat(status.getObject("ingest").get("maxSpansPerSecond").isNull())
                    .as("unset by default").isTrue();
            JsonObject storage = status.getObject("storage");
            assertThat(storage.getString("url")).startsWith("jdbc:h2:mem:");
            assertThat(storage.get("path").isNull()).isTrue();
            assertThat(storage.getLong("sizeBytes")).isZero();
            assertThat(storage.getBoolean("fallback")).isFalse();
            assertThat(storage.getLong("droppedBatches")).isZero();
            assertThat(storage.getLong("droppedSpans")).isZero();
            JsonObject counts = status.getObject("counts");
            assertThat(counts.getLong("spans")).isEqualTo(3);
            assertThat(counts.getLong("traces")).isEqualTo(2);
            assertThat(counts.getLong("services")).isEqualTo(1);
            assertThat(status.getArray("services")).hasSize(1);
            assertThat(status.getObject("oldest").getLong("span")).isEqualTo(NOW);
        });
    }

    @Test
    void everyApiAnswerForbidsCaching() {
        serve((client, assembly) -> assertThat(client.get("/api/status").headers()
                .firstValue("cache-control")).hasValue("no-store"));
    }

    @Test
    void exportDownloadsTracesAndDeleteEmptiesTheWindows() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            HttpResponse<String> export = client.get("/api/export?traceId=" + TRACE);
            assertThat(export.statusCode()).isEqualTo(200);
            assertThat(export.headers().firstValue("content-disposition"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("attachment"));
            JsonObject exported = Json.parse(export.body()).asObject();
            assertThat(exported.getArray("traces")).hasSize(1);
            assertThat(exported.getArray("traces").get(0).asObject().getString("traceId"))
                    .isEqualTo(TRACE);
            // Without traceId the answer is the session document, not a list of traces.
            assertThat(Json.parse(client.get("/api/export" + windowQuery()).body()).asObject()
                    .getArray("spans")).hasSize(3);

            assertThat(client.delete("/api/data").statusCode()).isEqualTo(204);

            JsonObject status = json(client.get("/api/status"));
            assertThat(status.getObject("counts").getLong("spans")).isZero();
            assertThat(status.getObject("counts").getLong("traces")).isZero();
            assertThat(status.getObject("counts").getLong("services")).isEqualTo(1);
            assertThat(json(client.get("/api/traces" + windowQuery())).getArray("traces")).isEmpty();
        });
    }

    @Test
    void aSpanAimedAtOurOwnPortIsNeverStored() {
        serve((client, assembly) -> {
            assembly.boundPort().set(assembly.app().port());
            // The server's own process, monitored by mistake: its export reports this JVM's pid.
            byte[] ours = Otlp.traces(Otlp.resource(Otlp.attr("service.name", "silk-bookstore"),
                            Otlp.attr("process.pid", ProcessHandle.current().pid())),
                    Otlp.span(TRACE, ROOT, "GET /api/overview", Span.SpanKind.SPAN_KIND_SERVER, NOW, 3,
                            Otlp.attr("server.port", assembly.app().port())),
                    Otlp.span(FAILING_TRACE, CHILD, "GET /books", Span.SpanKind.SPAN_KIND_SERVER, NOW, 3,
                            Otlp.attr("server.port", 8081))).toByteArray();

            postProtobuf(client, "/v1/traces", ours);

            JsonObject traces = json(client.get("/api/traces" + windowQuery()));
            assertThat(traces.getArray("traces")).hasSize(1);
            assertThat(traces.getArray("traces").get(0).asObject().getString("traceId"))
                    .isEqualTo(FAILING_TRACE);
        });
    }

    @Test
    void anUnknownPageFallsBackToTheSinglePageButAnUnknownApiPathIsJson() {
        serve((client, assembly) -> {
            HttpResponse<String> api = client.get("/api/nothing-here");
            assertThat(api.statusCode()).isEqualTo(404);
            assertThat(Json.parse(api.body()).asObject().getString("error")).contains("Not found");

            HttpResponse<String> asset = client.get("/assets/missing.js");
            assertThat(asset.statusCode()).isEqualTo(404);

            // index.html is a resource of this module, so the page is always there, and it is
            // the page: a 200, not the 404 the error handler was entered with.
            for (String path : List.of("/", "/traces", "/findings")) {
                HttpResponse<String> page = client.get(path);
                assertThat(page.statusCode()).as(path).isEqualTo(200);
                assertThat(page.headers().firstValue("content-type"))
                        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
                assertThat(page.body()).contains("<title>Spider Sense</title>");
            }
        });
    }
}
