package net.benelog.spidersense.store;

import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
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

    static @Nullable String string(Json.JsonObject object, String key) {
        return AttrJson.optionalString(object, key);
    }

    /** A nullable integer column: JSON null stays null rather than becoming zero. */
    static @Nullable Long nullableLong(Json.JsonObject object, String key) {
        return AttrJson.optionalLong(object, key);
    }

    static long longOr(Json.JsonObject object, String key, long fallback) {
        return object.optLong(key, fallback);
    }

    static boolean bool(Json.JsonObject object, String key) {
        return object.optBoolean(key, false);
    }

    /** A nested object or array as the JSON text its column holds. */
    static String nested(Json.JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isNull()) {
            return fallback;
        }
        return object.get(key).toJson();
    }

    static String or(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** A nullable number as the JSON value it is: a number, or null. */
    static Json.JsonValue number(@Nullable Long value) {
        return AttrJson.toJson(value, AttrJson.Rules.ANSWER);
    }

    /** A double as JSON; a NaN or an infinity has no JSON syntax, so it is null. */
    static Json.JsonValue number(double value) {
        return AttrJson.toJson(value, AttrJson.Rules.ANSWER);
    }

    /** A JSON column as the value its text is, {@code empty} standing in for no text. */
    static Json.JsonValue parsed(@Nullable String json, String empty) {
        return Json.parse(json == null || json.isEmpty() ? empty : json);
    }

    /** A nullable double column, with SQL NULL read as NaN, which {@link #number(double)} writes as null. */
    static double doubleOrNaN(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }
}
