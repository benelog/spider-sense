package net.benelog.spidersense.api;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.Schema;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The whole window as one JSON document: every service, span, log record,
 * metric, series, point, tingle and mark (agent.md, "Export and import").
 *
 * <p>It is written straight to the response — or to the file the CLI named —
 * rather than built as a tree first. A session worth exporting is the one that
 * is too big to describe in a message, so the largest thing alive here is one
 * row: the result sets are walked and each row is written as it is read.
 *
 * <p>The keys are the column names of storage.md in camelCase, and nothing is
 * derived: the point of the document is that importing it reproduces the rows,
 * so {@code entry}, {@code slow}, {@code endpointId} and the rest travel as they
 * are stored rather than being decided again on the other side.
 */
final class SessionExport {

    private SessionExport() {
    }

    /** {@code spider-sense-<from>-<to>.json}, the instants in epoch milliseconds. */
    static String filename(Window window) {
        return "spider-sense-" + window.from() + "-" + window.to() + ".json";
    }

    /**
     * Writes the document of the window to {@code out}, which is left open: the
     * caller owns it, because it is a servlet's output stream as often as it is a
     * file.
     *
     * @param service one service, or null for every one of them
     */
    static void write(Sql sql, Window window, @Nullable String service, OutputStream out) {
        // Work always answers with something; there is nothing to answer with here.
        Boolean unused = sql.with(connection -> {
            Writer text = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 8192);
            try {
                document(connection, window, service, text);
                text.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("could not write the export", e);
            }
            return Boolean.TRUE;
        }, "export the window");
    }

    private static void document(Connection connection, Window window, @Nullable String service,
            Writer text)
            throws SQLException, IOException {
        text.write("{\"spiderSense\":");
        text.write(Json.obj()
                .put("version", ApiRoutes.VERSION)
                .put("schema", Schema.VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("window", Json.obj().put("from", window.from()).put("to", window.to()))
                .put("service", service)
                .toJson());

        section(text, "services", connection, services(service), SessionExport::service);
        section(text, "spans", connection, spans(window, service), SessionExport::span);
        section(text, "logs", connection, logs(window, service), SessionExport::log);
        section(text, "metrics", connection, metrics(), SessionExport::metric);
        section(text, "metricSeries", connection, series(window, service), SessionExport::series);
        section(text, "metricPoints", connection, points(window, service), SessionExport::point);
        section(text, "tingles", connection, tingles(window, service), SessionExport::tingle);
        section(text, "marks", connection, marks(window, service), SessionExport::mark);
        text.write("}");
    }

    /** One row of a result set, as the object the document holds. */
    @FunctionalInterface
    private interface Row {
        Json.JsonObject of(ResultSet rs) throws SQLException;
    }

    /** A statement and the values it binds, kept together so a section is one expression. */
    private record Select(String sql, List<Object> params) {
    }

    private static void section(Writer text, String name, Connection connection, Select select,
            Row row) throws SQLException, IOException {
        text.write(",\"");
        text.write(name);
        text.write("\":[");
        try (PreparedStatement statement = connection.prepareStatement(select.sql())) {
            Sql.bind(statement, select.params());
            try (ResultSet rs = statement.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        text.write(',');
                    }
                    first = false;
                    text.write(row.of(rs).toJson());
                }
            }
        }
        text.write(']');
    }

    // --- the sections -----------------------------------------------------------

    private static Select services(@Nullable String service) {
        return service == null
                ? new Select("SELECT * FROM service ORDER BY name", List.of())
                : new Select("SELECT * FROM service WHERE name = ? ORDER BY name", List.of(service));
    }

    private static Json.JsonObject service(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("name", rs.getString("name"))
                .put("language", rs.getString("language"))
                .put("pid", value(Sql.longOrNull(rs, "pid")))
                .put("firstSeen", rs.getLong("first_seen"))
                .put("lastSeen", rs.getLong("last_seen"))
                .put("resource", Json.parse(orEmpty(rs.getString("resource"))));
    }

    private static Select spans(Window window, @Nullable String service) {
        return windowed("SELECT * FROM span WHERE start_ms BETWEEN ? AND ?", "service",
                " ORDER BY start_ms, id", window, service);
    }

    private static Json.JsonObject span(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("traceId", rs.getString("trace_id"))
                .put("spanId", rs.getString("span_id"))
                .put("parentSpanId", rs.getString("parent_span_id"))
                .put("service", rs.getString("service"))
                .put("name", rs.getString("name"))
                .put("kind", rs.getString("kind"))
                .put("startMs", rs.getLong("start_ms"))
                .put("startNs", rs.getLong("start_ns"))
                .put("durationNs", rs.getLong("duration_ns"))
                .put("status", rs.getString("status"))
                .put("statusMessage", rs.getString("status_message"))
                .put("entry", rs.getBoolean("entry"))
                .put("error", rs.getBoolean("error"))
                .put("slow", rs.getBoolean("slow"))
                .put("category", rs.getString("category"))
                .put("endpoint", rs.getString("endpoint"))
                .put("endpointId", rs.getString("endpoint_id"))
                .put("httpMethod", rs.getString("http_method"))
                .put("httpRoute", rs.getString("http_route"))
                .put("httpStatus", value(Sql.longOrNull(rs, "http_status")))
                .put("dbSystem", rs.getString("db_system"))
                .put("dbStatement", rs.getString("db_statement"))
                .put("dbNamespace", rs.getString("db_namespace"))
                .put("dbOperation", rs.getString("db_operation"))
                .put("dbTable", rs.getString("db_table"))
                .put("queryId", rs.getString("query_id"))
                .put("errorType", rs.getString("error_type"))
                .put("errorMessage", rs.getString("error_message"))
                .put("errorId", rs.getString("error_id"))
                .put("scope", rs.getString("scope"))
                .put("attributes", Json.parse(orEmpty(rs.getString("attributes"))))
                .put("events", Json.parse(orEmptyArray(rs.getString("events"))));
    }

    private static Select logs(Window window, @Nullable String service) {
        return windowed("SELECT * FROM log WHERE at_ms BETWEEN ? AND ?", "service",
                " ORDER BY at_ms, id", window, service);
    }

    private static Json.JsonObject log(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("atMs", rs.getLong("at_ms"))
                .put("service", rs.getString("service"))
                .put("severityNumber", rs.getInt("severity_number"))
                .put("severity", rs.getString("severity"))
                .put("body", rs.getString("body"))
                .put("logger", rs.getString("logger"))
                .put("traceId", rs.getString("trace_id"))
                .put("spanId", rs.getString("span_id"))
                .put("attributes", Json.parse(orEmpty(rs.getString("attributes"))));
    }

    /**
     * The instrument metadata is not windowed: it describes the series the points
     * belong to, and a description has no instant of its own.
     */
    private static Select metrics() {
        return new Select("SELECT * FROM metric ORDER BY name", List.of());
    }

    private static Json.JsonObject metric(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("name", rs.getString("name"))
                .put("type", rs.getString("type"))
                .put("unit", rs.getString("unit"))
                .put("description", rs.getString("description"))
                .put("monotonic", rs.getBoolean("monotonic"))
                .put("temporality", rs.getString("temporality"));
    }

    /** Only the series with a point in the window; the others describe nothing here. */
    private static Select series(Window window, @Nullable String service) {
        String sql = """
                SELECT DISTINCT s.id, s.service, s.name, s.attributes
                FROM metric_series s JOIN metric_point p ON p.series_id = s.id
                WHERE p.at_ms BETWEEN ? AND ?""";
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        if (service != null) {
            sql = sql + " AND s.service = ?";
            params.add(service);
        }
        return new Select(sql + " ORDER BY s.id", params);
    }

    private static Json.JsonObject series(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("id", rs.getLong("id"))
                .put("service", rs.getString("service"))
                .put("name", rs.getString("name"))
                .put("attributes", Json.parse(orEmpty(rs.getString("attributes"))));
    }

    private static Select points(Window window, @Nullable String service) {
        String sql = """
                SELECT p.* FROM metric_point p JOIN metric_series s ON s.id = p.series_id
                WHERE p.at_ms BETWEEN ? AND ?""";
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        if (service != null) {
            sql = sql + " AND s.service = ?";
            params.add(service);
        }
        return new Select(sql + " ORDER BY p.series_id, p.at_ms", params);
    }

    private static Json.JsonObject point(ResultSet rs) throws SQLException {
        String buckets = rs.getString("buckets");
        return Json.obj()
                .put("seriesId", rs.getLong("series_id"))
                .put("atMs", rs.getLong("at_ms"))
                .put("value", value(doubleOrNull(rs, "value")))
                .put("count", value(Sql.longOrNull(rs, "count")))
                .put("sum", value(doubleOrNull(rs, "sum")))
                .put("min", value(doubleOrNull(rs, "min")))
                .put("max", value(doubleOrNull(rs, "max")))
                .put("buckets", buckets == null ? null : Json.parse(buckets));
    }

    private static Select tingles(Window window, @Nullable String service) {
        return windowed("SELECT * FROM tingle WHERE at_ms BETWEEN ? AND ?", "service",
                " ORDER BY at_ms, id", window, service);
    }

    private static Json.JsonObject tingle(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("atMs", rs.getLong("at_ms"))
                .put("kind", rs.getString("kind"))
                .put("service", rs.getString("service"))
                .put("title", rs.getString("title"))
                .put("detail", rs.getString("detail"))
                .put("traceId", rs.getString("trace_id"))
                .put("spanId", rs.getString("span_id"))
                .put("durationMs", value(doubleOrNull(rs, "duration_ms")));
    }

    /**
     * A mark of no service belongs to every service, so {@code service=x} keeps it:
     * "before" was the moment, not the application.
     */
    private static Select marks(Window window, @Nullable String service) {
        String sql = "SELECT * FROM mark WHERE at_ms BETWEEN ? AND ?";
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        if (service != null) {
            sql = sql + " AND (service = ? OR service IS NULL)";
            params.add(service);
        }
        return new Select(sql + " ORDER BY at_ms, id", params);
    }

    private static Json.JsonObject mark(ResultSet rs) throws SQLException {
        return Json.obj()
                .put("atMs", rs.getLong("at_ms"))
                .put("name", rs.getString("name"))
                .put("service", rs.getString("service"))
                .put("note", rs.getString("note"));
    }

    // --- the plumbing --------------------------------------------------------------

    private static Select windowed(String sql, String serviceColumn, String order, Window window,
            @Nullable String service) {
        List<Object> params = new ArrayList<>(List.of(window.from(), window.to()));
        String statement = sql;
        if (service != null) {
            statement = statement + " AND " + serviceColumn + " = ?";
            params.add(service);
        }
        return new Select(statement + order, params);
    }

    /**
     * A nullable number as the JSON value it is: a number, or null — which
     * {@code put(key, (JsonValue) null)} writes as {@code null}.
     */
    private static Json.@Nullable JsonValue value(@Nullable Long number) {
        return number == null ? null : Json.obj().put("v", number.longValue()).get("v");
    }

    /** The same for a double; a NaN or an infinity has no JSON syntax, so it is null. */
    private static Json.@Nullable JsonValue value(@Nullable Double number) {
        return number == null || number.isNaN() || number.isInfinite()
                ? null : Json.obj().put("v", number.doubleValue()).get("v");
    }

    private static @Nullable Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String orEmpty(@Nullable String json) {
        return json == null || json.isEmpty() ? AttrJson.EMPTY_OBJECT : json;
    }

    private static String orEmptyArray(@Nullable String json) {
        return json == null || json.isEmpty() ? AttrJson.EMPTY_ARRAY : json;
    }
}
