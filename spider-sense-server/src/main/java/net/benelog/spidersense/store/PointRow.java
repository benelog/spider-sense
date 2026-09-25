package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code metric_point} (storage.adoc#schema), in the one shape the
 * writer, the importer and the export share, as {@link SpanRow} is for a span.
 *
 * @param seriesId the series row's id here, or the document's own id before the importer maps it
 * @param value    NaN for a stored NULL, as {@code sum}, {@code min} and {@code max} are
 * @param buckets  the {@code {"bounds":[…],"counts":[…]}} text the column holds, or null
 */
public record PointRow(long seriesId, long atMs, double value, @Nullable Long count, double sum,
        double min, double max, @Nullable String buckets) {

    static final String MERGE = """
            MERGE INTO metric_point (series_id, at_ms, value, count, sum, min, max, buckets)
            KEY(series_id, at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    static PointRow of(long seriesId, MetricPoint point) {
        return new PointRow(seriesId, point.at(), point.value(), point.count(), point.sum(), point.min(),
                point.max(), buckets(point));
    }

    private static @Nullable String buckets(MetricPoint point) {
        double[] bounds = point.bounds();
        long[] counts = point.bucketCounts();
        if (!point.hasBuckets() || bounds == null || counts == null) {
            return null;
        }
        StringBuilder json = new StringBuilder("{\"bounds\":[");
        for (int i = 0; i < bounds.length; i++) {
            json.append(i == 0 ? "" : ",").append(bounds[i]);
        }
        json.append("],\"counts\":[");
        for (int i = 0; i < counts.length; i++) {
            json.append(i == 0 ? "" : ",").append(counts[i]);
        }
        return json.append("]}").toString();
    }

    static PointRow fromJson(Json.JsonObject point) {
        Json.JsonObject buckets = point.optObject("buckets");
        return new PointRow(
                RowJson.longOr(point, "seriesId", -1),
                RowJson.longOr(point, "atMs", 0),
                point.optDouble("value", 0),
                RowJson.longOr(point, "count", 0),
                point.optDouble("sum", 0),
                point.optDouble("min", Double.NaN),
                point.optDouble("max", Double.NaN),
                buckets == null ? null : buckets.toJson());
    }

    /** The row of a {@code SELECT *} on {@code metric_point}. */
    public static PointRow read(ResultSet rs) throws SQLException {
        return new PointRow(
                rs.getLong("series_id"),
                rs.getLong("at_ms"),
                RowJson.doubleOrNaN(rs, "value"),
                Sql.longOrNull(rs, "count"),
                RowJson.doubleOrNaN(rs, "sum"),
                RowJson.doubleOrNaN(rs, "min"),
                RowJson.doubleOrNaN(rs, "max"),
                rs.getString("buckets"));
    }

    /** The row as the export document holds it, with {@code buckets} as the object it is. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("seriesId", seriesId)
                .put("atMs", atMs)
                .put("value", RowJson.number(value))
                .put("count", RowJson.number(count))
                .put("sum", RowJson.number(sum))
                .put("min", RowJson.number(min))
                .put("max", RowJson.number(max))
                .put("buckets", buckets == null ? null : Json.parse(buckets));
    }

    /** The same point in the series {@code id}: an imported point moves from the document's id to this store's. */
    PointRow inSeries(long id) {
        return new PointRow(id, atMs, value, count, sum, min, max, buckets);
    }

    /**
     * Binds the row to {@link #MERGE}. Buckets that do not fit their column are
     * left out rather than cut, since text cut partway through would not parse: the
     * point keeps its count, sum, min and max (storage.adoc#writer).
     */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setLong(i++, seriesId);
        statement.setLong(i++, atMs);
        statement.setDouble(i++, value);
        Columns.setLong(statement, i++, count);
        statement.setDouble(i++, sum);
        Columns.setDouble(statement, i++, min);
        Columns.setDouble(statement, i++, max);
        statement.setString(i, buckets != null && buckets.length() <= Columns.BUCKETS ? buckets : null);
    }
}
