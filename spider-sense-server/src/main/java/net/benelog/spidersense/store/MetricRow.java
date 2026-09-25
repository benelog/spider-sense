package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code metric}, one service's instrument (storage.adoc#schema), in
 * the one shape the writer, the importer and the export share, as {@link SpanRow}
 * is for a span.
 */
public record MetricRow(String service, String name, String type, @Nullable String unit,
        @Nullable String description, boolean monotonic, @Nullable String temporality) {

    /** The writer's: the newest description of an instrument wins. */
    static final String MERGE = "MERGE INTO metric (service, name, type, unit, description, monotonic, temporality)"
            + " KEY(service, name) VALUES (?, ?, ?, ?, ?, ?, ?)";

    /** The importer's, for an instrument this store has no row of: a stored description wins. */
    static final String INSERT = "INSERT INTO metric (service, name, type, unit, description, monotonic, temporality)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    static MetricRow of(Batch.MetricSample sample) {
        return new MetricRow(sample.service(), sample.name(), sample.type(), sample.unit(), sample.description(),
                sample.monotonic(), sample.temporality());
    }

    /** The row a document holds, or null when it names no service or no name. */
    static @Nullable MetricRow fromJson(Json.JsonObject metric) {
        String service = RowJson.string(metric, "service");
        String name = RowJson.string(metric, "name");
        if (service == null || name == null) {
            return null;
        }
        return new MetricRow(service, name, RowJson.or(RowJson.string(metric, "type"), "gauge"),
                RowJson.string(metric, "unit"), RowJson.string(metric, "description"),
                RowJson.bool(metric, "monotonic"), RowJson.string(metric, "temporality"));
    }

    /** The row of a {@code SELECT *} on {@code metric}. */
    public static MetricRow read(ResultSet rs) throws SQLException {
        return new MetricRow(
                RowJson.or(rs.getString("service"), ""),
                RowJson.or(rs.getString("name"), ""),
                RowJson.or(rs.getString("type"), ""),
                rs.getString("unit"),
                rs.getString("description"),
                rs.getBoolean("monotonic"),
                rs.getString("temporality"));
    }

    /** The row as the export document holds it. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("service", service)
                .put("name", name)
                .put("type", type)
                .put("unit", unit)
                .put("description", description)
                .put("monotonic", monotonic)
                .put("temporality", temporality);
    }

    /** Binds the row to {@link #MERGE} or {@link #INSERT}, which list the same columns, each text cut to its column. */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setString(i++, service);
        statement.setString(i++, name);
        statement.setString(i++, type);
        statement.setString(i++, Columns.cut(unit, Columns.METRIC_UNIT));
        statement.setString(i++, Columns.cut(description, Columns.METRIC_DESCRIPTION));
        statement.setBoolean(i++, monotonic);
        statement.setString(i, temporality);
    }
}
