package net.benelog.spidersense.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;

/**
 * The protocol on its own: what {@code initialize} negotiates, what
 * {@code tools/list} publishes, and which mistake is which JSON-RPC error
 * (agent.md, "MCP").
 *
 * <p>No database and no HTTP here — the tool runner is a stub, because every
 * answer a tool gives is {@link McpTools}' business and is tested where it is
 * computed.
 */
class McpServerTest {

    private final List<String> called = new ArrayList<>();
    private Map<String, Object> lastArguments;

    private final McpServer server = new McpServer((name, arguments) -> {
        called.add(name);
        lastArguments = arguments;
        return McpServer.ToolResult.of("# " + name);
    }, "9.9.9");

    private static Json.JsonObject answer(String response) {
        assertThat(response).isNotNull();
        return Json.parse(response).asObject();
    }

    private static String request(long id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    @Test
    void initializeEchoesAKnownProtocolAndFallsBackToTheNewestOtherwise() {
        Json.JsonObject known = answer(server.handle(request(1, "initialize",
                "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}")));
        assertThat(known.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(known.getLong("id")).isEqualTo(1);
        Json.JsonObject result = known.getObject("result");
        assertThat(result.getString("protocolVersion")).isEqualTo("2024-11-05");
        assertThat(result.getObject("capabilities").has("tools")).isTrue();
        assertThat(result.getObject("serverInfo").getString("name")).isEqualTo("spider-sense");
        assertThat(result.getObject("serverInfo").getString("version")).isEqualTo("9.9.9");
        assertThat(result.getString("instructions"))
                .as("the loop, in one paragraph")
                .contains("mark").contains("findings").contains("compare").contains("check")
                .doesNotContain("\n");

        Json.JsonObject unknown = answer(server.handle(request(2, "initialize",
                "{\"protocolVersion\":\"1999-01-01\"}")));
        assertThat(unknown.getObject("result").getString("protocolVersion"))
                .isEqualTo(McpServer.LATEST_PROTOCOL);

        Json.JsonObject silent = answer(server.handle(request(3, "initialize", null)));
        assertThat(silent.getObject("result").getString("protocolVersion"))
                .isEqualTo(McpServer.LATEST_PROTOCOL);
    }

    @Test
    void pingIsAnEmptyResultAndANotificationIsAnsweredWithNothingAtAll() {
        assertThat(answer(server.handle(request(7, "ping", null))).getObject("result").size())
                .isZero();

        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .as("nothing is expected back").isNull();
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"id\":null,"
                + "\"method\":\"notifications/cancelled\"}")).isNull();
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"))
                .as("no id is a notification too").isNull();
    }

    @Test
    void toolsListIsTheSixToolsOfAgentMdWithTheirSchemas() {
        Json.JsonArray tools = answer(server.handle(request(4, "tools/list", null)))
                .getObject("result").getArray("tools");

        Map<String, Json.JsonObject> byName = new LinkedHashMap<>();
        for (Json.JsonValue tool : tools) {
            byName.put(tool.asObject().getString("name"), tool.asObject());
        }
        assertThat(byName.keySet())
                .containsExactly("findings", "trace", "mark", "compare", "check", "sql");

        for (Json.JsonObject tool : byName.values()) {
            assertThat(tool.getString("description")).isNotBlank();
            assertThat(tool.getObject("inputSchema").getString("type")).isEqualTo("object");
        }

        assertThat(required(byName.get("findings"))).isEmpty();
        assertThat(required(byName.get("trace"))).containsExactly("traceId");
        assertThat(required(byName.get("mark"))).containsExactly("name");
        assertThat(required(byName.get("compare"))).containsExactly("before", "after");
        assertThat(required(byName.get("check"))).isEmpty();
        assertThat(required(byName.get("sql"))).containsExactly("sql");

        Json.JsonObject limit = properties(byName.get("findings")).getObject("limit");
        assertThat(limit.getString("type")).isEqualTo("integer");
        assertThat(limit.getDouble("minimum")).isEqualTo(1.0);
        assertThat(limit.getDouble("maximum")).isEqualTo(100.0);
        assertThat(properties(byName.get("sql")).getObject("limit").getDouble("maximum"))
                .isEqualTo(5000.0);
        assertThat(properties(byName.get("trace")).getObject("diff").getString("type"))
                .isEqualTo("string");
        assertThat(properties(byName.get("mark")).getObject("name").getString("pattern"))
                .isEqualTo("^[A-Za-z0-9._-]{1,64}$");
        assertThat(properties(byName.get("check")).getObject("maxP95Ms").getString("type"))
                .isEqualTo("number");
        assertThat(properties(byName.get("findings")).getObject("full").getString("type"))
                .isEqualTo("boolean");
        Json.JsonObject hideAcked = properties(byName.get("findings")).getObject("hideAcked");
        assertThat(hideAcked).as("agent.md's findings tool takes hideAcked").isNotNull();
        assertThat(hideAcked.getString("type")).isEqualTo("boolean");
        assertThat(hideAcked.getString("description")).contains("acknowledged");
    }

    private static Json.JsonObject properties(Json.JsonObject tool) {
        return tool.getObject("inputSchema").getObject("properties");
    }

    private static List<String> required(Json.JsonObject tool) {
        Json.JsonObject schema = tool.getObject("inputSchema");
        List<String> names = new ArrayList<>();
        if (schema.has("required")) {
            for (Json.JsonValue value : schema.getArray("required")) {
                names.add(value.asString());
            }
        }
        return names;
    }

    @Test
    void aToolCallCarriesItsArgumentsTypedAndItsAnswerAsOneTextContent() {
        Json.JsonObject result = answer(server.handle(request(5, "tools/call",
                "{\"name\":\"findings\",\"arguments\":{\"since\":\"before\",\"limit\":5,"
                        + "\"full\":true}}"))).getObject("result");

        assertThat(called).containsExactly("findings");
        assertThat(lastArguments).containsEntry("since", "before")
                .containsEntry("limit", 5L).containsEntry("full", true);
        assertThat(result.getArray("content").get(0).asObject().getString("type")).isEqualTo("text");
        assertThat(result.getArray("content").get(0).asObject().getString("text"))
                .isEqualTo("# findings");
        assertThat(result.getBoolean("isError")).isFalse();
        assertThat(result.has("structuredContent")).isFalse();
    }

    @Test
    void aToolThatFailsIsAResultWithIsErrorRatherThanAProtocolError() {
        McpServer failing = new McpServer(
                (name, arguments) -> McpServer.ToolResult.failed("No such trace: ff"), "0");

        Json.JsonObject answered = answer(failing.handle(request(6, "tools/call",
                "{\"name\":\"trace\",\"arguments\":{\"traceId\":\"ff\"}}")));

        assertThat(answered.has("error")).isFalse();
        assertThat(answered.getObject("result").getBoolean("isError")).isTrue();
        assertThat(answered.getObject("result").getArray("content").get(0).asObject()
                .getString("text")).isEqualTo("No such trace: ff");
    }

    @Test
    void theVerdictOfCheckAlsoTravelsAsStructuredContent() {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("pass", false);
        structured.put("requests", 12L);
        McpServer checking = new McpServer(
                (name, arguments) -> new McpServer.ToolResult("# check  fail", false, structured), "0");

        Json.JsonObject result = answer(checking.handle(request(8, "tools/call",
                "{\"name\":\"check\",\"arguments\":{}}"))).getObject("result");

        assertThat(result.getObject("structuredContent").getBoolean("pass")).isFalse();
        assertThat(result.getObject("structuredContent").getLong("requests")).isEqualTo(12);
    }

    @Test
    void everyKindOfBadMessageIsItsOwnJsonRpcError() {
        assertThat(code(server.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")))
                .as("a batch, which this server does not speak").isEqualTo(-32600);
        assertThat(code(server.handle("\"hello\""))).isEqualTo(-32600);
        assertThat(code(server.handle("not json at all"))).isEqualTo(-32700);

        assertThat(code(server.handle(request(1, "resources/list", null))))
                .as("a method that is not here").isEqualTo(-32601);

        assertThat(code(server.handle(request(2, "tools/call", "{\"name\":\"nonesuch\"}"))))
                .as("an unknown tool").isEqualTo(-32602);
        assertThat(code(server.handle(request(3, "tools/call", "{\"name\":\"trace\"}"))))
                .as("a missing required argument").isEqualTo(-32602);
        assertThat(message(server.handle(request(3, "tools/call", "{\"name\":\"trace\"}"))))
                .contains("traceId");
        assertThat(code(server.handle(request(4, "tools/call",
                "{\"name\":\"findings\",\"arguments\":{\"since\":5}}"))))
                .as("an argument of the wrong type").isEqualTo(-32602);
        assertThat(called).as("nothing reached the tools").isEmpty();

        McpServer broken = new McpServer((name, arguments) -> {
            throw new IllegalStateException("the database went away");
        }, "0");
        assertThat(code(broken.handle(request(5, "tools/call", "{\"name\":\"findings\"}"))))
                .isEqualTo(-32603);
        assertThat(message(broken.handle(request(5, "tools/call", "{\"name\":\"findings\"}"))))
                .isEqualTo("the database went away");
    }

    @Test
    void theIdComesBackExactlyAsItWasGiven() {
        assertThat(answer(server.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}"))
                .getString("id")).isEqualTo("abc");
        assertThat(answer(server.handle("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}"))
                .getLong("id")).isEqualTo(42);
        assertThat(answer(server.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}"))
                .get("id").isNull()).as("a null id is still a request").isTrue();
    }

    private static int code(String response) {
        return (int) answer(response).getObject("error").getLong("code");
    }

    private static String message(String response) {
        return answer(response).getObject("error").getString("message");
    }
}
