package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.store.Schema;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * {@code GET /api/export} and {@code POST /api/import}: a session out of one
 * store and into another (cli.adoc#export-import).
 *
 * <p>The test that matters is the round trip — a trace read back out of the
 * second store must be the trace the first one served — and after it the second
 * import of the same file, because a document an agent keeps will be imported
 * twice sooner or later.
 */
class ExportImportTest {

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
        void run(TestClient client);
    }

    /** One assembled server per call, on an in-memory database of its own. */
    private static void serve(Body body) {
        Config config = TestStore.config();
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(config);
        try {
            WebTest.test(assembly.app(), body::run);
        } finally {
            assembly.store().close();
        }
    }

    private static String window() {
        return "?from=" + (NOW - 60_000) + "&to=" + (NOW + 60_000);
    }

    private static HttpResponse<String> postProtobuf(TestClient client, String path, byte[] body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", PROTOBUF)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private static HttpResponse<String> postJson(TestClient client, String path, String body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** One number out of the store, for the tables the API has no list for. */
    private static long rows(TestClient client, String statement) {
        HttpResponse<String> response = postJson(client, "/api/sql",
                Json.obj().put("sql", statement).put("limit", 1).toJson());
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body()).asObject().getArray("rows").get(0).asArray()
                .get(0).asLong();
    }

    /**
     * A slow database child, a failing request, a log, a metric series, a mark,
     * and the index catalog of the table the slow statement reads.
     */
    private static void fill(TestClient client) {
        Span.Builder root = Otlp.span(TRACE, ROOT, "GET /orders/{id}", Span.SpanKind.SPAN_KIND_SERVER,
                NOW, 152,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        Span.Builder query = Otlp.child(root, CHILD, "SELECT orders", Span.SpanKind.SPAN_KIND_CLIENT,
                NOW + 10, 200,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from orders where name like ?"),
                Otlp.attr("db.name", "orders"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "orders"));
        Span.Builder failing = Otlp.failing(
                Otlp.span(FAILING_TRACE, "00f067aa0ba902b9", "POST /orders/{id}/ship",
                        Span.SpanKind.SPAN_KIND_SERVER, NOW + 100, 900,
                        Otlp.attr("http.request.method", "POST"),
                        Otlp.attr("http.route", "/orders/{id}/ship"),
                        Otlp.attr("http.response.status_code", 500)),
                "java.lang.IllegalStateException", "Order 42 is already shipped", "at Orders.ship(..)");
        postProtobuf(client, "/v1/traces",
                Otlp.traces(Otlp.service("spring-orders"), root, query, failing).toByteArray());
        postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("spring-orders"),
                "o.s.boot.StartupInfoLogger",
                Otlp.log(NOW + 5, 9, "Started OrdersApplication in 2.1 seconds", TRACE, ROOT),
                Otlp.log(NOW + 6, 17, "Shipping failed", null, null)).toByteArray());
        postProtobuf(client, "/v1/metrics", Otlp.gauge(Otlp.service("spring-orders"),
                "jvm.memory.used", "By", NOW, 1024,
                Otlp.attr("jvm.memory.type", "heap"),
                Otlp.attr("jvm.memory.pool.name", "G1 Eden Space")).toByteArray());
        postProtobuf(client, "/v1/metrics", Otlp.gauge(Otlp.service("spring-orders"),
                "jvm.memory.used", "By", NOW + 1000, 2048,
                Otlp.attr("jvm.memory.type", "heap"),
                Otlp.attr("jvm.memory.pool.name", "G1 Eden Space")).toByteArray());
        postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("spring-orders"), "spider-sense",
                Otlp.log(NOW - 30_000, 9, "index catalog of ORDERS", null, null,
                        Otlp.attr("spidersense.schema.table", "ORDERS"),
                        Otlp.attr("spidersense.schema.schema", "PUBLIC"),
                        Otlp.attr("spidersense.schema.product", "H2"),
                        Otlp.attr("spidersense.schema.indexes",
                                "[{\"name\":\"PRIMARY_KEY_8\",\"unique\":true,\"columns\":[\"ID\"]}]")))
                .toByteArray());
        assertThat(postJson(client, "/api/marks",
                Json.obj().put("name", "before").put("at", NOW + 20).toJson()).statusCode())
                .isEqualTo(201);
    }

    @Test
    void theWindowIsExportedAsTheDocumentAgentMdNames() {
        serve(client -> {
            fill(client);

            HttpResponse<String> response = client.get("/api/export" + window());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-disposition"))
                    .hasValue("attachment; filename=\"spider-sense-" + (NOW - 60_000) + "-"
                            + (NOW + 60_000) + ".json\"");

            Json.JsonObject document = Json.parse(response.body()).asObject();
            Json.JsonObject header = document.getObject("spiderSense");
            assertThat(header.getLong("schema")).isEqualTo(Schema.VERSION);
            assertThat(header.getString("version")).isEqualTo(ApiRoutes.VERSION);
            assertThat(header.getObject("window").getLong("from")).isEqualTo(NOW - 60_000);
            assertThat(header.get("service").isNull()).isTrue();

            assertThat(document.getArray("services")).hasSize(1);
            assertThat(document.getArray("spans")).hasSize(3);
            assertThat(document.getArray("logs")).hasSize(2);
            assertThat(document.getArray("metrics")).hasSize(1);
            assertThat(document.getArray("metricSeries")).hasSize(1);
            assertThat(document.getArray("metricPoints")).hasSize(2);
            assertThat(document.getArray("tingles")).isNotEmpty();
            assertThat(document.getArray("marks")).hasSize(1);
            assertThat(document.getArray("dbTables")).hasSize(1);

            Json.JsonObject table = document.getArray("dbTables").get(0).asObject();
            assertThat(table.getString("service")).isEqualTo("spring-orders");
            assertThat(table.getString("schemaName")).isEqualTo("PUBLIC");
            assertThat(table.getString("tableName")).isEqualTo("ORDERS");
            assertThat(table.getString("product")).isEqualTo("H2");
            assertThat(table.getLong("seenMs")).isEqualTo(NOW - 30_000);
            assertThat(table.getArray("indexes").get(0).asObject().getString("name"))
                    .isEqualTo("PRIMARY_KEY_8");

            Json.JsonObject span = document.getArray("spans").get(1).asObject();
            assertThat(span.getString("spanId")).isEqualTo(CHILD);
            assertThat(span.getBoolean("slow")).isTrue();
            assertThat(span.getBoolean("entry")).isFalse();
            assertThat(span.getString("queryId")).isNotBlank();
            assertThat(span.getObject("attributes").getString("db.system")).isEqualTo("h2");
            assertThat(span.get("events").asArray()).isEmpty();
            assertThat(span.get("httpStatus").isNull()).isTrue();

            assertThat(document.getArray("marks").get(0).asObject().getString("name"))
                    .isEqualTo("before");
            assertThat(document.getArray("metricPoints").get(0).asObject().getLong("atMs"))
                    .isEqualTo(NOW);
        });
    }

    @Test
    void theDocumentImportsBackAndImportingItTwiceChangesNothing() {
        String[] document = new String[1];
        String[] trace = new String[1];
        serve(client -> {
            fill(client);
            document[0] = client.get("/api/export" + window()).body();
            trace[0] = client.get("/api/traces/" + TRACE).body();
        });

        serve(client -> {
            HttpResponse<String> first = postJson(client, "/api/import", document[0]);
            assertThat(first.statusCode()).isEqualTo(200);
            Json.JsonObject counts = Json.parse(first.body()).asObject();
            assertThat(counts.getLong("spans")).isEqualTo(3);
            assertThat(counts.getLong("logs")).isEqualTo(2);
            assertThat(counts.getLong("metricPoints")).isEqualTo(2);
            assertThat(counts.getLong("tingles")).isPositive();
            assertThat(counts.getLong("marks")).isEqualTo(1);
            assertThat(counts.getLong("dbTables")).isEqualTo(1);
            assertThat(counts.getLong("skippedTraces")).isZero();
            assertThat(counts.getObject("window").getLong("from")).isEqualTo(NOW);

            assertThat(client.get("/api/traces/" + TRACE).body()).isEqualTo(trace[0]);

            Json.JsonObject status = Json.parse(client.get("/api/status").body()).asObject();
            assertThat(status.getObject("counts").getLong("spans")).isEqualTo(3);
            assertThat(status.getObject("counts").getLong("traces")).isEqualTo(2);
            assertThat(status.getObject("counts").getLong("logs")).isEqualTo(2);
            assertThat(status.getObject("counts").getLong("metricSeries")).isEqualTo(1);
            assertThat(status.getObject("counts").getLong("services")).isEqualTo(1);

            long tingles = rows(client, "SELECT COUNT(*) FROM tingle");

            HttpResponse<String> again = postJson(client, "/api/import", document[0]);
            assertThat(again.statusCode()).isEqualTo(200);
            Json.JsonObject second = Json.parse(again.body()).asObject();
            assertThat(second.getLong("skippedTraces")).isEqualTo(2);
            assertThat(second.getLong("spans")).isZero();
            assertThat(second.getLong("logs")).as("the line outside a span is already stored too").isZero();
            assertThat(second.getLong("tingles")).isZero();
            assertThat(second.getLong("marks")).isZero();
            assertThat(second.getLong("metricPoints")).isEqualTo(2);
            assertThat(second.getLong("dbTables")).isEqualTo(1);
            assertThat(rows(client, "SELECT COUNT(*) FROM db_table"))
                    .as("merged on its key, not added again").isEqualTo(1);

            Json.JsonObject after = Json.parse(client.get("/api/status").body()).asObject();
            assertThat(after.getObject("counts").getLong("spans")).isEqualTo(3);
            assertThat(after.getObject("counts").getLong("logs")).isEqualTo(2);
            assertThat(after.getObject("counts").getLong("metricSeries")).isEqualTo(1);
            assertThat(Json.parse(client.get("/api/marks").body()).asObject().getArray("marks"))
                    .hasSize(1);
            assertThat(rows(client, "SELECT COUNT(*) FROM tingle")).isEqualTo(tingles);
            assertThat(rows(client, "SELECT COUNT(*) FROM metric_point")).isEqualTo(2);
            assertThat(client.get("/api/traces/" + TRACE).body()).isEqualTo(trace[0]);
        });
    }

    /**
     * A log line outside any span and a tingle without a trace have no trace to be
     * skipped with, so they are recognised by their own columns, and two identical
     * lines of the file stay two lines.
     */
    @Test
    void linesAndTinglesWithoutATraceAreNotImportedTwice() {
        String[] document = new String[1];
        serve(client -> {
            fill(client);
            Json.JsonObject exported = Json.parse(client.get("/api/export" + window()).body()).asObject();
            Json.JsonArray logs = exported.getArray("logs");
            for (int i = 0; i < logs.size(); i++) {
                if (logs.get(i).asObject().get("traceId").isNull()) {
                    // The same line logged twice in one millisecond.
                    logs.add(Json.parse(logs.get(i).toJson()));
                    break;
                }
            }
            exported.getArray("tingles").add(Json.obj()
                    .put("atMs", NOW + 7).put("kind", "error").put("service", "spring-orders")
                    .put("title", "Shipping failed").put("detail", "outside any request")
                    .putNull("traceId").putNull("spanId").put("durationMs", 0));
            document[0] = exported.toJson();
        });

        serve(client -> {
            Json.JsonObject first = Json.parse(postJson(client, "/api/import", document[0]).body()).asObject();
            assertThat(first.getLong("logs")).isEqualTo(3);
            long tingles = rows(client, "SELECT COUNT(*) FROM tingle");
            assertThat(tingles).isEqualTo(first.getLong("tingles"));

            Json.JsonObject second = Json.parse(postJson(client, "/api/import", document[0]).body()).asObject();
            assertThat(second.getLong("logs")).isZero();
            assertThat(second.getLong("tingles")).isZero();
            assertThat(rows(client, "SELECT COUNT(*) FROM log")).isEqualTo(3);
            assertThat(rows(client, "SELECT COUNT(*) FROM tingle")).isEqualTo(tingles);
        });
    }

    /**
     * The point of carrying the catalog: a slow-query finding computed over the
     * imported session has the schema block the original one had (findings.adoc#schema).
     */
    @Test
    void aSlowQueryOfTheImportedSessionHasItsSchemaBlock() {
        String[] document = new String[1];
        String[] original = new String[1];
        serve(client -> {
            fill(client);
            document[0] = client.get("/api/export" + window()).body();
            original[0] = slowQuerySchema(client).toJson();
        });

        serve(client -> {
            assertThat(postJson(client, "/api/import", document[0]).statusCode()).isEqualTo(200);

            Json.JsonObject schema = slowQuerySchema(client);
            assertThat(schema.toJson()).isEqualTo(original[0]);
            Json.JsonObject table = schema.getArray("tables").get(0).asObject();
            assertThat(table.getString("table")).isEqualTo("ORDERS");
            assertThat(table.getArray("indexes")).hasSize(1);
            assertThat(schema.getArray("unindexed").get(0).asString()).isEqualTo("orders.name");
            assertThat(client.get("/api/findings" + window() + "&format=text").body())
                    .contains("   indexes ORDERS: PRIMARY_KEY_8 (ID) unique\n");
        });
    }

    private static Json.JsonObject slowQuerySchema(TestClient client) {
        for (Json.JsonValue value : Json.parse(client.get("/api/findings" + window()).body())
                .asObject().getArray("findings")) {
            Json.JsonObject finding = value.asObject();
            if ("slow-query".equals(finding.getString("kind"))) {
                return finding.getObject("schema");
            }
        }
        throw new AssertionError("no slow-query finding in the window");
    }

    /**
     * An import neither brings a start mark in nor gives a running service the
     * file's process id, either of which would move {@code since=start}.
     */
    @Test
    void anImportLeavesTheRunningServicesStartAlone() {
        serve(client -> {
            postProtobuf(client, "/v1/traces", Otlp.traces(
                    Otlp.resource(Otlp.attr("service.name", "spring-orders"), Otlp.attr("process.pid", 100)),
                    Otlp.span(TRACE, ROOT, "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW, 5)).toByteArray());
            long starts = rows(client, "SELECT COUNT(*) FROM mark WHERE name = 'start'");

            String document = Json.obj()
                    .put("spiderSense", Json.obj().put("schema", Schema.VERSION))
                    .put("services", Json.arr().add(Json.obj().put("name", "spring-orders").put("pid", 200)
                            .put("firstSeen", NOW - 60_000).put("lastSeen", NOW - 30_000)))
                    .put("marks", Json.arr()
                            .add(Json.obj().put("atMs", NOW + 50).put("name", "start").put("service", "spring-orders"))
                            .add(Json.obj().put("atMs", NOW + 60).put("name", "before")))
                    .toJson();
            assertThat(postJson(client, "/api/import", document).statusCode()).isEqualTo(200);
            postProtobuf(client, "/v1/traces", Otlp.traces(
                    Otlp.resource(Otlp.attr("service.name", "spring-orders"), Otlp.attr("process.pid", 100)),
                    Otlp.span(FAILING_TRACE, ROOT, "GET /orders", Span.SpanKind.SPAN_KIND_SERVER, NOW + 100, 5))
                    .toByteArray());

            assertThat(rows(client, "SELECT pid FROM service WHERE name = 'spring-orders'")).isEqualTo(100);
            assertThat(rows(client, "SELECT COUNT(*) FROM mark WHERE name = 'start'")).isEqualTo(starts);
            assertThat(rows(client, "SELECT COUNT(*) FROM mark WHERE name = 'before'")).isEqualTo(1);
            assertThat(rows(client, "SELECT MIN(first_seen) FROM service WHERE name = 'spring-orders'"))
                    .isEqualTo(NOW - 60_000);
        });
    }

    @Test
    void aDocumentOfAnotherSchemaVersionIsRefused() {
        serve(client -> {
            String document = Json.obj()
                    .put("spiderSense", Json.obj().put("schema", 1))
                    .put("spans", Json.arr())
                    .toJson();
            HttpResponse<String> response = postJson(client, "/api/import", document);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(Json.parse(response.body()).asObject().getString("error"))
                    .contains("schema version 1")
                    .contains("schema version " + Schema.VERSION);

            assertThat(postJson(client, "/api/import", "not json at all").statusCode())
                    .isEqualTo(400);
        });
    }

    @Test
    void aDocumentWhoseRowsAreNotTheExportedShapesIsRefused() {
        serve(client -> {
            String document = Json.obj()
                    .put("spiderSense", Json.obj().put("schema", Schema.VERSION))
                    .put("spans", Json.arr().add("not a span"))
                    .toJson();
            HttpResponse<String> response = postJson(client, "/api/import", document);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(Json.parse(response.body()).asObject().getString("error"))
                    .startsWith("Undecodable import document");
        });
    }
}
