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
 * MCP over the real request path (api.md, "MCP").
 *
 * <p>The promise this file exists to keep is the one in agent.md: a tool call and
 * the {@code format=text} endpoint over the same window answer the same bytes, so
 * a host with no shell reads exactly what an agent with one reads.
 */
class McpApiTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

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

    private static void serve(Body body) {
        Config config = TestStore.config();
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(config);
        try {
            WebTest.test(assembly.app(), client -> body.run(client));
        } finally {
            assembly.store().close();
        }
    }

    private static HttpResponse<String> post(TestClient client, String path, String body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** One JSON-RPC message, and the {@code result} object it answered with. */
    private static Json.JsonObject call(TestClient client, String name, String arguments) {
        HttpResponse<String> response = post(client, "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + name + "\",\"arguments\":" + arguments + "}}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        Json.JsonObject body = Json.parse(response.body()).asObject();
        assertThat(body.has("error")).as("%s", response.body()).isFalse();
        return body.getObject("result");
    }

    private static String text(Json.JsonObject result) {
        return result.getArray("content").get(0).asObject().getString("text");
    }

    /** The same trace AgentApiTest ingests: enough for every rule to have something to say. */
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
        return Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0])).toByteArray();
    }

    private static void ingest(TestClient client) {
        client.send(request -> request
                .uri(URI.create(client.url("/v1/traces")))
                .header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(sample())));
    }

    @Test
    void initializeAndToolsListAnswerOverHttpJustAsTheyDoOverStdio() {
        serve(client -> {
            Json.JsonObject initialized = Json.parse(post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}").body())
                    .asObject().getObject("result");
            assertThat(initialized.getString("protocolVersion")).isEqualTo("2025-06-18");
            assertThat(initialized.getObject("serverInfo").getString("version"))
                    .isEqualTo(ApiRoutes.VERSION);

            Json.JsonArray tools = Json.parse(post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body())
                    .asObject().getObject("result").getArray("tools");
            assertThat(tools.size()).isEqualTo(7);
        });
    }

    /** The point of the whole adapter: one answer, two interfaces. */
    @Test
    void aToolCallAnswersTheSameBytesAsTheTextEndpointForTheSameWindow() {
        serve(client -> {
            ingest(client);

            String overHttp = client.get("/api/findings?since=5m&limit=20&format=text").body();
            String overMcp = text(call(client, "findings", "{\"since\":\"5m\"}"));

            assertThat(overMcp).isEqualTo(overHttp);
            assertThat(overMcp).startsWith("# findings  ");
        });
    }

    @Test
    void checkCarriesItsVerdictBesideTheTextSoAHostNeedNotReadTheHeading() {
        serve(client -> {
            ingest(client);

            Json.JsonObject result = call(client, "check", "{\"since\":\"5m\"}");

            assertThat(text(result)).startsWith("# check  fail");
            assertThat(result.getObject("structuredContent").getBoolean("pass")).isFalse();
            assertThat(result.getObject("structuredContent").getLong("requests")).isEqualTo(1);
            assertThat(result.getBoolean("isError")).isFalse();

            Json.JsonObject relaxed = call(client, "check",
                    "{\"since\":\"5m\",\"maxP95Ms\":100000}");
            assertThat(relaxed.getObject("structuredContent").getBoolean("pass")).isTrue();
        });
    }

    @Test
    void whatTheCliWouldCallNotFoundIsAToolResultAndNotAProtocolError() {
        serve(client -> {
            ingest(client);

            Json.JsonObject missing = call(client, "trace",
                    "{\"traceId\":\"" + "f".repeat(32) + "\"}");
            assertThat(missing.getBoolean("isError")).isTrue();
            assertThat(text(missing)).isEqualTo("No such trace: " + "f".repeat(32));

            Json.JsonObject noMark = call(client, "findings", "{\"since\":\"nowhere\"}");
            assertThat(noMark.getBoolean("isError")).isTrue();
            assertThat(text(noMark)).contains("No mark named nowhere");

            Json.JsonObject badSelector = call(client, "findings", "{\"since\":\"5 minutes\"}");
            assertThat(badSelector.getBoolean("isError")).isTrue();
            assertThat(text(badSelector)).contains("Not a time selector");

            Json.JsonObject refused = call(client, "sql", "{\"sql\":\"DELETE FROM span\"}");
            assertThat(refused.getBoolean("isError")).isTrue();
            assertThat(text(refused)).contains("DELETE").doesNotContain("\n");

            Json.JsonObject badName = call(client, "mark", "{\"name\":\"two words\"}");
            assertThat(badName.getBoolean("isError")).isTrue();
        });
    }

    @Test
    void markAndCompareGoThroughTheSameReportsAsTheCli() {
        serve(client -> {
            assertThat(text(call(client, "mark", "{\"name\":\"before\",\"note\":\"the slow one\"}")))
                    .startsWith("mark before at ").contains("the slow one");
            ingest(client);

            assertThat(text(call(client, "compare",
                    "{\"before\":\"10m\",\"after\":\"before\"}")))
                    .startsWith("# compare  ").contains("## endpoints");

            assertThat(text(call(client, "sql",
                    "{\"sql\":\"SELECT service, COUNT(*) AS spans FROM span GROUP BY service\"}")))
                    .startsWith("# sql  1 rows").contains("| orders | 7 |");
        });
    }

    @Test
    void aNotificationIsAcceptedWithNoBodyAndOnlyPostIsAllowed() {
        serve(client -> {
            HttpResponse<String> notified = post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertThat(notified.statusCode()).isEqualTo(202);
            assertThat(notified.body()).isEmpty();

            HttpResponse<String> got = client.get("/mcp");
            assertThat(got.statusCode()).as("never the single page").isEqualTo(405);
            assertThat(Json.parse(got.body()).asObject().getString("error")).isNotBlank();

            assertThat(client.delete("/mcp").statusCode()).isEqualTo(405);

            // The UI is unaffected: a path with no extension is still index.html.
            assertThat(client.get("/somewhere").statusCode()).isEqualTo(200);
        });
    }

    @Test
    void aBatchAndAMethodThatIsNotHereAreTheirOwnJsonRpcErrors() {
        serve(client -> {
            HttpResponse<String> batch = post(client, "/mcp",
                    "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
            assertThat(batch.statusCode()).isEqualTo(200);
            assertThat(Json.parse(batch.body()).asObject().getObject("error").getLong("code"))
                    .isEqualTo(-32600);

            HttpResponse<String> unknown = post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
            assertThat(Json.parse(unknown.body()).asObject().getObject("error").getLong("code"))
                    .isEqualTo(-32601);
        });
    }
}
