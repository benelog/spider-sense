package net.benelog.spidersense.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code metric_series} as the export document carries it: its id,
 * which the document's points refer to, and the {@code (service, name, attributes)}
 * the importer looks the series up by (cli.adoc#export-import).
 *
 * <p>No binder: a series row is only ever written by
 * {@link MetricSeriesRows#lookupOrCreate}, which owns its lock.
 *
 * @param attributes the JSON object text the column holds
 */
public record SeriesRow(long id, String service, String name, String attributes) {

    /** The row a document holds, or null when it names no service or no name. */
    static @Nullable SeriesRow fromJson(Json.JsonObject series) {
        String service = RowJson.string(series, "service");
        String name = RowJson.string(series, "name");
        if (service == null || name == null) {
            return null;
        }
        return new SeriesRow(RowJson.longOr(series, "id", 0), service, name,
                RowJson.object(series, "attributes"));
    }

    /** The row of a {@code SELECT id, service, name, attributes} on {@code metric_series}. */
    public static SeriesRow read(ResultSet rs) throws SQLException {
        return new SeriesRow(
                rs.getLong("id"),
                RowJson.or(rs.getString("service"), ""),
                RowJson.or(rs.getString("name"), ""),
                RowJson.or(rs.getString("attributes"), AttrJson.EMPTY_OBJECT));
    }

    /** The row as the export document holds it, with {@code attributes} as the object it is. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("id", id)
                .put("service", service)
                .put("name", name)
                .put("attributes", RowJson.parsed(attributes, AttrJson.EMPTY_OBJECT));
    }

    /**
     * The attributes in the sorted form the series key is hashed over: the document's
     * own values with the keys sorted, so the text is the one the writer stored and the
     * import finds that series. Decoded into the store's values and encoded again, a
     * {@code 2.0} would come back as {@code 2} and a nested object as its text, and the
     * import would create a second series. Only text too long for the column, which no
     * export writes, is cut as the writer cuts.
     */
    String sortedAttributes() {
        Map<String, Json.JsonValue> members = new TreeMap<>();
        for (Map.Entry<String, Json.JsonValue> member : Json.parse(attributes).asObject()) {
            members.put(member.getKey(), member.getValue());
        }
        Json.JsonObject sorted = Json.obj();
        members.forEach(sorted::put);
        String json = sorted.toJson();
        return json.length() <= Columns.SERIES_ATTRIBUTES
                ? json
                : AttrJson.encodeSorted(AttrJson.decode(attributes), Columns.SERIES_ATTRIBUTES);
    }
}
