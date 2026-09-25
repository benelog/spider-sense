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
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.CatalogRow;
import net.benelog.spidersense.store.LogRow;
import net.benelog.spidersense.store.MarkRow;
import net.benelog.spidersense.store.MetricRow;
import net.benelog.spidersense.store.PointRow;
import net.benelog.spidersense.store.Schema;
import net.benelog.spidersense.store.SeriesRow;
import net.benelog.spidersense.store.ServiceRow;
import net.benelog.spidersense.store.SpanRow;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.TingleRow;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The whole window as one JSON document: every service, span, log record,
 * metric, series, point, tingle and mark, and the index catalog of its services
 * (cli.adoc#export-import).
 *
 * <p>It is written straight to the response — or to the file the CLI named —
 * rather than built as a tree first. A session worth exporting is the one that
 * is too big to describe in a message, so the largest thing alive here is one
 * row: the result sets are walked and each row is written as it is read.
 *
 * <p>The keys are the column names of storage.adoc#schema in camelCase, and nothing is
 * derived: the point of the document is that importing it reproduces the rows,
 * so {@code entry}, {@code slow}, {@code endpointId} and the rest travel as they
 * are stored rather than being decided again on the other side. Each row is
 * written by the {@code toJson} of the store's row record for its table
 * ({@link SpanRow} and the others), whose {@code fromJson} the importer reads it back with.
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
     * @param service    one service, or null for every one of them
     * @param exportedAt when the document says it was written, in epoch milliseconds
     */
    static void write(Sql sql, Window window, @Nullable String service, long exportedAt,
            OutputStream out) {
        // Work always answers with something; there is nothing to answer with here.
        Boolean unused = sql.withConnection(connection -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 8192);
            try {
                document(connection, window, service, exportedAt, writer);
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("could not write the export", e);
            }
            return Boolean.TRUE;
        }, "export the window");
    }

    private static void document(Connection connection, Window window, @Nullable String service,
            long exportedAt, Writer out)
            throws SQLException, IOException {
        out.write("{\"spiderSense\":");
        out.write(Json.obj()
                .put("version", Version.CURRENT)
                .put("schema", Schema.VERSION)
                .put("exportedAt", exportedAt)
                .put("window", Json.obj().put("from", window.from()).put("to", window.to()))
                .put("service", service)
                .toJson());

        section(out, "services", connection, services(service), rs -> ServiceRow.read(rs).toJson());
        section(out, "spans", connection, spans(window, service), rs -> SpanRow.read(rs).toJson());
        section(out, "logs", connection, logs(window, service), rs -> LogRow.read(rs).toJson());
        section(out, "metrics", connection, metrics(), rs -> MetricRow.read(rs).toJson());
        section(out, "metricSeries", connection, series(window, service), rs -> SeriesRow.read(rs).toJson());
        section(out, "metricPoints", connection, points(window, service), rs -> PointRow.read(rs).toJson());
        section(out, "tingles", connection, tingles(window, service), rs -> TingleRow.read(rs).toJson());
        section(out, "marks", connection, marks(window, service), rs -> MarkRow.read(rs).toJson());
        section(out, "dbTables", connection, catalog(service), rs -> CatalogRow.read(rs).toJson());
        out.write("}");
    }

    /** One row of a result set, as the object the document holds. */
    @FunctionalInterface
    private interface Row {
        Json.JsonObject of(ResultSet rs) throws SQLException;
    }

    /** A statement and the values it binds, kept together so a section is one expression. */
    private record Select(String sql, List<Object> params) {
    }

    private static void section(Writer out, String name, Connection connection, Select select,
            Row row) throws SQLException, IOException {
        out.write(",\"");
        out.write(name);
        out.write("\":[");
        try (PreparedStatement statement = connection.prepareStatement(select.sql())) {
            Sql.bind(statement, select.params());
            try (ResultSet rs = statement.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        out.write(',');
                    }
                    first = false;
                    out.write(row.of(rs).toJson());
                }
            }
        }
        out.write(']');
    }

    // --- the sections -----------------------------------------------------------

    private static Select services(@Nullable String service) {
        return service == null
                ? new Select("SELECT * FROM service ORDER BY name", List.of())
                : new Select("SELECT * FROM service WHERE name = ? ORDER BY name", List.of(service));
    }

    private static Select spans(Window window, @Nullable String service) {
        return windowed("SELECT * FROM span WHERE start_ms BETWEEN ? AND ?", "service",
                " ORDER BY start_ms, id", window, service);
    }

    private static Select logs(Window window, @Nullable String service) {
        return windowed("SELECT * FROM log WHERE at_ms BETWEEN ? AND ?", "service",
                " ORDER BY at_ms, id", window, service);
    }

    /**
     * The instrument metadata is not windowed: it describes the series the points
     * belong to, and a description has no instant of its own.
     */
    private static Select metrics() {
        return new Select("SELECT * FROM metric ORDER BY service, name", List.of());
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

    private static Select tingles(Window window, @Nullable String service) {
        return windowed("SELECT * FROM tingle WHERE at_ms BETWEEN ? AND ?", "service",
                " ORDER BY at_ms, id", window, service);
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

    /**
     * The index catalog of the services the document carries, windowed by service
     * and not by time: the extension reads a table's indexes once per process, so
     * the row that describes the window's statements was usually written before
     * the window began.
     */
    private static Select catalog(@Nullable String service) {
        return service == null
                ? new Select("SELECT * FROM db_table ORDER BY service, schema_name, table_name",
                        List.of())
                : new Select("SELECT * FROM db_table WHERE service = ?"
                        + " ORDER BY service, schema_name, table_name", List.of(service));
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
}
