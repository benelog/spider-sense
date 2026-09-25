package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * The contract in api.adoc, exercised through the real request path.
 *
 * <p>Ingest is write-behind, so the tests set {@code spidersense.sync} and the
 * OTLP handler flushes before it answers. Without it a POST followed by a GET
 * would be a race against the writer thread rather than a test.
 */
class ApiTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String ROOT = "00f067aa0ba902b7";
    private static final String CHILD = "00f067aa0ba902b8";
    private static final String FAILING_TRACE = "4bf92f3577b34da6a3ce929d0e0e4737";
    private static final String PROTOBUF = "application/x-protobuf";

    @BeforeAll
    static void synchronousIngest() {
        System.setProperty("spidersense.sync", "true");
    }

    @AfterAll
    static void asynchronousIngestAgain() {
        System.clearProperty("spidersense.sync");
    }

    private interface Body {
        void run(TestClient client, SpiderSenseServer.Assembly assembly);
    }

    /** One assembled server per test, on an in-memory database of its own. */
    private static void serve(Body body) {
        Config config = TestStore.config();
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

    private static Json.JsonObject json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body()).asObject();
    }

    /** A JSON array of counts, as a list one assertion can compare. */
    private static List<Long> counts(Json.JsonArray array) {
        List<Long> values = new ArrayList<>();
        for (Json.JsonValue value : array) {
            values.add(value.asLong());
        }
        return values;
    }

    private static List<String> strings(Json.JsonArray array) {
        List<String> values = new ArrayList<>();
        for (Json.JsonValue value : array) {
            values.add(value.asString());
        }
        return values;
    }

    /** The node of a service map with this id, or null. */
    private static Json.JsonObject node(Json.JsonObject map, String id) {
        for (Json.JsonValue value : map.getArray("nodes")) {
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

            Json.JsonObject traces = json(client.get("/api/traces" + windowQuery()));
            assertThat(traces.getLong("total")).isEqualTo(2);
            assertThat(traces.getObject("window").has("bucketMs")).isTrue();

            Json.JsonObject summary = null;
            for (Json.JsonValue value : traces.getArray("traces")) {
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

            Json.JsonObject detail = json(client.get("/api/traces/" + TRACE));
            assertThat(detail.getString("traceId")).isEqualTo(TRACE);
            assertThat(detail.getArray("spans")).hasSize(2);
            Json.JsonObject root = detail.getArray("spans").get(0).asObject();
            assertThat(root.getString("spanId")).isEqualTo(ROOT);
            assertThat(root.get("parentSpanId").isNull()).isTrue();
            assertThat(root.getString("category")).isEqualTo("http");
            assertThat(root.getString("summary")).isEqualTo("GET /orders/{id} → 200");
            assertThat(root.getObject("attributes").getString("http.route")).isEqualTo("/orders/{id}");
            Json.JsonObject child = detail.getArray("spans").get(1).asObject();
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

            Json.JsonObject services = json(client.get("/api/services" + windowQuery()));
            Json.JsonObject service = services.getArray("services").get(0).asObject();
            assertThat(service.getString("name")).isEqualTo("spring-orders");
            assertThat(service.getString("language")).isEqualTo("java");
            assertThat(service.getBoolean("embedded")).isFalse();
            assertThat(service.getLong("requests")).isEqualTo(2);
            assertThat(service.getLong("errors")).isEqualTo(1);
            assertThat(service.getArray("sparkline").size()).isGreaterThan(0);
            assertThat(service.getBoolean("hasJvm")).isFalse();
            assertThat(service.getDouble("apdex")).isEqualTo(0.5);
            assertThat(counts(service.getArray("histogram"))).containsExactly(0L, 1L, 0L, 0L, 1L);

            Json.JsonObject detail = json(client.get("/api/services/spring-orders" + windowQuery()));
            assertThat(detail.getObject("resource").getString("service.name")).isEqualTo("spring-orders");
            assertThat(detail.getObject("series").getArray("p95Ms").size())
                    .isEqualTo(detail.getObject("series").getArray("t").size());
            assertThat(detail.getArray("endpoints")).hasSize(2);
            Json.JsonObject ok = null;
            for (Json.JsonValue value : detail.getArray("endpoints")) {
                if (value.asObject().getString("name").equals("GET /orders/{id}")) {
                    ok = value.asObject();
                }
            }
            assertThat(ok).isNotNull();
            assertThat(ok.getDouble("apdex")).isEqualTo(1.0);
            assertThat(counts(ok.getArray("histogram"))).containsExactly(0L, 1L, 0L, 0L, 0L);
            assertThat(detail.getArray("queries")).hasSize(1);
            assertThat(detail.getArray("errors")).hasSize(1);
            Json.JsonObject dependency = detail.getArray("dependencies").get(0).asObject();
            assertThat(dependency.getString("kind")).isEqualTo("db");
            assertThat(dependency.getString("target")).isEqualTo("h2:orders");

            Json.JsonObject endpoints = json(client.get("/api/endpoints" + windowQuery()));
            Json.JsonObject endpoint = endpoints.getArray("endpoints").get(0).asObject();
            assertThat(endpoint.getString("name")).isIn("GET /orders/{id}", "POST /orders/{id}/ship");
            assertThat(endpoint.getString("method")).isIn("GET", "POST");
            assertThat(endpoint.getObject("statusCodes").size()).isEqualTo(1);
            Json.JsonObject endpointDetail =
                    json(client.get("/api/endpoints/" + endpoint.getString("endpointId") + windowQuery()));
            assertThat(endpointDetail.getObject("endpoint").getString("endpointId"))
                    .isEqualTo(endpoint.getString("endpointId"));
            assertThat(endpointDetail.has("recent")).isTrue();

            Json.JsonObject queries = json(client.get("/api/queries" + windowQuery()));
            Json.JsonObject query = queries.getArray("queries").get(0).asObject();
            assertThat(query.getString("statement")).isEqualTo("select * from orders where name like ?");
            assertThat(query.getString("system")).isEqualTo("h2");
            assertThat(query.getString("namespace")).isEqualTo("orders");
            assertThat(query.getString("operation")).isEqualTo("SELECT");
            assertThat(query.getString("table")).isEqualTo("orders");
            assertThat(query.getLong("slowCalls")).isEqualTo(1);
            assertThat(query.getArray("callers").get(0).asObject().getString("endpoint"))
                    .isEqualTo("GET /orders/{id}");
            assertThat(json(client.get("/api/queries/" + query.getString("queryId") + windowQuery()))
                    .getObject("series").has("calls")).isTrue();

            Json.JsonObject errors = json(client.get("/api/errors" + windowQuery()));
            Json.JsonObject error = errors.getArray("errors").get(0).asObject();
            assertThat(error.getString("type")).isEqualTo("java.lang.IllegalStateException");
            assertThat(error.getString("message")).isEqualTo("Order ? is already shipped");
            assertThat(error.getLong("count")).isEqualTo(1);
            assertThat(error.getObject("sample").getString("stacktrace")).isEqualTo("at Orders.ship(..)");
            Json.JsonObject errorDetail =
                    json(client.get("/api/errors/" + error.getString("errorId") + windowQuery()));
            assertThat(errorDetail.getObject("series").has("count")).isTrue();
            Json.JsonObject cause = errorDetail.getArray("chain").get(0).asObject();
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
            Json.JsonObject detail = json(client.get("/api/endpoints/" + endpointId + windowQuery()));

            List<String> statements = new ArrayList<>();
            for (Json.JsonValue query : detail.getArray("queries")) {
                statements.add(query.asObject().getString("statement"));
            }
            assertThat(statements).containsExactly("select svc_a from t");
            List<String> types = new ArrayList<>();
            for (Json.JsonValue error : detail.getArray("errors")) {
                types.add(error.asObject().getString("type"));
            }
            assertThat(types).containsExactly("java.lang.AError");
        });
    }

    @Test
    void theOverviewAndTheScatterDescribeTheSameWindow() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            Json.JsonObject overview = json(client.get("/api/overview" + windowQuery()));
            assertThat(overview.getObject("totals").getLong("requests")).isEqualTo(2);
            assertThat(overview.getObject("totals").getLong("errors")).isEqualTo(1);
            assertThat(overview.getObject("totals").getDouble("errorRate")).isEqualTo(0.5);
            // A 152 ms request lands in the second bucket, the failing one in the error slot.
            assertThat(overview.getObject("totals").getDouble("apdex")).isEqualTo(0.5);
            assertThat(counts(overview.getObject("totals").getArray("histogram")))
                    .containsExactly(0L, 1L, 0L, 0L, 1L);
            assertThat(overview.getArray("services")).hasSize(1);
            assertThat(overview.getArray("tingles")).isNotEmpty();
            Json.JsonObject tingle = overview.getArray("tingles").get(0).asObject();
            assertThat(tingle.getString("kind")).isIn("slow-request", "slow-query", "error");
            Json.JsonObject series = overview.getObject("series");
            assertThat(series.getArray("requests").size()).isEqualTo(series.getArray("t").size());
            assertThat(series.getArray("p95Ms").size()).isEqualTo(series.getArray("t").size());
            Json.JsonArray seriesHistogram = series.getArray("histogram");
            assertThat(seriesHistogram).hasSize(4);
            for (Json.JsonValue bucket : seriesHistogram) {
                assertThat(bucket.asArray().size()).isEqualTo(series.getArray("t").size());
            }

            Json.JsonObject scatter = json(client.get("/api/scatter" + windowQuery()));
            assertThat(scatter.getBoolean("truncated")).isFalse();
            Json.JsonArray points = scatter.getArray("points");
            assertThat(points).hasSize(2);
            Json.JsonArray point = points.get(0).asArray();
            assertThat(point.size()).isEqualTo(6);
            assertThat(point.get(2).asString()).isEqualTo("spring-orders");
            assertThat(point.get(3).asString()).isIn("GET /orders/{id}", "POST /orders/{id}/ship");

            // Exactly as many points as the limit is a full list, not a cut one.
            Json.JsonObject full = json(client.get("/api/scatter" + windowQuery() + "&limit=2"));
            assertThat(full.getBoolean("truncated")).isFalse();
            assertThat(full.getArray("points")).hasSize(2);
            Json.JsonObject cut = json(client.get("/api/scatter" + windowQuery() + "&limit=1"));
            assertThat(cut.getBoolean("truncated")).isTrue();
            assertThat(cut.getArray("points")).hasSize(1);
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

            Json.JsonObject map = json(client.get("/api/map" + windowQuery()));
            assertThat(map.getObject("window").has("bucketMs")).isTrue();
            assertThat(map.getArray("nodes")).hasSize(4);
            assertThat(node(map, "user").getString("name")).isEqualTo("Clients");
            Json.JsonObject orders = node(map, "svc:spring-orders");
            assertThat(orders.getString("kind")).isEqualTo("service");
            assertThat(orders.getLong("requests")).isEqualTo(1);
            assertThat(orders.getDouble("apdex")).isEqualTo(1.0);
            assertThat(counts(orders.getArray("histogram"))).containsExactly(1L, 0L, 0L, 0L, 0L);
            assertThat(orders.getBoolean("hasJvm")).isFalse();
            assertThat(node(map, "svc:silk-bookstore")).isNotNull();
            Json.JsonObject database = node(map, "db:h2:orders");
            assertThat(database.getString("name")).isEqualTo("h2:orders");
            assertThat(database.getLong("calls")).isEqualTo(1);
            assertThat(database.getDouble("avgMs")).isEqualTo(10.0);
            assertThat(node(map, "http:localhost:8081")).isNull();

            List<String> edges = new ArrayList<>();
            for (Json.JsonValue value : map.getArray("edges")) {
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

            Json.JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            assertThat(jvm.getArray("connectionPools")).hasSize(1);
            Json.JsonObject pool = jvm.getArray("connectionPools").get(0).asObject();
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

            Json.JsonObject catalog = json(client.get("/api/metrics"));
            assertThat(catalog.getArray("metrics").size()).isEqualTo(3);
            Json.JsonObject memory = catalog.getArray("metrics").get(2).asObject();
            assertThat(memory.getString("name")).isEqualTo("jvm.memory.used");
            assertThat(memory.getString("type")).isEqualTo("gauge");
            assertThat(memory.getString("unit")).isEqualTo("By");
            assertThat(memory.getLong("series")).isEqualTo(2);
            assertThat(memory.getArray("services").get(0).asString()).isEqualTo("spring-orders");

            Json.JsonObject series = json(client.get(
                    "/api/metrics/series?name=jvm.memory.used" + windowQuery().replace('?', '&')));
            assertThat(series.getString("name")).isEqualTo("jvm.memory.used");
            assertThat(series.getArray("series")).hasSize(2);
            Json.JsonObject first = series.getArray("series").get(0).asObject();
            assertThat(first.getString("service")).isEqualTo("spring-orders");
            assertThat(first.getArray("t")).hasSize(1);
            assertThat(first.getArray("v").get(0).asDouble()).isIn(1024.0, 2048.0);

            Json.JsonObject filtered = json(client.get("/api/metrics/series?name=jvm.memory.used"
                    + "&attr.jvm.memory.pool.name=G1+Eden+Space" + windowQuery().replace('?', '&')));
            assertThat(filtered.getArray("series")).hasSize(1);

            Json.JsonObject histogram = json(client.get(
                    "/api/metrics/series?name=jvm.gc.duration" + windowQuery().replace('?', '&')));
            Json.JsonObject gc = histogram.getArray("series").get(0).asObject();
            assertThat(gc.has("count")).isTrue();
            assertThat(gc.has("p95")).isTrue();
            assertThat(gc.has("max")).isTrue();
            assertThat(gc.getArray("v").get(0).asDouble()).isEqualTo(0.05);

            Json.JsonObject jvm = json(client.get(
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

            Json.JsonObject detail = json(client.get("/api/traces/" + FAILING_TRACE));
            Json.JsonObject span = detail.getArray("spans").get(0).asObject();
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

            Json.JsonObject jvm = json(client.get(
                    "/api/jvm?service=spring-orders" + windowQuery().replace('?', '&')));
            Json.JsonArray durations = jvm.getArray("gc").get(0).asObject().getArray("durationMs");
            double total = 0;
            for (int i = 0; i < durations.size(); i++) {
                total += durations.get(i).asDouble();
            }
            assertThat(total).isCloseTo(12.0, within(1e-6));
        });
    }

    @Test
    void logsAreListedFilteredAndCorrelatedWithTheirTrace() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());
            postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("spring-orders"),
                    "o.s.boot.StartupInfoLogger",
                    Otlp.log(NOW + 5, 9, "Started OrdersApplication in 2.1 seconds", TRACE, ROOT),
                    Otlp.log(NOW + 6, 17, "Shipping failed", null, null)).toByteArray());

            Json.JsonObject logs = json(client.get("/api/logs" + windowQuery()));
            assertThat(logs.getLong("total")).isEqualTo(2);
            Json.JsonObject newest = logs.getArray("logs").get(0).asObject();
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
            Json.JsonObject page = json(client.get("/api/logs" + windowQuery() + "&limit=1"));
            Json.JsonObject last = page.getArray("logs").get(0).asObject();
            Json.JsonArray next = json(client.get("/api/logs" + windowQuery() + "&limit=1&before="
                    + last.getLong("at") + "&beforeId=" + last.getLong("id"))).getArray("logs");
            assertThat(next.get(0).asObject().getString("body"))
                    .isEqualTo("Started OrdersApplication in 2.1 seconds");

            Json.JsonObject trace = json(client.get("/api/traces/" + TRACE));
            assertThat(trace.getArray("logs")).hasSize(1);
            assertThat(trace.getArray("logs").get(0).asObject().getString("traceId")).isEqualTo(TRACE);
        });
    }

    @Test
    void statusDescribesTheServerItsStorageAndItsCounts() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sampleTraces());

            Json.JsonObject status = json(client.get("/api/status"));
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
            Json.JsonObject storage = status.getObject("storage");
            assertThat(storage.getString("url")).startsWith("jdbc:h2:mem:");
            assertThat(storage.get("path").isNull()).isTrue();
            assertThat(storage.getLong("sizeBytes")).isZero();
            assertThat(storage.getBoolean("fallback")).isFalse();
            assertThat(storage.getLong("droppedBatches")).isZero();
            assertThat(storage.getLong("droppedSpans")).isZero();
            Json.JsonObject counts = status.getObject("counts");
            assertThat(counts.getLong("spans")).isEqualTo(3);
            assertThat(counts.getLong("traces")).isEqualTo(2);
            assertThat(counts.getLong("services")).isEqualTo(1);
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
            Json.JsonObject exported = Json.parse(export.body()).asObject();
            assertThat(exported.getArray("traces")).hasSize(1);
            assertThat(exported.getArray("traces").get(0).asObject().getString("traceId"))
                    .isEqualTo(TRACE);
            // Without traceId the answer is the session document, not a list of traces.
            assertThat(Json.parse(client.get("/api/export" + windowQuery()).body()).asObject()
                    .getArray("spans")).hasSize(3);

            assertThat(client.delete("/api/data").statusCode()).isEqualTo(204);

            Json.JsonObject status = json(client.get("/api/status"));
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
            byte[] ours = Otlp.traces(Otlp.service("silk-bookstore"),
                    Otlp.span(TRACE, ROOT, "GET /api/overview", Span.SpanKind.SPAN_KIND_SERVER, NOW, 3,
                            Otlp.attr("server.port", assembly.app().port())),
                    Otlp.span(FAILING_TRACE, CHILD, "GET /books", Span.SpanKind.SPAN_KIND_SERVER, NOW, 3,
                            Otlp.attr("server.port", 8081))).toByteArray();

            postProtobuf(client, "/v1/traces", ours);

            Json.JsonObject traces = json(client.get("/api/traces" + windowQuery()));
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
