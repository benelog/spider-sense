package net.benelog.spidersense.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code trace} rows of the traces a transaction touched, recomputed from
 * every span stored for them, inside that transaction.
 *
 * <p>The writer runs it after each flush's span inserts, and the importer after
 * an imported document's, so an imported trace is summarised exactly as a
 * received one (storage.adoc#writer).
 */
final class TraceSummaries {

    private static final String MERGE_TRACE = """
            MERGE INTO trace (trace_id, start_ms, end_ms, duration_ns, root_span_id, root_name, root_service,
                root_kind, services, span_count, error_count, db_count, http_status, slow, error)
            KEY(trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /**
     * A placeholder for a trace row, merged before the spans are read so that the
     * row is locked for the rest of the transaction. The real values replace it
     * before the commit, so no other connection ever sees it.
     */
    private static final String LOCK_TRACE = """
            MERGE INTO trace (trace_id, start_ms, end_ms, duration_ns, root_name, root_service, root_kind,
                services, span_count, error_count, db_count, slow, error)
            KEY(trace_id) VALUES (?, 0, 0, 0, '', '', '', '[]', 0, 0, 0, FALSE, FALSE)""";

    private final long slowRequestMs;

    /** @param slowRequestMs what a trace must take longer than to be slow, as a request must */
    TraceSummaries(long slowRequestMs) {
        this.slowRequestMs = slowRequestMs;
    }

    /** Recomputes the rows of {@code traceIds} in the caller's transaction, which commits them. */
    void merge(Connection connection, Set<String> traceIds) throws SQLException {
        if (traceIds.isEmpty()) {
            return;
        }
        // Sorted, so two writers locking overlapping sets take the locks in one order.
        List<String> ids = traceIds.stream().sorted().toList();
        lock(connection, ids);
        Map<String, List<TraceSummary.Span>> byTrace = new LinkedHashMap<>();
        // In chunks: a flush after a burst, or an import, touches tens of thousands of traces.
        for (List<String> chunk : Sql.chunks(ids)) {
            readSpans(connection, chunk, byTrace);
        }
        try (PreparedStatement statement = connection.prepareStatement(MERGE_TRACE)) {
            for (List<TraceSummary.Span> spans : byTrace.values()) {
                bind(statement, TraceSummary.of(spans, slowRequestMs));
                statement.addBatch();
            }
            if (!byTrace.isEmpty()) {
                statement.executeBatch();
            }
        }
    }

    private static void readSpans(Connection connection, List<String> ids,
            Map<String, List<TraceSummary.Span>> byTrace) throws SQLException {
        String select = """
                SELECT trace_id, span_id, parent_span_id, service, name, endpoint, kind, start_ms, start_ns,
                       duration_ns, error, db_statement, http_status
                FROM span WHERE trace_id IN (""" + Sql.placeholders(ids.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            Sql.bind(statement, ids);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TraceSummary.Span span = new TraceSummary.Span(rs.getString("trace_id"),
                            rs.getString("span_id"), rs.getString("parent_span_id"), rs.getString("service"),
                            rs.getString("name"), rs.getString("endpoint"), rs.getString("kind"),
                            rs.getLong("start_ms"), rs.getLong("start_ns"), rs.getLong("duration_ns"),
                            rs.getBoolean("error"), rs.getString("db_statement") != null,
                            Sql.longOrNull(rs, "http_status"));
                    byTrace.computeIfAbsent(span.traceId(), id -> new ArrayList<>()).add(span);
                }
            }
        }
    }

    /**
     * Locks the touched trace rows before their spans are read. Another process
     * sharing the file may be flushing other spans of the same trace; without the
     * lock each reads only its own uncommitted spans, and the one that commits
     * second overwrites the row with half the trace. With it, the second waits for
     * the first to commit and then reads every span.
     */
    private static void lock(Connection connection, List<String> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_TRACE)) {
            for (String id : ids) {
                statement.setString(1, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void bind(PreparedStatement statement, TraceSummary trace) throws SQLException {
        int i = 1;
        statement.setString(i++, trace.traceId());
        statement.setLong(i++, trace.startMs());
        statement.setLong(i++, trace.endMs());
        statement.setLong(i++, trace.durationNs());
        statement.setString(i++, trace.rootSpanId());
        statement.setString(i++, trace.rootName());
        statement.setString(i++, trace.rootService());
        statement.setString(i++, trace.rootKind());
        statement.setString(i++, trace.services());
        statement.setInt(i++, trace.spanCount());
        statement.setInt(i++, trace.errorCount());
        statement.setInt(i++, trace.dbCount());
        Columns.setLong(statement, i++, trace.httpStatus());
        statement.setBoolean(i++, trace.slow());
        statement.setBoolean(i, trace.error());
    }
}
