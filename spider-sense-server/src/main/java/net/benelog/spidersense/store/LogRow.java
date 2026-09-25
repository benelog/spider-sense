package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code log} (storage.adoc#schema), in the one shape the writer, the
 * importer and the export share, as {@link SpanRow} is for a span.
 *
 * @param attributes the JSON object text the column holds
 */
public record LogRow(
        long atMs,
        @Nullable String service,
        int severityNumber,
        @Nullable String severity,
        String body,
        @Nullable String logger,
        @Nullable String traceId,
        @Nullable String spanId,
        String attributes) {

    static final String INSERT = """
            INSERT INTO log (at_ms, service, severity_number, severity, body, logger, trace_id, span_id,
                attributes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /**
     * The columns that make two log lines the same line, in the order of
     * {@link #sameLine()}; the attributes are left out.
     */
    static final String SAME_LINE = """
            SELECT COUNT(*) FROM log WHERE service IS NOT DISTINCT FROM ? AND at_ms = ?
                AND severity_number = ? AND body = ? AND logger IS NOT DISTINCT FROM ?
                AND trace_id IS NOT DISTINCT FROM ? AND span_id IS NOT DISTINCT FROM ?""";

    static LogRow of(LogRecord log) {
        return new LogRow(log.at(), log.service(), log.severityNumber(), log.severity(), log.body(),
                log.logger(), log.traceId(), log.spanId(), AttrJson.encode(log.attributes(), Columns.JSON_TEXT));
    }

    static LogRow fromJson(Json.JsonObject log) {
        return new LogRow(
                RowJson.longOr(log, "atMs", 0),
                RowJson.string(log, "service"),
                (int) RowJson.longOr(log, "severityNumber", 0),
                RowJson.string(log, "severity"),
                RowJson.or(RowJson.string(log, "body"), ""),
                RowJson.string(log, "logger"),
                RowJson.string(log, "traceId"),
                RowJson.string(log, "spanId"),
                RowJson.nested(log, "attributes", AttrJson.EMPTY_OBJECT));
    }

    /** The row of a {@code SELECT *} on {@code log}. */
    public static LogRow read(ResultSet rs) throws SQLException {
        return new LogRow(
                rs.getLong("at_ms"),
                rs.getString("service"),
                rs.getInt("severity_number"),
                rs.getString("severity"),
                RowJson.or(rs.getString("body"), ""),
                rs.getString("logger"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                RowJson.or(rs.getString("attributes"), AttrJson.EMPTY_OBJECT));
    }

    /** The row as the export document holds it. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("atMs", atMs)
                .put("service", service)
                .put("severityNumber", severityNumber)
                .put("severity", severity)
                .put("body", body)
                .put("logger", logger)
                .put("traceId", traceId)
                .put("spanId", spanId)
                .put("attributes", RowJson.parsed(attributes, AttrJson.EMPTY_OBJECT));
    }

    /** The values {@link #SAME_LINE} binds, cut as {@link #bind} cuts them, so a stored line compares equal. */
    List<@Nullable Object> sameLine() {
        return Arrays.asList(service, atMs, (long) severityNumber, Columns.cut(body, Columns.LOG_BODY),
                Columns.cut(logger, Columns.LOGGER), traceId, spanId);
    }

    /** Binds the row to {@link #INSERT}, each text cut to its column. */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setLong(i++, atMs);
        statement.setString(i++, service);
        statement.setInt(i++, severityNumber);
        statement.setString(i++, Columns.cut(severity, Columns.LOG_SEVERITY));
        statement.setString(i++, Columns.cut(body, Columns.LOG_BODY));
        statement.setString(i++, Columns.cut(logger, Columns.LOGGER));
        statement.setString(i++, traceId);
        statement.setString(i++, spanId);
        statement.setString(i, attributes);
    }
}
