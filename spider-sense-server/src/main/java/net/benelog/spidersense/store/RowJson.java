package net.benelog.spidersense.store;

import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonArray;
import net.benelog.silkjson.JsonValue;
import net.benelog.silkjson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * The reading and writing of a row's JSON form that every row record shares
 * (api.adoc#export-document).
 *
 * <p>On the way in, an absent key and JSON null are the same missing value, a
 * missing number is its fallback, and a nested object or array is stored as its
 * text. On the way out, a number the column holds as NULL or NaN is JSON null, and
 * a JSON column is the object or array its text is.
 */
final class RowJson {

    private RowJson() {
    }

    static @Nullable String string(JsonObject object, String key) {
        return AttrJson.optionalString(object, key);
    }

    /** A nullable integer column: JSON null stays null rather than becoming zero. */
    static @Nullable Long nullableLong(JsonObject object, String key) {
        return AttrJson.optionalLong(object, key);
    }

    static long longOr(JsonObject object, String key, long fallback) {
        return object.getLong(key, fallback);
    }

    static boolean bool(JsonObject object, String key) {
        return object.getBoolean(key, false);
    }

    /** A nested object or array as the JSON text its column holds, whatever its shape. */
    static String nested(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isNull()) {
            return fallback;
        }
        return object.get(key).toJson();
    }

    /**
     * An attributes or resource column: the JSON object's text, or {@code {}} when it is missing.
     *
     * @throws net.benelog.silkjson.JsonException when the value is not an object, which every read of the column would fail on
     */
    static String object(JsonObject object, String key) {
        JsonObject value = object.getObjectOrNull(key);
        return value == null ? AttrJson.EMPTY_OBJECT : value.toJson();
    }

    /**
     * An events column: the JSON array's text, or {@code []} when it is missing.
     *
     * @throws net.benelog.silkjson.JsonException when the value is not an array of the objects
     *         {@link AttrJson#encodeEvents} writes, which every read of the column would fail on
     */
    static String events(JsonObject object, String key) {
        JsonArray value = object.getArrayOrNull(key);
        if (value == null) {
            return AttrJson.EMPTY_ARRAY;
        }
        String json = value.toJson();
        AttrJson.readEvents(json);
        return json;
    }

    static String or(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** A nullable number as the JSON value it is: a number, or null. */
    static JsonValue number(@Nullable Long value) {
        return AttrJson.toJson(value, AttrJson.Rules.ANSWER);
    }

    /** A double as JSON; a NaN or an infinity has no JSON syntax, so it is null. */
    static JsonValue number(double value) {
        return AttrJson.toJson(value, AttrJson.Rules.ANSWER);
    }

    /** A JSON column as the value its text is, {@code empty} standing in for no text. */
    static JsonValue parsed(@Nullable String json, String empty) {
        return Json.parse(json == null || json.isEmpty() ? empty : json);
    }

    /** A nullable double column, with SQL NULL read as NaN, which {@link #number(double)} writes as null. */
    static double doubleOrNaN(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }
}
