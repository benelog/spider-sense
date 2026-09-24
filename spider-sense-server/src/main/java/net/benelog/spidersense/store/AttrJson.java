package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

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

    /** What a cut string value ends with, so the span drawer shows that it was cut. */
    static final String CUT_MARK = "\u2026";

    private AttrJson() {
    }

    /**
     * An optional string member: absent, JSON null and a missing key all read as null.
     *
     * <p>{@link Json.JsonObject#optString} takes a fallback that may not itself be
     * null, so this is how a reader asks for "the value, or nothing".
     */
    public static @Nullable String optionalString(Json.JsonObject object, String key) {
        return object.has(key) && !object.get(key).isNull() ? object.get(key).asString() : null;
    }

    public static String encode(Map<String, Object> attributes) {
        if (attributes.isEmpty()) {
            return EMPTY_OBJECT;
        }
        Json.JsonObject object = Json.obj();
        attributes.forEach((key, value) -> object.put(key, toJson(value)));
        return object.toJson();
    }

    /**
     * The attributes as JSON of at most {@code max} characters, the width of the
     * column that holds them: the longest string values are cut, one at a time,
     * until the text fits, so one huge value (a deep stack trace) costs its own
     * tail rather than the row, or the whole batch the row is written with.
     */
    public static String encode(Map<String, Object> attributes, int max) {
        String json = encode(attributes);
        if (json.length() <= max) {
            return json;
        }
        List<Map<String, Object>> maps = List.of(new LinkedHashMap<>(attributes));
        while (json.length() > max) {
            if (!cutLongest(maps, json.length() - max)) {
                return EMPTY_OBJECT;
            }
            json = encode(maps.get(0));
        }
        return json;
    }

    /** The same map with its keys sorted, which is what a series key hashes over. */
    public static String encodeSorted(Map<String, Object> attributes) {
        return encode(new TreeMap<>(attributes));
    }

    public static Map<String, Object> decode(@Nullable String json) {
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

    /** The events as JSON of at most {@code max} characters, cut as {@link #encode(Map, int)} cuts. */
    public static String encodeEvents(List<SpanRecord.SpanEvent> events, int max) {
        String json = encodeEvents(events);
        if (json.length() <= max) {
            return json;
        }
        List<Map<String, Object>> maps = new ArrayList<>(events.size());
        for (SpanRecord.SpanEvent event : events) {
            maps.add(new LinkedHashMap<>(event.attributes()));
        }
        while (json.length() > max) {
            if (!cutLongest(maps, json.length() - max)) {
                return EMPTY_ARRAY;
            }
            List<SpanRecord.SpanEvent> cut = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                SpanRecord.SpanEvent event = events.get(i);
                cut.add(new SpanRecord.SpanEvent(event.name(), event.timeNanos(), maps.get(i)));
            }
            json = encodeEvents(cut);
        }
        return json;
    }

    /**
     * Cuts the longest string value among the maps by about {@code excess}
     * encoded characters, marking it; false when no value is long enough to cut, which
     * leaves the caller nothing to shrink but the whole document.
     */
    private static boolean cutLongest(List<Map<String, Object>> maps, int excess) {
        @Nullable Map<String, Object> owner = null;
        @Nullable String key = null;
        String value = "";
        for (Map<String, Object> map : maps) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof String text && text.length() > value.length()) {
                    owner = map;
                    key = entry.getKey();
                    value = text;
                }
            }
        }
        if (owner == null || key == null || value.length() <= CUT_MARK.length()) {
            return false;
        }
        // The excess counts encoded characters, and an escaped one (a tab, a newline in a
        // stack trace) is two of them: cut in proportion, and the caller's loop tops it up.
        int encoded = Json.obj().put("v", value).toJson().length() - "{\"v\":\"\"}".length();
        long raw = ((long) excess * value.length() + encoded - 1) / Math.max(1, encoded);
        int keep = (int) Math.max(0, value.length() - raw - CUT_MARK.length());
        if (keep > 0 && Character.isHighSurrogate(value.charAt(keep - 1))) {
            keep--;
        }
        owner.put(key, value.substring(0, keep) + CUT_MARK);
        return true;
    }

    public static List<SpanRecord.SpanEvent> decodeEvents(@Nullable String json) {
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

    /** As {@link #encodeStrings(List)}, leaving out the values past {@code max} characters of JSON. */
    public static String encodeStrings(List<String> values, int max) {
        List<String> kept = new ArrayList<>(values);
        String json = encodeStrings(kept);
        while (json.length() > max && !kept.isEmpty()) {
            kept.remove(kept.size() - 1);
            json = encodeStrings(kept);
        }
        return json;
    }

    public static List<String> decodeStrings(@Nullable String json) {
        if (json == null || json.isEmpty() || EMPTY_ARRAY.equals(json)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Json.JsonValue value : Json.parse(json).asArray()) {
            values.add(value.asString());
        }
        return values;
    }

    /**
     * One value of the store's value space as JSON: a list is an array of its
     * elements, never Java's {@code [a, b]}.
     */
    public static Json.JsonValue json(@Nullable Object value) {
        return toJson(value);
    }

    private static Json.JsonValue toJson(@Nullable Object value) {
        Json.JsonObject holder = Json.obj();
        switch (value) {
            case null -> holder.putNull("v");
            case String text -> holder.put("v", text);
            case Long number -> holder.put("v", number.longValue());
            case Integer number -> holder.put("v", number.longValue());
            // JSON has no NaN or Infinity, and Spider Silk refuses to write one: the
            // attribute keeps its value as the text Java spells it.
            case Double number when !Double.isFinite(number) -> holder.put("v", number.toString());
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

    private static @Nullable Object fromJson(Json.JsonValue value) {
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
