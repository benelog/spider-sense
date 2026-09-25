package net.benelog.spidersense.mcp;

import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The Model Context Protocol, as one JSON-RPC dispatcher and nothing else.
 *
 * <p>For a host that has no shell (mcp.adoc). Both transports — {@code POST
 * /mcp} and the {@code mcp} command over stdio — hand a message to
 * {@link #handle(String)} and send back what it returns, so the protocol is
 * written once and neither transport can drift from the other.
 *
 * <p>Nothing here computes an answer. The seven tools are calls on
 * {@link net.benelog.spidersense.api.Reports} through a {@link ToolRunner}, which
 * is also what lets the stdio transport put a proxy behind them, and their text is
 * the same Markdown the CLI prints over the same window.
 *
 * <p>The server is stateless: no session is issued, nothing is remembered between
 * messages, and {@code initialize} is answered the same way whenever it arrives.
 */
public final class McpServer {

    /** What {@code serverInfo.name} says; the same name {@code init --mcp} writes. */
    public static final String NAME = "spider-sense";

    /** Answered when the client asks for a revision this server does not know. */
    public static final String LATEST_PROTOCOL = "2025-06-18";

    /** The revisions whose {@code protocolVersion} is echoed back as it came. */
    private static final List<String> PROTOCOLS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    /**
     * The loop, in one paragraph, for a host that has no skill to read.
     *
     * <p>The skill teaches the same loop to a host with a shell
     * (agent-loop.adoc#choosing-an-interface), so neither host is taught something the other is
     * not; this is the one place it is written for MCP.
     */
    public static final String INSTRUCTIONS = """
            Spider Sense is an observability tool for the local development loop: it watches a JVM application \
            started under its agent and answers in Markdown made to be read by a model. \
            Run the loop: start the application under the agent and confirm it is collecting, \
            call mark to name the moment, exercise the endpoints in question, call findings to read \
            the ranked list of what is worth fixing and trace to open the evidence behind the top one, \
            fix the code and restart if it needs one, call mark again, exercise exactly the same way, \
            then compare the two marks and run check to turn thresholds into a verdict, \
            and once it passes call resolve on the finding you fixed, so it is a regression if it comes back. \
            Keep the window small — since=start covers the run since the application was last \
            restarted and since=<mark> covers what you just exercised, where the default 15m drags in \
            whatever ran before — and never quote a number these tools did not print.""";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    /** One tool's answer: the Markdown, whether it is a failure, and {@code check}'s verdict. */
    public record ToolResult(String text, boolean isError,
            @Nullable Map<String, Object> structured) {

        public static ToolResult of(String text) {
            return new ToolResult(text, false, null);
        }

        /**
         * What the CLI reports with exit code 4 or as a 400: the message on one
         * line, as the tool's own content rather than as a protocol error
         * (mcp.adoc#tools).
         */
        public static ToolResult failed(String message) {
            return new ToolResult(message, true, null);
        }
    }

    /** Where a tool call goes: {@link McpTools} in process, or a proxy over HTTP. */
    public interface ToolRunner {
        ToolResult call(String name, Map<String, Object> arguments);
    }

    /** An argument the client got wrong: a {@code -32602}, never a tool failure. */
    static final class BadArgument extends RuntimeException {
        BadArgument(String message) {
            super(message);
        }
    }

    private final List<Tool> tools;
    private final ToolRunner runner;
    private final String version;

    /**
     * @param tools  the catalogue {@code tools/list} publishes and a call's arguments are read
     *               against, which is {@link McpTools#TOOLS}
     * @param runner where a call that passed the catalogue goes
     */
    public McpServer(List<Tool> tools, ToolRunner runner, String version) {
        this.tools = List.copyOf(tools);
        this.runner = runner;
        this.version = version;
    }

    /**
     * One JSON-RPC message in, one JSON-RPC response out.
     *
     * @return the response as a string, or null when the message was a
     *         notification and there is nothing to answer
     */
    public @Nullable String handle(String message) {
        return handle(message, runner);
    }

    /**
     * The same, with the tool calls of this one message sent somewhere else.
     *
     * <p>The stdio transport uses it to answer a call it could not forward,
     * without building a JSON-RPC envelope of its own.
     */
    public @Nullable String handle(String message, ToolRunner with) {
        Json.JsonValue parsed;
        try {
            parsed = Json.parse(message);
        } catch (RuntimeException e) {
            return error(null, PARSE_ERROR, "Not JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Json.JsonObject request)) {
            // A JSON array is the batch of older revisions, which this server does not
            // speak; anything else is not a request at all.
            return error(null, INVALID_REQUEST,
                    parsed instanceof Json.JsonArray
                            ? "This server does not accept batched messages"
                            : "A request must be a JSON object");
        }
        String method = request.has("method") && request.get("method").isString()
                ? request.getString("method") : null;
        boolean notification = method != null && method.startsWith("notifications/");
        if (!request.has("id") || notification) {
            // No id at all, or a notification: nothing is expected back, so nothing is
            // sent — not even when the method is one this server does not have.
            return null;
        }
        Json.JsonValue id = request.get("id");
        if (method == null) {
            return error(id, INVALID_REQUEST, "A request must carry a method");
        }
        Json.JsonObject params = request.has("params") && request.get("params") instanceof Json.JsonObject given
                ? given : Json.obj();
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, Json.obj());
                case "tools/list" -> result(id, Json.obj().put("tools", list()));
                case "tools/call" -> result(id, call(params, with));
                default -> error(id, METHOD_NOT_FOUND, "No such method: " + method);
            };
        } catch (BadArgument e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (Json.JsonException e) {
            // A parameter of the wrong JSON type, such as a protocolVersion that is a number.
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (RuntimeException e) {
            String said = e.getMessage();
            return error(id, INTERNAL_ERROR, said == null || said.isBlank() ? e.toString() : said);
        }
    }

    /** Whether this server speaks a protocol revision, as the HTTP transport's header names one. */
    public static boolean speaks(String protocolVersion) {
        return PROTOCOLS.contains(protocolVersion);
    }

    // --- the methods ----------------------------------------------------------

    /**
     * The client's revision when it is one this server knows, and the newest one
     * otherwise, which is what the protocol asks a server to do rather than fail.
     */
    private Json.JsonObject initialize(Json.JsonObject params) {
        String asked = AttrJson.optionalString(params, "protocolVersion");
        String spoken = asked != null && PROTOCOLS.contains(asked) ? asked : LATEST_PROTOCOL;
        return Json.obj()
                .put("protocolVersion", spoken)
                .put("capabilities", Json.obj().put("tools", Json.obj()))
                .put("serverInfo", Json.obj().put("name", NAME).put("version", version))
                .put("instructions", INSTRUCTIONS);
    }

    private Json.JsonObject call(Json.JsonObject params, ToolRunner with) {
        String name = params.has("name") && params.get("name").isString()
                ? params.getString("name") : null;
        Tool tool = byName(name);
        if (tool == null) {
            return failIfUnknown(name);
        }
        Json.JsonValue given = params.has("arguments") ? params.get("arguments") : null;
        if (given != null && !given.isNull() && !(given instanceof Json.JsonObject)) {
            throw new BadArgument("arguments must be an object");
        }
        Map<String, Object> arguments = tool.read(given instanceof Json.JsonObject object ? object : Json.obj());
        ToolResult answer = with.call(tool.name(), arguments);
        Json.JsonObject result = Json.obj()
                .put("content", Json.arr().add(Json.obj()
                        .put("type", "text")
                        .put("text", answer.text() == null ? "" : answer.text())))
                .put("isError", answer.isError());
        if (answer.structured() != null) {
            result.put("structuredContent", structured(answer.structured()));
        }
        return result;
    }

    private Json.JsonObject failIfUnknown(@Nullable String name) {
        throw new BadArgument(name == null
                ? "tools/call needs a tool name"
                : "No such tool: " + name + "; the tools are "
                        + String.join(", ", tools.stream().map(Tool::name).toList()));
    }

    private Json.JsonArray list() {
        Json.JsonArray list = Json.arr();
        for (Tool tool : tools) {
            list.add(tool.schema());
        }
        return list;
    }

    private @Nullable Tool byName(@Nullable String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private static Json.JsonObject structured(Map<String, Object> values) {
        Json.JsonObject object = Json.obj();
        values.forEach((key, value) -> {
            if (value == null) {
                object.putNull(key);
            } else if (value instanceof Boolean flag) {
                object.put(key, (boolean) flag);
            } else if (value instanceof Double number) {
                object.put(key, (double) number);
            } else if (value instanceof Number number) {
                object.put(key, number.longValue());
            } else {
                object.put(key, String.valueOf(value));
            }
        });
        return object;
    }

    // --- the envelope ---------------------------------------------------------

    private static String result(Json.@Nullable JsonValue id, Json.JsonObject payload) {
        return envelope(id).put("result", payload).toJson();
    }

    private static String error(Json.@Nullable JsonValue id, int code,
            @Nullable String message) {
        return envelope(id).put("error", Json.obj()
                .put("code", code)
                .put("message", message == null ? "" : message)).toJson();
    }

    /** The id is echoed as it came — a number stays a number, a string a string. */
    private static Json.JsonObject envelope(Json.@Nullable JsonValue id) {
        Json.JsonObject object = Json.obj().put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            object.putNull("id");
        } else {
            object.put("id", id);
        }
        return object;
    }
}
