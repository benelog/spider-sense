package net.benelog.spidersense.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The Model Context Protocol, as one JSON-RPC dispatcher and nothing else.
 *
 * <p>For a host that has no shell (agent.md, "MCP"). Both transports — {@code POST
 * /mcp} and the {@code mcp} command over stdio — hand a message to
 * {@link #handle(String)} and send back what it returns, so the protocol is
 * written once and neither transport can drift from the other.
 *
 * <p>Nothing here computes an answer. The six tools are calls on
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
     * <p>The skill teaches the same loop to a host with a shell (agent.md,
     * "Choosing an interface"), so neither host is taught something the other is
     * not; this is the one place it is written for MCP.
     */
    public static final String INSTRUCTIONS = """
            Spider Sense is an observability tool for the local development loop: it watches a JVM application \
            started under its agent and answers in Markdown made to be read by a model. \
            Run the loop: start the application under the agent and confirm it is collecting, \
            call mark to name the moment, exercise the endpoints in question, call findings to read \
            the ranked list of what is worth fixing and trace to open the evidence behind the top one, \
            fix the code and restart if it needs one, call mark again, exercise exactly the same way, \
            then compare the two marks and run check to turn thresholds into a verdict. \
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
         * (agent.md).
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

    private final ToolRunner runner;
    private final String version;

    public McpServer(ToolRunner runner, String version) {
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
                case "tools/list" -> result(id, Json.obj().put("tools", Tools.list()));
                case "tools/call" -> result(id, call(params, with));
                default -> error(id, METHOD_NOT_FOUND, "No such method: " + method);
            };
        } catch (BadArgument e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (RuntimeException e) {
            String said = e.getMessage();
            return error(id, INTERNAL_ERROR, said == null || said.isBlank() ? e.toString() : said);
        }
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
        Tools.Tool tool = Tools.byName(name);
        if (tool == null) {
            return failIfUnknown(name);
        }
        Map<String, Object> arguments = tool.read(params.has("arguments")
                && params.get("arguments") instanceof Json.JsonObject given ? given : Json.obj());
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

    private static Json.JsonObject failIfUnknown(@Nullable String name) {
        throw new BadArgument(name == null
                ? "tools/call needs a tool name"
                : "No such tool: " + name + "; the tools are " + Tools.names());
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

    /** The catalogue: the six tools of agent.md, their schemas and their descriptions. */
    static final class Tools {

        /** One argument, with the JSON Schema it is published as. */
        record Arg(String name, String type, String description, @Nullable Double minimum,
                @Nullable Double maximum, @Nullable String pattern) {

            static Arg string(String name, String description) {
                return new Arg(name, "string", description, null, null, null);
            }

            static Arg string(String name, String description, String pattern) {
                return new Arg(name, "string", description, null, null, pattern);
            }

            static Arg integer(String name, String description, double minimum, double maximum) {
                return new Arg(name, "integer", description, minimum, maximum, null);
            }

            static Arg number(String name, String description) {
                return new Arg(name, "number", description, null, null, null);
            }

            static Arg bool(String name, String description) {
                return new Arg(name, "boolean", description, null, null, null);
            }

            Json.JsonObject schema() {
                Json.JsonObject schema = Json.obj().put("type", type).put("description", description);
                if (minimum != null) {
                    schema.put("minimum", (double) minimum);
                }
                if (maximum != null) {
                    schema.put("maximum", (double) maximum);
                }
                if (pattern != null) {
                    schema.put("pattern", pattern);
                }
                return schema;
            }

            /** The value as the tool wants it, or a {@code -32602} naming the type. */
            Object read(Json.JsonValue value) {
                return switch (type) {
                    case "string" -> {
                        if (!value.isString()) {
                            throw wrongType("a string");
                        }
                        yield value.asString();
                    }
                    case "integer" -> {
                        if (!value.isNumber()) {
                            throw wrongType("a number");
                        }
                        yield (long) value.asDouble();
                    }
                    case "number" -> {
                        if (!value.isNumber()) {
                            throw wrongType("a number");
                        }
                        yield value.asDouble();
                    }
                    default -> {
                        if (!value.isBoolean()) {
                            throw wrongType("true or false");
                        }
                        yield value.asBoolean();
                    }
                };
            }

            private BadArgument wrongType(String wanted) {
                return new BadArgument(name + " must be " + wanted);
            }
        }

        record Tool(String name, String description, List<Arg> args, List<String> required) {

            Json.JsonObject schema() {
                Json.JsonObject properties = Json.obj();
                for (Arg arg : args) {
                    properties.put(arg.name(), arg.schema());
                }
                Json.JsonObject input = Json.obj().put("type", "object").put("properties", properties);
                if (!required.isEmpty()) {
                    input.put("required", Json.arr().addAll(required));
                }
                return Json.obj()
                        .put("name", name)
                        .put("description", description)
                        .put("inputSchema", input);
            }

            /** The arguments this call gave, typed, with every required one present. */
            Map<String, Object> read(Json.JsonObject given) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Arg arg : args) {
                    if (!given.has(arg.name()) || given.get(arg.name()).isNull()) {
                        continue;
                    }
                    values.put(arg.name(), arg.read(given.get(arg.name())));
                }
                for (String name : required) {
                    if (!values.containsKey(name)) {
                        throw new BadArgument(this.name + " needs " + name);
                    }
                }
                return values;
            }
        }

        private static final Arg SINCE = Arg.string("since",
                "Start of the window: a duration (30s, 5m, 2h, 1d), a mark name, "
                        + "start (since the application was last restarted), now, or epoch "
                        + "milliseconds. Defaults to 15m.");
        private static final Arg UNTIL = Arg.string("until",
                "End of the window, in the same forms as since. Defaults to now.");
        private static final Arg SERVICE = Arg.string("service",
                "Narrow the answer to one service by name; every service by default.");

        private static final List<Tool> TOOLS = List.of(
                new Tool("findings",
                        "Use this first, and after every change: the ranked list of what is worth "
                                + "fixing in a time window — N+1 queries, slow queries, slow endpoints, "
                                + "slow jobs, errors and exhausted connection pools — each with the "
                                + "numbers that justify it and the trace ids that prove it.",
                        List.of(SINCE, UNTIL, SERVICE,
                                Arg.integer("limit", "How many findings to return; 20 by default.",
                                        1, 100),
                                Arg.bool("full", "Keep statements whole instead of cutting them at "
                                        + "200 characters."),
                                Arg.bool("hideAcked", "Leave out the findings that have been "
                                        + "acknowledged; they are ranked last otherwise.")),
                        List.of()),
                new Tool("trace",
                        "Use this to open the evidence behind a finding: one request as an indented "
                                + "tree of spans, with the statement under each database span, the "
                                + "exception and application frames under each error, and the trace's "
                                + "log lines.",
                        List.of(Arg.string("traceId",
                                        "The 32 hex characters a finding or a trace list named."),
                                Arg.bool("full", "Expand the repeated spans a tree collapses into "
                                        + "one row, and keep statements whole."),
                                Arg.string("diff", "A second trace id: the two trees are aligned "
                                        + "and answered as one text, which is how the same request "
                                        + "before and after a change is read.")),
                        List.of("traceId")),
                new Tool("mark",
                        "Use this to name the moment before you exercise the application, so a later "
                                + "compare can measure what a change did; marks are also what since "
                                + "accepts in place of a duration.",
                        List.of(Arg.string("name", "The mark's name, such as before or after-fix.",
                                        "^[A-Za-z0-9._-]{1,64}$"),
                                Arg.string("note", "A sentence recorded with the moment."),
                                Arg.string("service", "The service the moment belongs to.")),
                        List.of("name")),
                new Tool("compare",
                        "Use this after a change to answer whether it helped: the window between two "
                                + "marks placed beside the window after the second, endpoint by "
                                + "endpoint and query by query, with a verdict on every row.",
                        List.of(Arg.string("before", "Selector where the first window starts, "
                                        + "usually the mark taken before the change."),
                                Arg.string("after", "Selector where the first window ends and the "
                                        + "second begins, usually the mark taken after it."),
                                Arg.string("until", "Selector where the second window ends; now by "
                                        + "default."),
                                SERVICE),
                        List.of("before", "after")),
                new Tool("check",
                        "Use this to decide whether a fix is done: the thresholds you name turned "
                                + "into one pass or fail verdict over the window, the way a test is "
                                + "used; with no rule the defaults are maxErrors 0, maxNPlusOne 0 and "
                                + "maxP95Ms the slow-request threshold.",
                        List.of(SINCE, UNTIL, SERVICE,
                                Arg.string("endpoint", "Narrow the verdict to one endpoint, by id or "
                                        + "by name (GET /orders/{id})."),
                                Arg.number("maxP95Ms", "Fail when any endpoint's p95 is above this."),
                                Arg.number("maxErrors", "Fail when more occurrences than this were "
                                        + "recorded."),
                                Arg.number("maxErrorRate", "Fail when the share of failed requests "
                                        + "is above this (0..1)."),
                                Arg.number("maxQueriesPerRequest", "Fail when any endpoint runs more "
                                        + "database calls per request than this."),
                                Arg.number("maxSlowQueries", "Fail when more query calls than this "
                                        + "ran over the slow-query threshold."),
                                Arg.number("maxNPlusOne", "Fail when more n-plus-one findings than "
                                        + "this were found."),
                                Arg.number("minApdex", "Fail when the Apdex over the scope is below "
                                        + "this.")),
                        List.of()),
                new Tool("sql",
                        "Use this only when no other tool has a column for the question: one "
                                + "read-only statement over Spider Sense's own H2 schema, which "
                                + "storage.md documents.",
                        List.of(Arg.string("sql",
                                        "One statement starting with SELECT, WITH, TABLE, VALUES, "
                                                + "EXPLAIN or SHOW; nothing that writes is allowed."),
                                Arg.integer("limit", "How many rows to return; 200 by default.",
                                        1, 5000)),
                        List.of("sql")));

        private Tools() {
        }

        static Json.JsonArray list() {
            Json.JsonArray tools = Json.arr();
            for (Tool tool : TOOLS) {
                tools.add(tool.schema());
            }
            return tools;
        }

        static @Nullable Tool byName(@Nullable String name) {
            for (Tool tool : TOOLS) {
                if (tool.name().equals(name)) {
                    return tool;
                }
            }
            return null;
        }

        static String names() {
            return String.join(", ", TOOLS.stream().map(Tool::name).toList());
        }
    }
}
