package net.benelog.spidersense.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.SpanRecord;

/**
 * Rows read by hand, one method per table.
 *
 * <p>Every column is named in the mapper, which is the point: there is no mapping
 * layer to look behind when a value comes out wrong.
 */
final class Rows {

    /** The columns a full {@link SpanRecord} needs. */
    static final String SPAN_COLUMNS = """
            trace_id, span_id, parent_span_id, service, name, kind, start_ns, duration_ns,
            status, status_message, scope, attributes, events""";

    private Rows() {
    }

    static SpanRecord span(ResultSet rs) throws SQLException {
        long startNs = rs.getLong("start_ns");
        return new SpanRecord(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("service"),
                rs.getString("name"),
                rs.getString("kind"),
                startNs,
                startNs + rs.getLong("duration_ns"),
                rs.getString("status"),
                rs.getString("status_message"),
                AttrJson.decode(rs.getString("attributes")),
                AttrJson.decodeEvents(rs.getString("events")),
                rs.getString("scope"));
    }

    static Stats.TraceSummary trace(ResultSet rs) throws SQLException {
        long start = rs.getLong("start_ms");
        return new Stats.TraceSummary(
                rs.getString("trace_id"),
                start,
                Math.max(0, rs.getLong("end_ms") - start),
                rs.getString("root_name"),
                rs.getString("root_service"),
                rs.getString("root_kind"),
                AttrJson.decodeStrings(rs.getString("services")),
                rs.getInt("span_count"),
                rs.getInt("error_count"),
                rs.getInt("db_count"),
                Sql.longOrNull(rs, "http_status"),
                rs.getBoolean("slow"),
                rs.getBoolean("error"));
    }

    static LogRecord log(ResultSet rs) throws SQLException {
        return new LogRecord(
                rs.getLong("id"),
                rs.getLong("at_ms"),
                rs.getString("service"),
                rs.getString("severity"),
                rs.getInt("severity_number"),
                rs.getString("body"),
                rs.getString("logger"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                AttrJson.decode(rs.getString("attributes")));
    }

    /** Nanoseconds as milliseconds; every duration on the wire is milliseconds. */
    static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    static double ms(ResultSet rs, String column) throws SQLException {
        return ms(rs.getLong(column));
    }
}
