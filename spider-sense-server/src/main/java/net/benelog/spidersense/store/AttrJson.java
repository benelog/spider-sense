package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.benelog.spidersilk.json.Json;

/**
 * Attributes and span events as the JSON text the {@code attributes} and
 * {@code events} columns hold.
 *
 * <p>They are stored as JSON rather than as rows of their own because nothing
 * queries an individual attribute: the columns beside them carry every value the
 * SQL needs, and everything else is shown verbatim in the span drawer. The one
 * query that does look inside is the free-text search, and {@code LIKE} over the
 * JSON is exactly the substring match it wants.
 */
public final class AttrJson {

    public static final String EMPTY_OBJECT = "{}";
    public static final String EMPTY_ARRAY = "[]";

    private AttrJson() {
    }

    public static String encode(Map<String, Object> attributes) {
        if (attributes.isEmpty()) {
            return EMPTY_OBJECT;
        }
        Json.JsonObject object = Json.obj();
        attributes.forEach((key, value) -> object.put(key, toJson(value)));
        return object.toJson();
    }

    /** The same map with its keys sorted, which is what a series key hashes over. */
    public static String encodeSorted(Map<String, Object> attributes) {
        return encode(new TreeMap<>(attributes));
    }

    public static Map<String, Object> decode(String json) {
        if (json == null || json.isEmpty() || EMPTY_OBJECT.equals(json)) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (var member : Json.parse(json).asObject()) {
            Object value = fromJson(member.getValue());
            if (value != null) {
                attributes.put(member.getKey(), value);
            }
        }
        return attributes;
    }

    public static String encodeEvents(List<SpanRecord.SpanEvent> events) {
        if (events.isEmpty()) {
            return EMPTY_ARRAY;
        }
        Json.JsonArray array = Json.arr();
        for (SpanRecord.SpanEvent event : events) {
            array.add(Json.obj()
                    .put("name", event.name())
                    .put("timeNs", event.timeNanos())
                    .put("attributes", Json.parse(encode(event.attributes()))));
        }
        return array.toJson();
    }

    public static List<SpanRecord.SpanEvent> decodeEvents(String json) {
        if (json == null || json.isEmpty() || EMPTY_ARRAY.equals(json)) {
            return List.of();
        }
        List<SpanRecord.SpanEvent> events = new ArrayList<>();
        for (Json.JsonValue value : Json.parse(json).asArray()) {
            Json.JsonObject event = value.asObject();
            Json.JsonObject attributes = event.optObject("attributes");
            events.add(new SpanRecord.SpanEvent(event.optString("name", ""),
                    event.optLong("timeNs", 0),
                    attributes == null ? Map.of() : decode(attributes.toJson())));
        }
        return events;
    }

    /** A JSON array of strings, for the {@code trace.services} column. */
    public static String encodeStrings(List<String> values) {
        return Json.arr().addAll(values).toJson();
    }

    public static List<String> decodeStrings(String json) {
        if (json == null || json.isEmpty() || EMPTY_ARRAY.equals(json)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Json.JsonValue value : Json.parse(json).asArray()) {
            values.add(value.asString());
        }
        return values;
    }

    private static Json.JsonValue toJson(Object value) {
        Json.JsonObject holder = Json.obj();
        switch (value) {
            case null -> holder.putNull("v");
            case String text -> holder.put("v", text);
            case Long number -> holder.put("v", number.longValue());
            case Integer number -> holder.put("v", number.longValue());
            case Double number -> holder.put("v", number.doubleValue());
            case Boolean flag -> holder.put("v", flag.booleanValue());
            case List<?> list -> {
                Json.JsonArray array = Json.arr();
                for (Object element : list) {
                    array.add(toJson(element));
                }
                holder.put("v", array);
            }
            default -> holder.put("v", String.valueOf(value));
        }
        return holder.get("v");
    }

    private static Object fromJson(Json.JsonValue value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            try {
                return value.asLong();
            } catch (Json.JsonException notWhole) {
                return value.asDouble();
            }
        }
        if (value instanceof Json.JsonArray array) {
            List<Object> values = new ArrayList<>(array.size());
            for (Json.JsonValue element : array) {
                Object converted = fromJson(element);
                if (converted != null) {
                    values.add(converted);
                }
            }
            return List.copyOf(values);
        }
        return value.toJson();
    }
}
