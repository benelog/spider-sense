package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * The agent interface over the real request path: the endpoints, the selectors,
 * and the Markdown rendering that answers instead of JSON when it is asked for.
 */
class AgentApiTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
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

    private static HttpResponse<String> postJson(TestClient client, String path, String body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> get(TestClient client, String path, String accept) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Accept", accept)
                .GET());
    }

    private static Json.JsonObject json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body()).asObject();
    }

    /**
     * One slow endpoint whose trace repeats a statement six times, calls one slow
     * query and ends in an exception: enough for every rule to have something to say.
     */
    private static byte[] sample() {
        Span.Builder root = Otlp.span(TRACE, "00f067aa0ba902b7", "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(Otlp.child(root, "%016x".formatted(100 + i), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        spans.add(Otlp.child(root, "00f067aa0ba902c9", "SELECT book",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW + 10, 300,
                Otlp.attr("db.system", "h2"),
                Otlp.attr("db.statement", "select * from book where title like ?"),
                Otlp.attr("db.operation", "SELECT"),
                Otlp.attr("db.sql.table", "book")));
        spans.add(Otlp.failing(Otlp.child(root, "00f067aa0ba902d1", "load",
                        Span.SpanKind.SPAN_KIND_INTERNAL, NOW + 400, 1),
                "java.lang.IllegalStateException", "no such order 42",
                """
                java.lang.IllegalStateException: no such order 42
                \tat orders.OrderService.load(OrderService.java:41)
                \tat org.springframework.web.servlet.DispatcherServlet.doService(D.java:1)"""));
        return Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])).toByteArray();
    }

    @Test
    void findingsAreRankedAndCarryTheirEvidence() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());

            Json.JsonObject body = json(client.get("/api/findings?since=5m"));

            assertThat(body.getLong("requests")).isEqualTo(1);
            assertThat(body.getObject("window").has("bucketMs")).isTrue();
            Json.JsonArray findings = body.getArray("findings");
            assertThat(findings.size()).isGreaterThanOrEqualTo(4);
            Json.JsonObject first = findings.get(0).asObject();
            assertThat(first.getString("kind")).isEqualTo("error");
            assertThat(first.getString("severity")).isEqualTo("high");
            assertThat(first.getString("id")).startsWith("error:");
            assertThat(first.getObject("subject").getString("errorId")).isNotBlank();
            assertThat(first.getArray("code").get(0).asString())
                    .isEqualTo("orders.OrderService.load(OrderService.java:41)");
            assertThat(first.getArray("traces").get(0).asString()).isEqualTo(TRACE);

            List<String> kinds = new ArrayList<>();
            for (Json.JsonValue value : findings) {
                kinds.add(value.asObject().getString("kind"));
            }
            assertThat(kinds).contains("n-plus-one", "slow-query", "slow-endpoint");
        });
    }

    @Test
    void aSlowJobIsAFindingOfItsOwnAndCountsAsNoRequest() {
        serve((client, assembly) -> {
            Span.Builder tick = Otlp.span("0af7651916cd43dd8448eb211c80319c", "00f067aa0ba90301",
                    "ReportJob.run", Span.SpanKind.SPAN_KIND_INTERNAL, NOW, 900,
                    Otlp.attr("code.namespace", "orders.ReportJob"),
                    Otlp.attr("code.function", "run"));
            postProtobuf(client, "/v1/traces",
                    Otlp.traces(Otlp.service("orders"), tick).toByteArray());

            Json.JsonObject body = json(client.get("/api/findings?since=5m"));

            assertThat(body.getLong("requests")).isZero();
            Json.JsonObject finding = body.getArray("findings").get(0).asObject();
            assertThat(finding.getString("kind")).isEqualTo("slow-job");
            assertThat(finding.getString("title")).isEqualTo("ReportJob.run is slow");
            assertThat(finding.getObject("subject").getString("job")).isEqualTo("ReportJob.run");
            assertThat(finding.getObject("subject").get("endpointId").isNull()).isTrue();
            assertThat(finding.getObject("numbers").getLong("runs")).isEqualTo(1);
            assertThat(finding.getArray("code").get(0).asString()).isEqualTo("orders.ReportJob.run");

            String text = client.get("/api/findings?since=5m&format=text").body();
            assertThat(text).contains("| slow-job |");
            assertThat(text).contains("ReportJob.run is slow");
            assertThat(text).contains("runs 1, p50Ms 900.0");
            assertThat(text).contains("orders.ReportJob.run");
            assertThat(text).contains("traces: 0af7651916cd43dd8448eb211c80319c");
        });
    }

    @Test
    void theTextNamesTheHotSpanAndTheErrorLogsNoTraceReports() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());
            postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("orders"),
                    "orders.web.OrderController",
                    Otlp.log(NOW + 500, 17, "Payment gateway timeout for order 42", null, null))
                    .toByteArray());

            String text = client.get("/api/findings?since=5m&format=text").body();

            assertThat(text).contains("| log-error |");
            assertThat(text).contains("ERROR in OrderController: Payment gateway timeout for order ?");
            assertThat(text).contains("hot span: GET /orders/{id} → 200 · 587.0 ms self · 65.2%");
            assertThat(text).as("the object is a line of its own, never a pair on the numbers line")
                    .doesNotContain("hotSpan");

            Json.JsonObject body = json(client.get("/api/findings?since=5m"));
            Json.JsonObject logError = null;
            Json.JsonObject slowEndpoint = null;
            for (Json.JsonValue value : body.getArray("findings")) {
                Json.JsonObject finding = value.asObject();
                if ("log-error".equals(finding.getString("kind"))) {
                    logError = finding;
                } else if ("slow-endpoint".equals(finding.getString("kind"))) {
                    slowEndpoint = finding;
                }
            }
            assertThat(logError).isNotNull();
            assertThat(logError.getObject("subject").getString("logger"))
                    .isEqualTo("orders.web.OrderController");
            assertThat(logError.getObject("numbers").getLong("count")).isEqualTo(1);
            assertThat(slowEndpoint).isNotNull();
            Json.JsonObject hot = slowEndpoint.getObject("numbers").getObject("hotSpan");
            assertThat(hot.getString("category")).isEqualTo("http");
            assertThat(hot.getDouble("selfMs")).isEqualTo(587.0);
        });
    }

    /** The index catalog of one table, as the extension exports it: a log record. */
    private static byte[] catalog(String table, String indexes) {
        return Otlp.logs(Otlp.service("orders"), "spider-sense",
                Otlp.log(NOW, 9, "index catalog of " + table, null, null,
                        Otlp.attr("spidersense.schema.table", table),
                        Otlp.attr("spidersense.schema.schema", "PUBLIC"),
                        Otlp.attr("spidersense.schema.product", "H2"),
                        Otlp.attr("spidersense.schema.indexes", indexes)))
                .toByteArray();
    }

    /**
     * The schema block of agent.md: three lines under a finding, a column in the
     * queries table, and the same block in both JSON answers.
     */
    @Test
    void theSchemaBlockNamesTheIndexesAndTheColumnsNoneLeadsWith() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());
            postProtobuf(client, "/v1/logs", catalog("ORDER_LINE",
                    "[{\"name\":\"PRIMARY_KEY_3\",\"unique\":true,\"columns\":[\"ID\"]},"
                            + "{\"name\":\"IDX_LINE_PRODUCT\",\"unique\":false,"
                            + "\"columns\":[\"PRODUCT_ID\",\"ORDER_ID\"]}]"));
            postProtobuf(client, "/v1/logs", catalog("BOOK", "[]"));

            String text = client.get("/api/findings?since=5m&format=text").body();

            assertThat(text).contains(
                    "   indexes ORDER_LINE: PRIMARY_KEY_3 (ID) unique,"
                            + " IDX_LINE_PRODUCT (PRODUCT_ID, ORDER_ID)\n"
                            + "   predicates: order_line.order_id;"
                            + " unindexed: order_line.order_id\n");
            assertThat(text).as("a table without an index says so")
                    .contains("   indexes BOOK: none\n   predicates: book.title;"
                            + " unindexed: book.title\n");

            String queries = client.get("/api/queries?since=5m&format=text").body();
            assertThat(queries).contains("| callers | unindexed | statement |");
            assertThat(queries).contains("| order_line.order_id | select * from order_line");

            Json.JsonObject slowQuery = null;
            Json.JsonObject slowEndpoint = null;
            for (Json.JsonValue value : json(client.get("/api/findings?since=5m"))
                    .getArray("findings")) {
                Json.JsonObject finding = value.asObject();
                if ("slow-query".equals(finding.getString("kind"))) {
                    slowQuery = finding;
                } else if ("slow-endpoint".equals(finding.getString("kind"))) {
                    slowEndpoint = finding;
                }
            }
            assertThat(slowQuery).isNotNull();
            Json.JsonObject schema = slowQuery.getObject("schema");
            assertThat(schema.getArray("predicates").get(0).asString()).isEqualTo("book.title");
            assertThat(schema.getArray("unindexed").get(0).asString()).isEqualTo("book.title");
            Json.JsonObject table = schema.getArray("tables").get(0).asObject();
            assertThat(table.getString("table")).isEqualTo("BOOK");
            assertThat(table.getString("schema")).isEqualTo("PUBLIC");
            assertThat(table.getArray("indexes")).isEmpty();
            assertThat(slowEndpoint).isNotNull();
            assertThat(slowEndpoint.get("schema").isNull())
                    .as("only a statement has a schema block").isTrue();

            Json.JsonObject group = json(client.get("/api/queries?since=5m"))
                    .getArray("queries").get(0).asObject();
            assertThat(group.getObject("schema").getArray("unindexed")).isNotEmpty();
        });
    }

    /**
     * The three endpoints of agent.md's "Acknowledgements", and what a finding and
     * its heading read like once one of them has been called.
     */
    @Test
    void aFindingIsAcknowledgedRankedLastAndWithdrawnAgain() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());

            Json.JsonObject before = json(client.get("/api/findings?since=5m"));
            assertThat(before.getLong("acked")).isZero();
            Json.JsonArray all = before.getArray("findings");
            String first = all.get(0).asObject().getString("id");
            String last = all.get(all.size() - 1).asObject().getString("id");
            assertThat(all.get(0).asObject().get("ack").isNull()).isTrue();

            HttpResponse<String> created = postJson(client,
                    "/api/findings/" + first + "/ack", "{\"note\":\"known, and accepted\"}");
            assertThat(created.statusCode()).isEqualTo(201);
            Json.JsonObject ack = Json.parse(created.body()).asObject();
            assertThat(ack.getString("findingId")).isEqualTo(first);
            assertThat(ack.getString("note")).isEqualTo("known, and accepted");
            assertThat(ack.getLong("at")).isPositive();

            Json.JsonObject after = json(client.get("/api/findings?since=5m"));
            assertThat(after.getLong("acked")).isEqualTo(1);
            Json.JsonArray ranked = after.getArray("findings");
            assertThat(ranked.get(ranked.size() - 1).asObject().getString("id")).isEqualTo(first);
            assertThat(ranked.get(ranked.size() - 1).asObject().getObject("ack").getString("note"))
                    .isEqualTo("known, and accepted");
            assertThat(ranked.get(0).asObject().getString("id"))
                    .as("the rest keep their order").isNotEqualTo(first);
            assertThat(ranked.get(ranked.size() - 2).asObject().getString("id")).isEqualTo(last);

            String text = client.get("/api/findings?since=5m&format=text").body();
            assertThat(text).contains(", 1 request, 1 acked)");
            assertThat(text).contains("| acked | ");

            Json.JsonArray acks = json(client.get("/api/acks")).getArray("acks");
            assertThat(acks.size()).isEqualTo(1);
            assertThat(acks.get(0).asObject().getString("findingId")).isEqualTo(first);

            Json.JsonObject hidden = json(client.get("/api/findings?since=5m&hideAcked=true"));
            assertThat(hidden.getLong("acked")).isEqualTo(1);
            assertThat(hidden.getArray("findings").size()).isEqualTo(all.size() - 1);
            for (Json.JsonValue value : hidden.getArray("findings")) {
                assertThat(value.asObject().getString("id")).isNotEqualTo(first);
            }

            assertThat(client.delete("/api/findings/" + first + "/ack").statusCode()).isEqualTo(204);
            HttpResponse<String> again = client.delete("/api/findings/" + first + "/ack");
            assertThat(again.statusCode()).isEqualTo(404);
            assertThat(Json.parse(again.body()).asObject().getString("error"))
                    .isEqualTo("No such acknowledgement: " + first);
            assertThat(json(client.get("/api/findings?since=5m")).getLong("acked")).isZero();
        });
    }

    @Test
    void anAcknowledgementTakesNoBodyAndAnEmptyDatabaseTakesItBack() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());
            String id = json(client.get("/api/findings?since=5m"))
                    .getArray("findings").get(0).asObject().getString("id");

            HttpResponse<String> created = client.post("/api/findings/" + id + "/ack");
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(Json.parse(created.body()).asObject().get("note").isNull()).isTrue();

            String text = postJson(client, "/api/findings/" + id + "/ack?format=text", "{}").body();
            assertThat(text).isEqualTo("acked " + id + "\n");

            assertThat(client.delete("/api/data").statusCode()).isEqualTo(204);
            assertThat(json(client.get("/api/acks")).getArray("acks").size()).isZero();
        });
    }

    @Test
    void theFormatIsJsonUnlessTextIsAskedForByParameterOrByAccept() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());

            HttpResponse<String> asJson = client.get("/api/findings?since=5m");
            assertThat(asJson.headers().firstValue("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));

            HttpResponse<String> byParam = client.get("/api/findings?since=5m&format=text");
            assertThat(byParam.statusCode()).isEqualTo(200);
            assertThat(byParam.headers().firstValue("content-type"))
                    .hasValue("text/markdown; charset=utf-8");
            assertThat(byParam.body()).startsWith("# findings  ");
            assertThat(byParam.body()).contains("| severity | kind | id | service | title |");
            assertThat(byParam.body()).contains("traces: " + TRACE);

            HttpResponse<String> byAccept = get(client, "/api/findings?since=5m", "text/markdown");
            assertThat(byAccept.body()).isEqualTo(byParam.body());

            HttpResponse<String> plain = get(client, "/api/findings?since=5m", "text/plain, */*");
            assertThat(plain.headers().firstValue("content-type"))
                    .hasValue("text/markdown; charset=utf-8");

            HttpResponse<String> browser = get(client, "/api/findings?since=5m",
                    "application/json, text/plain, */*");
            assertThat(browser.headers().firstValue("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        });
    }

    @Test
    void anEmptyWindowSaysWhatWasLookedForAndWhereToSendData() {
        serve((client, assembly) -> {
            String text = client.get("/api/findings?since=5m&format=text").body();

            assertThat(text).startsWith("# findings  ");
            assertThat(text).contains("no findings since ");
            assertThat(text).contains("(5m, 0 requests)");
            assertThat(text).contains("/v1/traces");
        });
    }

    @Test
    void aTraceRendersAsATreeWithItsRepeatedSiblingsCollapsed() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());
            postProtobuf(client, "/v1/logs", Otlp.logs(Otlp.service("orders"),
                    "o.s.web.servlet.DispatcherServlet",
                    Otlp.log(NOW + 401, 13, "Resolved [IllegalStateException: no such order 42]",
                            TRACE, "00f067aa0ba902b7")).toByteArray());

            String text = client.get("/api/traces/" + TRACE + "?format=text").body();

            assertThat(text).startsWith("# trace " + TRACE + "  ");
            assertThat(text).contains("offset     duration  span");
            assertThat(text).contains("SERVER orders GET /orders/{id} → 200  [slow]");
            assertThat(text).contains("db SELECT order_line  × 6, 2.0 ms avg, 12.0 ms total");
            assertThat(text).contains("select * from order_line where order_id = ?");
            assertThat(text).contains("db SELECT book  [slow]");
            assertThat(text).contains("exception IllegalStateException: no such order 42");
            assertThat(text).contains("orders.OrderService.load(OrderService.java:41)");
            assertThat(text).contains("logs (1)");
            assertThat(text).contains("Resolved [IllegalStateException: no such order 42]");
            // Deterministic: the same data renders to the same bytes.
            assertThat(client.get("/api/traces/" + TRACE + "?format=text").body()).isEqualTo(text);

            String full = client.get("/api/traces/" + TRACE + "?format=text&full=true").body();
            assertThat(full).doesNotContain("× 6");

            assertThat(client.get("/api/traces/" + "f".repeat(32) + "?format=text").statusCode())
                    .isEqualTo(404);
        });
    }

    @Test
    void marksAreRecordedListedAndValidated() {
        serve((client, assembly) -> {
            HttpResponse<String> created = postJson(client, "/api/marks",
                    "{\"name\":\"before\",\"note\":\"the slow version\"}");
            assertThat(created.statusCode()).isEqualTo(201);
            Json.JsonObject mark = Json.parse(created.body()).asObject();
            assertThat(mark.getString("name")).isEqualTo("before");
            assertThat(mark.getString("note")).isEqualTo("the slow version");
            assertThat(mark.get("service").isNull()).isTrue();
            assertThat(mark.getLong("id")).isPositive();

            HttpResponse<String> bad = postJson(client, "/api/marks", "{\"name\":\"two words\"}");
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(Json.parse(bad.body()).asObject().getString("error")).isNotBlank();

            Json.JsonObject marks = json(client.get("/api/marks"));
            assertThat(marks.getArray("marks")).hasSize(1);

            assertThat(client.get("/api/marks?format=text").body()).contains("| at | name | service | note |");
        });
    }

    @Test
    void aSelectorThatNamesNoMarkIs404AndOneThatIsNotASelectorIs400() {
        serve((client, assembly) -> {
            assertThat(client.get("/api/findings?since=nowhere").statusCode()).isEqualTo(404);
            assertThat(client.get("/api/findings?since=5%20minutes").statusCode()).isEqualTo(400);
            // The UI's own endpoints take the same selectors.
            assertThat(client.get("/api/traces?since=5m").statusCode()).isEqualTo(200);
            assertThat(client.get("/api/traces?since=nowhere").statusCode()).isEqualTo(404);
        });
    }

    @Test
    void compareNeedsBothWindowsAndAnswersOneTableEach() {
        serve((client, assembly) -> {
            assertThat(client.get("/api/compare?before=5m").statusCode()).isEqualTo(400);

            postJson(client, "/api/marks", "{\"name\":\"start-of-run\",\"at\":" + (NOW - 1000) + "}");
            postProtobuf(client, "/v1/traces", sample());

            Json.JsonObject body = json(client.get(
                    "/api/compare?before=" + (NOW - 60_000) + "&after=start-of-run"));
            assertThat(body.getObject("totals").getObject("after").getLong("requests")).isEqualTo(1);
            assertThat(body.getObject("totals").getObject("before").getLong("requests")).isZero();
            Json.JsonObject endpoint = body.getArray("endpoints").get(0).asObject();
            assertThat(endpoint.getString("verdict")).isEqualTo("new");
            assertThat(endpoint.get("before").isNull()).isTrue();
            assertThat(endpoint.getObject("after").getLong("calls")).isEqualTo(1);

            String text = client.get("/api/compare?before=" + (NOW - 60_000)
                    + "&after=start-of-run&format=text").body();
            assertThat(text).startsWith("# compare  ");
            assertThat(text).contains("## endpoints");
            assertThat(text).contains("## queries");
            assertThat(text).contains("## errors");
        });
    }

    @Test
    void checkIsAVerdictAndItsRulesAreQueryParameters() {
        serve((client, assembly) -> {
            Json.JsonObject empty = json(client.get("/api/check?since=5m"));
            assertThat(empty.get("pass").isNull()).isTrue();
            assertThat(empty.getString("reason")).isEqualTo("no requests in the window");

            postProtobuf(client, "/v1/traces", sample());

            Json.JsonObject failed = json(client.get("/api/check?since=5m"));
            assertThat(failed.getBoolean("pass")).isFalse();
            assertThat(failed.getLong("requests")).isEqualTo(1);
            Json.JsonObject first = failed.getArray("checks").get(0).asObject();
            assertThat(first.getString("rule")).isEqualTo("maxP95Ms");
            assertThat(first.getDouble("limit")).isEqualTo(500.0);
            assertThat(first.getDouble("actual")).isEqualTo(900.0);
            assertThat(first.getBoolean("pass")).isFalse();
            assertThat(first.getString("detail")).contains("GET /orders/{id} p95 900.0 ms");

            Json.JsonObject relaxed = json(client.get("/api/check?since=5m&maxP95Ms=2000"));
            assertThat(relaxed.getArray("checks")).hasSize(1);
            assertThat(relaxed.getBoolean("pass")).isTrue();

            assertThat(client.get("/api/check?since=5m&format=text").body())
                    .contains("| rule | limit | actual | verdict | detail |");
        });
    }

    @Test
    void sqlAnswersRowsInBothRenderingsAndSaysWhenItCutThemOff() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());
            String group = "{\"sql\":\"SELECT service, COUNT(*) AS spans FROM span GROUP BY service\"}";

            Json.JsonObject body = json(postJson(client, "/api/sql", group));
            assertThat(body.getArray("columns").get(0).asString()).isEqualTo("SERVICE");
            assertThat(body.getArray("columns").get(1).asString()).isEqualTo("SPANS");
            assertThat(body.getLong("rowCount")).isEqualTo(1);
            assertThat(body.getBoolean("truncated")).isFalse();
            assertThat(body.has("elapsedMs")).isTrue();
            Json.JsonArray row = body.getArray("rows").get(0).asArray();
            assertThat(row.get(0).asString()).isEqualTo("orders");
            assertThat(row.get(1).asLong()).isEqualTo(9);

            String text = postJson(client, "/api/sql?format=text", group).body();
            assertThat(text).startsWith("# sql  1 rows\n\n");
            assertThat(text).contains("| SERVICE | SPANS |");
            assertThat(text).contains("| orders | 9 |");
            assertThat(postJson(client, "/api/sql?format=text", group).body())
                    .as("the same bytes twice").isEqualTo(text);

            String capping = "{\"sql\":\"SELECT id FROM span ORDER BY id\",\"limit\":2}";
            Json.JsonObject capped = json(postJson(client, "/api/sql", capping));
            assertThat(capped.getLong("rowCount")).isEqualTo(2);
            assertThat(capped.getBoolean("truncated")).isTrue();
            assertThat(postJson(client, "/api/sql?format=text", capping).body())
                    .startsWith("# sql  2 rows (truncated at 2)");

            Json.JsonObject typed = json(postJson(client, "/api/sql",
                    "{\"sql\":\"SELECT query_id, entry, duration_ns / 1000000.0 AS ms FROM span"
                            + " WHERE entry\"}"));
            Json.JsonArray entry = typed.getArray("rows").get(0).asArray();
            assertThat(entry.get(0).isNull()).as("a NULL cell is null").isTrue();
            assertThat(entry.get(1).asBoolean()).isTrue();
            assertThat(entry.get(2).asDouble()).isEqualTo(900.0);
            assertThat(postJson(client, "/api/sql?format=text",
                    "{\"sql\":\"SELECT query_id FROM span WHERE entry\"}").body())
                    .contains("| — |");
        });
    }

    @Test
    void sqlRefusesEverythingThatIsNotOneReadAndSaysWhyInTheFormatThatWasAsked() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());

            HttpResponse<String> deleted = postJson(client, "/api/sql",
                    "{\"sql\":\"DELETE FROM span\"}");
            assertThat(deleted.statusCode()).isEqualTo(400);
            assertThat(Json.parse(deleted.body()).asObject().getString("error"))
                    .contains("EXPLAIN, SELECT, SHOW, TABLE, VALUES, WITH")
                    .contains("DELETE");

            HttpResponse<String> two = postJson(client, "/api/sql",
                    "{\"sql\":\"SELECT 1; DELETE FROM span\"}");
            assertThat(two.statusCode()).isEqualTo(400);
            assertThat(Json.parse(two.body()).asObject().getString("error"))
                    .isEqualTo("one statement at a time: there is more after the first ';'");

            HttpResponse<String> broken = postJson(client, "/api/sql",
                    "{\"sql\":\"SELECT nonesuch FROM span\"}");
            assertThat(broken.statusCode()).isEqualTo(400);
            assertThat(Json.parse(broken.body()).asObject().getString("error"))
                    .as("H2's own message, on one line")
                    .contains("NONESUCH").doesNotContain("\n");

            HttpResponse<String> zero = postJson(client, "/api/sql",
                    "{\"sql\":\"SELECT 1\",\"limit\":0}");
            assertThat(zero.statusCode()).isEqualTo(400);
            assertThat(Json.parse(zero.body()).asObject().getString("error"))
                    .isEqualTo("limit must be at least 1");

            HttpResponse<String> asText = postJson(client, "/api/sql?format=text",
                    "{\"sql\":\"DROP TABLE span\"}");
            assertThat(asText.statusCode()).isEqualTo(400);
            assertThat(asText.headers().firstValue("content-type"))
                    .hasValue("text/markdown; charset=utf-8");
            assertThat(asText.body().lines().count()).as("one line").isEqualTo(1);

            assertThat(assembly.store().database().sql()
                    .count("SELECT COUNT(*) FROM span", List.of()))
                    .as("the span table is still there").isEqualTo(9);

            // A ';' in a literal is not a second statement, and a comment is not a keyword.
            Json.JsonObject fine = json(postJson(client, "/api/sql",
                    "{\"sql\":\"-- what is in here\\n  SELECT ';' AS semicolon FROM span WHERE entry\"}"));
            assertThat(fine.getLong("rowCount")).isEqualTo(1);
        });
    }

    @Test
    void theListsTheUiServesAlsoAnswerMarkdown() {
        serve((client, assembly) -> {
            postProtobuf(client, "/v1/traces", sample());

            assertThat(client.get("/api/endpoints?since=5m&format=text").body())
                    .startsWith("# endpoints  ");
            assertThat(client.get("/api/queries?since=5m&format=text").body())
                    .startsWith("# queries  ");
            assertThat(client.get("/api/errors?since=5m&format=text").body())
                    .startsWith("# errors  ");
            assertThat(client.get("/api/traces?since=5m&format=text").body())
                    .startsWith("# traces  ");
            assertThat(client.get("/api/services?since=5m&format=text").body())
                    .startsWith("# services  ");
            assertThat(client.get("/api/logs?since=5m&format=text").body())
                    .startsWith("# logs  ");
            assertThat(client.get("/api/status?format=text").body())
                    .startsWith("# status");

            // JSON is still the default everywhere, with the shapes the UI reads.
            assertThat(json(client.get("/api/endpoints?since=5m")).getArray("endpoints")).hasSize(1);
            assertThat(json(client.get("/api/traces?since=5m")).getLong("total")).isEqualTo(1);
            assertThat(json(client.get("/api/status")).getString("mode")).isEqualTo("standalone");
        });
    }
}
