package net.benelog.spidersense.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One tool of mcp.adoc#tools: the schema {@code tools/list} publishes, the argument reading a
 * {@code tools/call} goes through, and the {@link McpTools} call that answers it in process.
 *
 * <p>{@link McpServer} reads only the name and the arguments, since a call may be answered by a
 * proxy rather than by the handler; {@link McpTools} dispatches on the handler.
 *
 * @param required the arguments a call must give, or it is a {@code -32602}
 */
public record Tool(String name, String description, List<Arg> args, List<String> required,
        Handler handler) {

    /** The in-process answer to one call, as a method of {@link McpTools}. */
    @FunctionalInterface
    public interface Handler {
        McpServer.ToolResult answer(McpTools tools, Map<String, Object> arguments);
    }

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
        for (String argument : required) {
            if (!values.containsKey(argument)) {
                throw new McpServer.BadArgument(name + " needs " + argument);
            }
        }
        return values;
    }

    /** One argument, with the JSON Schema it is published as. */
    public record Arg(String name, String type, String description, @Nullable Double minimum,
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

        private McpServer.BadArgument wrongType(String wanted) {
            return new McpServer.BadArgument(name + " must be " + wanted);
        }
    }
}
