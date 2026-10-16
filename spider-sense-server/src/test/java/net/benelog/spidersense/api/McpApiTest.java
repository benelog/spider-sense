package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.mcp.McpServer;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.server.Version;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
import net.benelog.silkjson.JsonArray;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * MCP over the real request path (api.adoc#mcp).
 *
 * <p>The promise this file exists to keep is the one in mcp.adoc: a tool call and
 * the {@code format=text} endpoint over the same window answer the same bytes, so
 * a host with no shell reads exactly what an agent with one reads.
 */
class McpApiTest {

    /** Where the telemetry sits: a fixed instant, so a window never depends on how fast the run is. */
    private static final long NOW = 1_700_000_000_000L;
    /** The server's clock, five seconds after it, which is what since=5m counts back from. */
    private final AtomicLong clock = new AtomicLong(NOW + 5_000);
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private interface Body {
        void run(TestClient client);
    }

    private void serve(Body body) {
        Config config = TestStore.config("--await-writes");
        SpiderSenseServer.Assembly assembly = SpiderSenseServer.assemble(config, clock::get);
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

    /**
     * A body past the limit, sent after {@code 100 Continue} only: the server refuses it on its
     * {@code Content-Length} and closes the connection, so a body already on its way could reset
     * the connection before the client reads the 413.
     */
    private static HttpResponse<String> postTooLarge(TestClient client, String path, String body) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Content-Type", "application/json")
                .expectContinue(true)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** One JSON-RPC message, and the {@code result} object it answered with. */
    private static JsonObject call(TestClient client, String name, String arguments) {
        HttpResponse<String> response = post(client, "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + name + "\",\"arguments\":" + arguments + "}}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        JsonObject body = Json.parse(response.body()).asObject();
        assertThat(body.has("error")).as("%s", response.body()).isFalse();
        return body.getObject("result");
    }

    private static String text(JsonObject result) {
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
            JsonObject initialized = Json.parse(post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}").body())
                    .asObject().getObject("result");
            assertThat(initialized.getString("protocolVersion")).isEqualTo("2025-06-18");
            assertThat(initialized.getObject("serverInfo").getString("version"))
                    .isEqualTo(Version.CURRENT);

            JsonArray tools = Json.parse(post(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body())
                    .asObject().getObject("result").getArray("tools");
            assertThat(tools.size()).isEqualTo(8);
        });
    }

    /** The Streamable HTTP transport: a revision named in the header that the server does not speak is a 400. */
    @Test
    void anUnsupportedProtocolVersionHeaderIs400() {
        serve(client -> {
            String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            HttpResponse<String> unknown = client.send(request -> request
                    .uri(URI.create(client.url("/mcp")))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "1999-01-01")
                    .POST(HttpRequest.BodyPublishers.ofString(ping)));
            assertThat(unknown.statusCode()).isEqualTo(400);

            HttpResponse<String> known = client.send(request -> request
                    .uri(URI.create(client.url("/mcp")))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", McpServer.LATEST_PROTOCOL)
                    .POST(HttpRequest.BodyPublishers.ofString(ping)));
            assertThat(known.statusCode()).isEqualTo(200);
        });
    }

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

    /**
     * An empty service is no filter, as {@code service=} is over HTTP: a model fills an optional
     * argument with "" as often as it leaves it out, and a service named "" answers nothing.
     */
    @Test
    void anEmptyServiceIsNoFilterAsItIsOverHttp() {
        serve(client -> {
            ingest(client);
            text(call(client, "mark", "{\"name\":\"before\",\"service\":\"\"}"));
            clock.addAndGet(1_000);

            assertThat(text(call(client, "findings", "{\"since\":\"5m\",\"service\":\"\"}")))
                    .isEqualTo(client.get("/api/findings?since=5m&limit=20&service=&format=text").body())
                    .isEqualTo(text(call(client, "findings", "{\"since\":\"5m\"}")));
            assertThat(text(call(client, "compare", "{\"before\":\"10m\",\"after\":\"before\",\"service\":\"\"}")))
                    .isEqualTo(client.get("/api/compare?before=10m&after=before&service=&format=text").body())
                    .contains("(all services)");
            JsonObject checked = call(client, "check", "{\"since\":\"5m\",\"service\":\"\"}");
            assertThat(text(checked)).isEqualTo(client.get("/api/check?since=5m&service=&format=text").body());
            assertThat(checked.getObject("structuredContent").getLong("requests")).isEqualTo(1);
            assertThat(text(call(client, "mark", "{\"name\":\"after\",\"service\":\"\"}")))
                    .as("no service, rather than one named \"\"").doesNotContain("(");
        });
    }

    /**
     * An empty since, until, endpoint or diff is left out too, for the same reason: its default
     * applies rather than a refusal of an empty selector, endpoint or trace id.
     */
    @Test
    void anEmptyOptionalArgumentIsLeftOut() {
        serve(client -> {
            ingest(client);
            text(call(client, "mark", "{\"name\":\"before\"}"));
            clock.addAndGet(1_000);

            assertThat(call(client, "findings", "{\"since\":\"\",\"until\":\"\"}").getBoolean("isError")).isFalse();
            assertThat(text(call(client, "findings", "{\"since\":\"\",\"until\":\"\"}")))
                    .isEqualTo(text(call(client, "findings", "{}")));
            JsonObject checked = call(client, "check", "{\"since\":\"5m\",\"endpoint\":\"\"}");
            assertThat(checked.getBoolean("isError")).isFalse();
            assertThat(text(checked))
                    .isEqualTo(text(call(client, "check", "{\"since\":\"5m\"}")))
                    .isEqualTo(client.get("/api/check?since=5m&endpoint=&format=text").body());
            assertThat(text(call(client, "trace", "{\"traceId\":\"" + TRACE + "\",\"diff\":\"\"}")))
                    .isEqualTo(text(call(client, "trace", "{\"traceId\":\"" + TRACE + "\"}")));
            assertThat(text(call(client, "compare", "{\"before\":\"10m\",\"after\":\"before\",\"until\":\"\"}")))
                    .isEqualTo(text(call(client, "compare", "{\"before\":\"10m\",\"after\":\"before\"}")));
        });
    }

    /**
     * The step the instructions name first has a tool: status is /api/status's own text, the
     * server's mode and address included. The database's size is left out of the comparison,
     * because a flush between the two calls may grow it.
     */
    @Test
    void statusIsTheStatusEndpointsText() {
        serve(client -> {
            ingest(client);

            String overHttp = client.get("/api/status?format=text").body();
            String overMcp = text(call(client, "status", "{}"));

            assertThat(overMcp).startsWith("# status\n");
            assertThat(withoutSize(overMcp)).isEqualTo(withoutSize(overHttp));
            assertThat(overMcp).doesNotContain("| mode | file |");
        });
    }

    private static String withoutSize(String text) {
        return text.lines().filter(line -> !line.startsWith("| database size |"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void checkCarriesItsVerdictBesideTheTextSoAHostNeedNotReadTheHeading() {
        serve(client -> {
            ingest(client);

            JsonObject result = call(client, "check", "{\"since\":\"5m\"}");

            assertThat(text(result)).startsWith("# check  fail");
            assertThat(result.getObject("structuredContent").getBoolean("pass")).isFalse();
            assertThat(result.getObject("structuredContent").getLong("requests")).isEqualTo(1);
            assertThat(result.getBoolean("isError")).isFalse();

            JsonObject relaxed = call(client, "check",
                    "{\"since\":\"5m\",\"maxP95Ms\":100000}");
            assertThat(relaxed.getObject("structuredContent").getBoolean("pass")).isTrue();
        });
    }

    @Test
    void whatTheCliWouldCallNotFoundIsAToolResultAndNotAProtocolError() {
        serve(client -> {
            ingest(client);

            JsonObject missing = call(client, "trace",
                    "{\"traceId\":\"" + "f".repeat(32) + "\"}");
            assertThat(missing.getBoolean("isError")).isTrue();
            assertThat(text(missing)).isEqualTo("No such trace: " + "f".repeat(32));

            JsonObject noMark = call(client, "findings", "{\"since\":\"nowhere\"}");
            assertThat(noMark.getBoolean("isError")).isTrue();
            assertThat(text(noMark)).contains("No mark named nowhere");

            JsonObject badSelector = call(client, "findings", "{\"since\":\"5 minutes\"}");
            assertThat(badSelector.getBoolean("isError")).isTrue();
            assertThat(text(badSelector)).contains("Not a time selector");

            JsonObject refused = call(client, "sql", "{\"sql\":\"DELETE FROM span\"}");
            assertThat(refused.getBoolean("isError")).isTrue();
            assertThat(text(refused)).contains("DELETE").doesNotContain("\n");

            JsonObject badName = call(client, "mark", "{\"name\":\"two words\"}");
            assertThat(badName.getBoolean("isError")).isTrue();

            // Below 1 is refused, as POST /api/sql refuses it with a 400.
            for (String limit : new String[] {"0", "-7"}) {
                JsonObject belowOne = call(client, "sql", "{\"sql\":\"SELECT 1\",\"limit\":" + limit + "}");
                assertThat(belowOne.getBoolean("isError")).as(limit).isTrue();
                assertThat(text(belowOne)).isEqualTo("limit must be at least 1");
            }
        });
    }

    @Test
    void aLimitPastTwoToTheThirtyOneIsTheMaximumNotOneRow() {
        serve(client -> {
            String threeRows = "SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3";
            for (String limit : new String[] {"4294967297", "4294967296", "1e20"}) {
                assertThat(text(call(client, "sql", "{\"sql\":\"" + threeRows + "\",\"limit\":" + limit + "}")))
                        .as(limit).startsWith("# sql  3 rows").doesNotContain("truncated");
            }
        });
    }

    @Test
    void markAndCompareGoThroughTheSameReportsAsTheCli() {
        serve(client -> {
            assertThat(text(call(client, "mark", "{\"name\":\"before\",\"note\":\"the slow one\"}")))
                    .startsWith("mark before at ").contains("the slow one");
            ingest(client);
            clock.addAndGet(1_000);

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

    /** mcp.adoc#http: a body past 1 MB is a 413 whose body is still a JSON-RPC message. */
    @Test
    void aBodyPastTheLimitIs413WithAJsonRpcError() {
        serve(client -> {
            HttpResponse<String> refused = postTooLarge(client, "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"sql\","
                            + "\"arguments\":{\"sql\":\"SELECT 1 " + " ".repeat(1024 * 1024) + "\"}}}");
            assertThat(refused.statusCode()).isEqualTo(413);
            assertThat(refused.headers().firstValue("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
            JsonObject body = Json.parse(refused.body()).asObject();
            assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
            assertThat(body.get("id").isNull()).as("the body was never read, so it has no id").isTrue();
            assertThat(body.getObject("error").getLong("code")).isEqualTo(-32600);
            assertThat(body.getObject("error").getString("message"))
                    .isEqualTo("Request body is larger than the limit of 1048576 bytes");
        });
    }
}
