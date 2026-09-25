package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

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

    /** An optional whole-number member: absent and JSON null both read as null. */
    public static @Nullable Long optionalLong(Json.JsonObject object, String key) {
        return object.has(key) && !object.get(key).isNull() ? object.getLong(key) : null;
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
        List<Map<String, Object>> maps = List.of(flattened(attributes));
        json = encode(maps.get(0));
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
            maps.add(flattened(event.attributes()));
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
     * A copy whose nested objects are their JSON text: a string, which the cut can
     * shorten like any other, where an object too long for the column could only
     * be dropped with every attribute beside it.
     */
    private static Map<String, Object> flattened(Map<String, Object> attributes) {
        Map<String, Object> flat = new LinkedHashMap<>(attributes);
        flat.replaceAll((key, value) -> value instanceof Map<?, ?> map ? toJson(map).toJson() : value);
        return flat;
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
     * What a NaN or an infinity becomes, since JSON has no syntax for either and Spider Silk
     * refuses to write one.
     */
    public enum NonFinite {
        /** {@code null}: an absent value, which is what an answer means by it. */
        NULL,
        /** The text Java spells it with, so a stored attribute keeps its value. */
        TEXT
    }

    /**
     * Where the writers of {@link #toJson(Object, Rules)} differ.
     *
     * @param nonFinite  what a NaN or an infinity becomes
     * @param finite     what any other double is written as, such as rounded to three decimals
     * @param mapsAsText a map as its JSON text rather than as an object, which is how an
     *                   attribute that holds one reads back once it is stored
     */
    public record Rules(NonFinite nonFinite, DoubleUnaryOperator finite, boolean mapsAsText) {

        /** The store's own: a map is an object, and a non-finite double is its text. */
        public static final Rules STORE = new Rules(NonFinite.TEXT, DoubleUnaryOperator.identity(), false);

        /** An answer's: a non-finite double is null, and every other value as the store writes it. */
        public static final Rules ANSWER = new Rules(NonFinite.NULL, DoubleUnaryOperator.identity(), false);
    }

    /**
     * One value of the store's value space as JSON: a list is an array of its
     * elements, never Java's {@code [a, b]}, and a map is an object, however deep
     * either nests.
     */
    public static Json.JsonValue toJson(@Nullable Object value) {
        return toJson(value, Rules.STORE);
    }

    /**
     * The one conversion from a Java value to JSON: the store's attributes, the API's attributes,
     * a finding's numbers, a SQL cell, the export's numbers and MCP's structured content all come
     * through here, and differ only by {@code rules}. Whole numbers are JSON integers, a list is an
     * array however deep it nests, and anything else is its text.
     */
    public static Json.JsonValue toJson(@Nullable Object value, Rules rules) {
        Json.JsonObject holder = Json.obj();
        switch (value) {
            case null -> holder.putNull("v");
            case String text -> holder.put("v", text);
            case Long number -> holder.put("v", number.longValue());
            case Integer number -> holder.put("v", number.longValue());
            case Short number -> holder.put("v", number.longValue());
            case Byte number -> holder.put("v", number.longValue());
            case Double number -> putDouble(holder, number, rules);
            case Float number -> putDouble(holder, number.doubleValue(), rules);
            case Boolean flag -> holder.put("v", flag.booleanValue());
            case List<?> list -> {
                Json.JsonArray array = Json.arr();
                for (Object element : list) {
                    array.add(toJson(element, rules));
                }
                holder.put("v", array);
            }
            // The text the store would write for it, which is what the attribute reads back as.
            case Map<?, ?> map when rules.mapsAsText() -> holder.put("v", toJson(map, Rules.STORE).toJson());
            case Map<?, ?> map -> {
                Json.JsonObject object = Json.obj();
                map.forEach((key, each) -> object.put(String.valueOf(key), toJson(each, rules)));
                holder.put("v", object);
            }
            default -> holder.put("v", String.valueOf(value));
        }
        return holder.get("v");
    }

    private static void putDouble(Json.JsonObject holder, double number, Rules rules) {
        if (Double.isFinite(number)) {
            holder.put("v", rules.finite().applyAsDouble(number));
        } else if (rules.nonFinite() == NonFinite.NULL) {
            holder.putNull("v");
        } else {
            holder.put("v", Double.toString(number));
        }
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
