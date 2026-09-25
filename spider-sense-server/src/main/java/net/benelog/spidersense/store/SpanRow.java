package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code span} (storage.adoc#schema), in the one shape the writer, the
 * importer and the export share.
 *
 * <p>The writer derives it from a received span ({@link #of}), the importer reads
 * it from a document ({@link #fromJson}), and the export reads it from the table
 * ({@link #read}) and writes it out ({@link #toJson}), so the column order, the
 * JSON key of each column and the width each value is cut to are written once.
 * {@link #bind} cuts every text to its column (storage.adoc#writer).
 *
 * <p>A column the schema declares {@code NOT NULL} can still be null here when the
 * row came from a hand-edited document: the insert refuses it, and the import is a
 * {@code 400} (cli.adoc#export-import).
 *
 * @param attributes the JSON object text the column holds
 * @param events     the JSON array text the column holds
 */
public record SpanRow(
        @Nullable String traceId,
        @Nullable String spanId,
        @Nullable String parentSpanId,
        @Nullable String service,
        @Nullable String name,
        @Nullable String kind,
        long startMs,
        long startNs,
        long durationNs,
        @Nullable String status,
        @Nullable String statusMessage,
        boolean entry,
        boolean error,
        boolean slow,
        @Nullable String category,
        @Nullable String endpoint,
        @Nullable String endpointId,
        @Nullable String httpMethod,
        @Nullable String httpRoute,
        @Nullable Long httpStatus,
        @Nullable String dbSystem,
        @Nullable String dbStatement,
        @Nullable String dbNamespace,
        @Nullable String dbOperation,
        @Nullable String dbTable,
        @Nullable String queryId,
        @Nullable String errorType,
        @Nullable String errorMessage,
        @Nullable String errorId,
        @Nullable String scope,
        String attributes,
        String events) {

    static final String INSERT = """
            INSERT INTO span (trace_id, span_id, parent_span_id, service, name, kind, start_ms, start_ns,
                duration_ns, status, status_message, entry, error, slow, category, endpoint, endpoint_id,
                http_method, http_route, http_status, db_system, db_statement, db_namespace, db_operation,
                db_table, query_id, error_type, error_message, error_id, scope, attributes, events)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /**
     * The row a received span becomes: {@code entry}, {@code slow} and the ids are
     * decided here, once, against {@code tingles}, and stored as decided.
     */
    static SpanRow of(SpanRecord span, Tingles tingles) {
        boolean entry = tingles.isEntry(span);
        boolean error = span.isError();
        String statement = span.dbStatement();
        String endpoint = entry ? span.endpointName() : null;
        String errorType = error ? span.errorType() : null;
        String errorMessage = error ? span.errorMessage() : null;
        return new SpanRow(span.traceId(), span.spanId(), span.parentSpanId(), span.service(), span.name(),
                span.kind(), span.startMillis(), span.startNanos(), span.durationNanos(), span.status(),
                span.statusMessage(), entry, error, tingles.isSlow(span), span.category(), endpoint,
                endpoint == null ? null : Ids.endpointId(span.service(), endpoint),
                span.httpMethod(), span.httpRoute(), span.httpStatus(), span.dbSystem(), statement,
                span.dbNamespace(), span.dbOperation(), span.dbTable(),
                statement == null ? null : Ids.queryId(span.service(), String.valueOf(span.dbSystem()), statement),
                errorType, errorMessage,
                error ? Ids.errorId(span.service(), String.valueOf(errorType), errorMessage, span.stacktrace()) : null,
                span.scope(),
                AttrJson.encode(span.attributes(), Columns.JSON_TEXT),
                AttrJson.encodeEvents(span.events(), Columns.JSON_TEXT));
    }

    /**
     * The row as a document holds it: every column is read, none is derived,
     * because {@code entry} and {@code slow} were decided when the row was first
     * written, under that session's thresholds (storage.adoc).
     */
    static SpanRow fromJson(Json.JsonObject span) {
        return new SpanRow(
                RowJson.string(span, "traceId"),
                RowJson.string(span, "spanId"),
                RowJson.string(span, "parentSpanId"),
                RowJson.string(span, "service"),
                RowJson.string(span, "name"),
                RowJson.string(span, "kind"),
                RowJson.longOr(span, "startMs", 0),
                RowJson.longOr(span, "startNs", 0),
                RowJson.longOr(span, "durationNs", 0),
                RowJson.string(span, "status"),
                RowJson.string(span, "statusMessage"),
                RowJson.bool(span, "entry"),
                RowJson.bool(span, "error"),
                RowJson.bool(span, "slow"),
                RowJson.string(span, "category"),
                RowJson.string(span, "endpoint"),
                RowJson.string(span, "endpointId"),
                RowJson.string(span, "httpMethod"),
                RowJson.string(span, "httpRoute"),
                RowJson.nullableLong(span, "httpStatus"),
                RowJson.string(span, "dbSystem"),
                RowJson.string(span, "dbStatement"),
                RowJson.string(span, "dbNamespace"),
                RowJson.string(span, "dbOperation"),
                RowJson.string(span, "dbTable"),
                RowJson.string(span, "queryId"),
                RowJson.string(span, "errorType"),
                RowJson.string(span, "errorMessage"),
                RowJson.string(span, "errorId"),
                RowJson.string(span, "scope"),
                RowJson.nested(span, "attributes", AttrJson.EMPTY_OBJECT),
                RowJson.nested(span, "events", AttrJson.EMPTY_ARRAY));
    }

    /** The row of a {@code SELECT *} on {@code span}. */
    public static SpanRow read(ResultSet rs) throws SQLException {
        return new SpanRow(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("service"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getLong("start_ms"),
                rs.getLong("start_ns"),
                rs.getLong("duration_ns"),
                rs.getString("status"),
                rs.getString("status_message"),
                rs.getBoolean("entry"),
                rs.getBoolean("error"),
                rs.getBoolean("slow"),
                rs.getString("category"),
                rs.getString("endpoint"),
                rs.getString("endpoint_id"),
                rs.getString("http_method"),
                rs.getString("http_route"),
                Sql.longOrNull(rs, "http_status"),
                rs.getString("db_system"),
                rs.getString("db_statement"),
                rs.getString("db_namespace"),
                rs.getString("db_operation"),
                rs.getString("db_table"),
                rs.getString("query_id"),
                rs.getString("error_type"),
                rs.getString("error_message"),
                rs.getString("error_id"),
                rs.getString("scope"),
                RowJson.or(rs.getString("attributes"), AttrJson.EMPTY_OBJECT),
                RowJson.or(rs.getString("events"), AttrJson.EMPTY_ARRAY));
    }

    /** The row as the export document holds it, with {@code attributes} and {@code events} as the JSON they are. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("traceId", traceId)
                .put("spanId", spanId)
                .put("parentSpanId", parentSpanId)
                .put("service", service)
                .put("name", name)
                .put("kind", kind)
                .put("startMs", startMs)
                .put("startNs", startNs)
                .put("durationNs", durationNs)
                .put("status", status)
                .put("statusMessage", statusMessage)
                .put("entry", entry)
                .put("error", error)
                .put("slow", slow)
                .put("category", category)
                .put("endpoint", endpoint)
                .put("endpointId", endpointId)
                .put("httpMethod", httpMethod)
                .put("httpRoute", httpRoute)
                .put("httpStatus", RowJson.number(httpStatus))
                .put("dbSystem", dbSystem)
                .put("dbStatement", dbStatement)
                .put("dbNamespace", dbNamespace)
                .put("dbOperation", dbOperation)
                .put("dbTable", dbTable)
                .put("queryId", queryId)
                .put("errorType", errorType)
                .put("errorMessage", errorMessage)
                .put("errorId", errorId)
                .put("scope", scope)
                .put("attributes", RowJson.parsed(attributes, AttrJson.EMPTY_OBJECT))
                .put("events", RowJson.parsed(events, AttrJson.EMPTY_ARRAY));
    }

    /** Binds the row to {@link #INSERT}, each text cut to its column. */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setString(i++, traceId);
        statement.setString(i++, spanId);
        statement.setString(i++, parentSpanId);
        statement.setString(i++, service);
        statement.setString(i++, Columns.cut(name, Columns.SPAN_NAME));
        statement.setString(i++, kind);
        statement.setLong(i++, startMs);
        statement.setLong(i++, startNs);
        statement.setLong(i++, durationNs);
        statement.setString(i++, status);
        statement.setString(i++, Columns.cut(statusMessage, Columns.STATUS_MESSAGE));
        statement.setBoolean(i++, entry);
        statement.setBoolean(i++, error);
        statement.setBoolean(i++, slow);
        statement.setString(i++, category);
        statement.setString(i++, Columns.cut(endpoint, Columns.ENDPOINT));
        statement.setString(i++, endpointId);
        statement.setString(i++, Columns.cut(httpMethod, Columns.HTTP_METHOD));
        statement.setString(i++, Columns.cut(httpRoute, Columns.HTTP_ROUTE));
        Columns.setLong(statement, i++, httpStatus);
        statement.setString(i++, Columns.cut(dbSystem, Columns.DB_SYSTEM));
        statement.setString(i++, dbStatement);
        statement.setString(i++, Columns.cut(dbNamespace, Columns.DB_NAMESPACE));
        statement.setString(i++, Columns.cut(dbOperation, Columns.DB_OPERATION));
        statement.setString(i++, Columns.cut(dbTable, Columns.DB_TABLE));
        statement.setString(i++, queryId);
        statement.setString(i++, Columns.cut(errorType, Columns.ERROR_TYPE));
        statement.setString(i++, Columns.cut(errorMessage, Columns.ERROR_MESSAGE));
        statement.setString(i++, errorId);
        statement.setString(i++, Columns.cut(scope, Columns.SCOPE));
        statement.setString(i++, attributes);
        statement.setString(i, events);
    }
}
