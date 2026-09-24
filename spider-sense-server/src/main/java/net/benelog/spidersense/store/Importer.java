package net.benelog.spidersense.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * An exported session document, written back into the store
 * (cli.adoc#export-import).
 *
 * <p>It is the {@link Writer}'s other caller: the rows come from a file rather
 * than from an OTLP export, but they take the writer's own span insert and its
 * {@code trace} merge, so an imported trace is stored exactly as a received one
 * (storage.adoc). Nothing is recomputed — {@code entry}, {@code slow},
 * {@code query_id} and the rest are columns the file carries, and recomputing
 * them against this machine's thresholds would make a re-import disagree with
 * the session it came from.
 *
 * <p>Importing the same file twice must not double the data, so every section
 * has an identity: a trace whose id already has a span is skipped whole, a log
 * line or a tingle is skipped when an identical row is already stored, a
 * metric point merges on {@code (series, at)}, a series is looked up by
 * {@code (service, name, attributes)}, a service row is merged, and a mark is
 * skipped when one with the same name and instant exists, and a catalog row is
 * merged on {@code (service, schema_name, table_name)} as the writer merges it.
 * The one thing an
 * import never writes is a {@code start} mark: the application it would claim to
 * have started is not running here.
 */
public final class Importer {

    /** What {@code POST /api/import} answers with. */
    public record Result(long spans, long logs, long metricPoints, long tingles, long marks,
            long dbTables, long skippedTraces, long from, long to) {
    }

    /** A document of another schema version: there is nothing safe to do with it. */
    public static final class WrongSchema extends RuntimeException {
        public WrongSchema(String message) {
            super(message);
        }
    }

    /** How many ids go into one {@code IN (...)} list. */
    private static final int CHUNK = 500;

    private final Sql sql;
    private final Writer writer;

    Importer(Sql sql, Writer writer) {
        this.sql = sql;
        this.writer = writer;
    }

    /**
     * Refuses a document this version cannot read.
     *
     * @throws WrongSchema naming both versions, which is what the {@code 400} says
     */
    public static void checkSchema(Json.JsonObject document) {
        Json.JsonObject header = document == null ? null : document.optObject("spiderSense");
        long schema = header == null ? -1 : header.optLong("schema", -1);
        if (schema != Schema.VERSION) {
            throw new WrongSchema("the document is schema version "
                    + (schema < 0 ? "unknown" : String.valueOf(schema))
                    + " and this Spider Sense reads schema version " + Schema.VERSION);
        }
    }

    /**
     * Writes the whole document in one transaction: either the session arrives or
     * nothing of it does, because half an imported trace is worse than none.
     */
    public Result importDocument(Json.JsonObject document) {
        checkSchema(document);
        try (Connection connection = sql.connection()) {
            connection.setAutoCommit(false);
            try {
                Result result = write(connection, document);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new Sql.SqlException("could not import the document", e);
        }
    }

    private Result write(Connection connection, Json.JsonObject document) throws SQLException {
        Json.JsonArray spans = array(document, "spans");
        Set<String> present = alreadyStored(connection, traceIds(spans));

        mergeServices(connection, array(document, "services"));
        insertMetrics(connection, array(document, "metrics"));
        Map<Long, Long> series = mapSeries(connection, array(document, "metricSeries"));
        long points = mergePoints(connection, array(document, "metricPoints"), series);

        Imported imported = insertSpans(connection, spans, present);
        writer.mergeTraces(connection, imported.traces());
        long logs = insertLogs(connection, array(document, "logs"), present);
        long tingles = insertTingles(connection, array(document, "tingles"), present);
        long marks = insertMarks(connection, array(document, "marks"));
        long tables = mergeCatalog(connection, array(document, "dbTables"));

        long from = imported.count() > 0 ? imported.from() : windowFrom(document);
        long to = imported.count() > 0 ? imported.to() : windowTo(document);
        return new Result(imported.count(), logs, points, tingles, marks, tables, present.size(),
                from, to);
    }

    // --- spans ----------------------------------------------------------------

    /** What the span pass found: how many rows, which traces, and the instants they span. */
    private record Imported(long count, Set<String> traces, long from, long to) {
    }

    private static Set<String> traceIds(Json.JsonArray spans) {
        Set<String> ids = new LinkedHashSet<>();
        for (Json.JsonValue value : spans) {
            String id = string(value.asObject(), "traceId");
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The file's trace ids that already have a span row here; their rows are skipped. */
    private static Set<String> alreadyStored(Connection connection, Set<String> ids)
            throws SQLException {
        Set<String> present = new LinkedHashSet<>();
        List<String> all = List.copyOf(ids);
        for (int start = 0; start < all.size(); start += CHUNK) {
            List<String> chunk = all.subList(start, Math.min(all.size(), start + CHUNK));
            String select = "SELECT DISTINCT trace_id FROM span WHERE trace_id IN ("
                    + Sql.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        present.add(rs.getString(1));
                    }
                }
            }
        }
        return present;
    }

    private Imported insertSpans(Connection connection, Json.JsonArray spans, Set<String> skip)
            throws SQLException {
        Set<String> touched = new LinkedHashSet<>();
        long count = 0;
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        try (PreparedStatement statement = connection.prepareStatement(Writer.INSERT_SPAN)) {
            for (Json.JsonValue value : spans) {
                Json.JsonObject span = value.asObject();
                String traceId = string(span, "traceId");
                if (traceId == null || skip.contains(traceId)) {
                    continue;
                }
                bindSpan(statement, span);
                statement.addBatch();
                touched.add(traceId);
                long startMs = longOr(span, "startMs", 0);
                from = Math.min(from, startMs);
                to = Math.max(to, startMs);
                count++;
            }
            if (count > 0) {
                statement.executeBatch();
            }
        }
        return new Imported(count, touched, count > 0 ? from : 0, count > 0 ? to : 0);
    }

    /**
     * The row as the file holds it.
     *
     * <p>Every column is bound from the document, not derived: {@code entry} and
     * {@code slow} were decided once, when the row was first written, and the
     * session that exported them may have run with other thresholds (storage.adoc).
     */
    private static void bindSpan(PreparedStatement statement, Json.JsonObject span)
            throws SQLException {
        int i = 1;
        statement.setString(i++, string(span, "traceId"));
        statement.setString(i++, string(span, "spanId"));
        statement.setString(i++, string(span, "parentSpanId"));
        statement.setString(i++, string(span, "service"));
        statement.setString(i++, Writer.cut(string(span, "name"), 1024));
        statement.setString(i++, string(span, "kind"));
        statement.setLong(i++, longOr(span, "startMs", 0));
        statement.setLong(i++, longOr(span, "startNs", 0));
        statement.setLong(i++, longOr(span, "durationNs", 0));
        statement.setString(i++, string(span, "status"));
        statement.setString(i++, Writer.cut(string(span, "statusMessage"), 4096));
        statement.setBoolean(i++, flag(span, "entry"));
        statement.setBoolean(i++, flag(span, "error"));
        statement.setBoolean(i++, flag(span, "slow"));
        statement.setString(i++, string(span, "category"));
        statement.setString(i++, Writer.cut(string(span, "endpoint"), 1024));
        statement.setString(i++, string(span, "endpointId"));
        statement.setString(i++, Writer.cut(string(span, "httpMethod"), 16));
        statement.setString(i++, Writer.cut(string(span, "httpRoute"), 1024));
        Writer.setLong(statement, i++, number(span, "httpStatus"));
        statement.setString(i++, Writer.cut(string(span, "dbSystem"), 64));
        statement.setString(i++, string(span, "dbStatement"));
        statement.setString(i++, Writer.cut(string(span, "dbNamespace"), 255));
        statement.setString(i++, Writer.cut(string(span, "dbOperation"), 64));
        statement.setString(i++, Writer.cut(string(span, "dbTable"), 255));
        statement.setString(i++, string(span, "queryId"));
        statement.setString(i++, Writer.cut(string(span, "errorType"), 512));
        statement.setString(i++, Writer.cut(string(span, "errorMessage"), 4096));
        statement.setString(i++, string(span, "errorId"));
        statement.setString(i++, Writer.cut(string(span, "scope"), 255));
        statement.setString(i++, nested(span, "attributes", AttrJson.EMPTY_OBJECT));
        statement.setString(i, nested(span, "events", AttrJson.EMPTY_ARRAY));
    }

    // --- logs and tingles ------------------------------------------------------

    /** The columns that make two log lines the same line; the attributes are left out. */
    private static final String SAME_LOG = """
            SELECT COUNT(*) FROM log WHERE service IS NOT DISTINCT FROM ? AND at_ms = ?
                AND severity_number = ? AND body = ? AND logger IS NOT DISTINCT FROM ?
                AND trace_id IS NOT DISTINCT FROM ? AND span_id IS NOT DISTINCT FROM ?""";

    /**
     * The file's log lines, less those of a skipped trace and those already stored.
     *
     * <p>A line outside any span (a worker's job log, a startup line) has no trace
     * to be skipped with, so it is recognised by its own columns. The check counts:
     * two identical lines in the file are two lines, and a second import of it
     * finds both and writes neither.
     */
    private static long insertLogs(Connection connection, Json.JsonArray logs, Set<String> skip)
            throws SQLException {
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(Writer.INSERT_LOG);
                PreparedStatement same = connection.prepareStatement(SAME_LOG)) {
            Stored stored = new Stored(same);
            for (Json.JsonValue value : logs) {
                Json.JsonObject log = value.asObject();
                String traceId = string(log, "traceId");
                if (traceId != null && skip.contains(traceId)) {
                    continue;
                }
                List<@Nullable Object> row = Arrays.asList(string(log, "service"), longOr(log, "atMs", 0),
                        longOr(log, "severityNumber", 0), Writer.cut(or(string(log, "body"), ""), 65535),
                        Writer.cut(string(log, "logger"), 512), traceId, string(log, "spanId"));
                if (stored.contains(row)) {
                    continue;
                }
                int i = 1;
                statement.setLong(i++, longOr(log, "atMs", 0));
                statement.setString(i++, string(log, "service"));
                statement.setInt(i++, (int) longOr(log, "severityNumber", 0));
                statement.setString(i++, Writer.cut(string(log, "severity"), 8));
                statement.setString(i++, Writer.cut(or(string(log, "body"), ""), 65535));
                statement.setString(i++, Writer.cut(string(log, "logger"), 512));
                statement.setString(i++, traceId);
                statement.setString(i++, string(log, "spanId"));
                statement.setString(i, nested(log, "attributes", AttrJson.EMPTY_OBJECT));
                statement.addBatch();
                count++;
            }
            if (count > 0) {
                statement.executeBatch();
            }
        }
        return count;
    }

    /** The columns that make two tingles the same one. */
    private static final String SAME_TINGLE = """
            SELECT COUNT(*) FROM tingle WHERE at_ms = ? AND kind IS NOT DISTINCT FROM ?
                AND service IS NOT DISTINCT FROM ? AND title = ? AND detail = ?
                AND trace_id IS NOT DISTINCT FROM ? AND span_id IS NOT DISTINCT FROM ?""";

    /** The file's tingles, less those of a skipped trace and those already stored, as for logs. */
    private static long insertTingles(Connection connection, Json.JsonArray tingles, Set<String> skip)
            throws SQLException {
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(Writer.INSERT_TINGLE);
                PreparedStatement same = connection.prepareStatement(SAME_TINGLE)) {
            Stored stored = new Stored(same);
            for (Json.JsonValue value : tingles) {
                Json.JsonObject tingle = value.asObject();
                String traceId = string(tingle, "traceId");
                if (traceId != null && skip.contains(traceId)) {
                    continue;
                }
                List<@Nullable Object> row = Arrays.asList(longOr(tingle, "atMs", 0), string(tingle, "kind"),
                        string(tingle, "service"), Writer.cut(or(string(tingle, "title"), ""), 1024),
                        Writer.cut(or(string(tingle, "detail"), ""), 4096), traceId, string(tingle, "spanId"));
                if (stored.contains(row)) {
                    continue;
                }
                int i = 1;
                statement.setLong(i++, longOr(tingle, "atMs", 0));
                statement.setString(i++, string(tingle, "kind"));
                statement.setString(i++, string(tingle, "service"));
                statement.setString(i++, Writer.cut(or(string(tingle, "title"), ""), 1024));
                statement.setString(i++, Writer.cut(or(string(tingle, "detail"), ""), 4096));
                statement.setString(i++, traceId);
                statement.setString(i++, string(tingle, "spanId"));
                statement.setDouble(i, tingle.optDouble("durationMs", 0));
                statement.addBatch();
                count++;
            }
            if (count > 0) {
                statement.executeBatch();
            }
        }
        return count;
    }

    /**
     * How many rows identical to a given one the store held before the import,
     * spent one per identical row of the file.
     *
     * <p>The count is read once per distinct row, before any of the file's rows is
     * written, so the file's own duplicates are matched against the store and not
     * against each other.
     */
    private static final class Stored {

        private final PreparedStatement count;
        private final Map<List<@Nullable Object>, long[]> remaining = new HashMap<>();

        Stored(PreparedStatement count) {
            this.count = count;
        }

        /** Whether one more row equal to {@code row} is already stored, which it then uses up. */
        boolean contains(List<@Nullable Object> row) throws SQLException {
            long[] left = remaining.get(row);
            if (left == null) {
                left = new long[]{stored(row)};
                remaining.put(row, left);
            }
            if (left[0] > 0) {
                left[0]--;
                return true;
            }
            return false;
        }

        private long stored(List<@Nullable Object> row) throws SQLException {
            for (int i = 0; i < row.size(); i++) {
                count.setObject(i + 1, row.get(i));
            }
            try (ResultSet rs = count.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // --- marks ------------------------------------------------------------------

    /**
     * A mark is its name and its instant: the same pair twice is the same moment,
     * however often the file is imported.
     */
    private static long insertMarks(Connection connection, Json.JsonArray marks) throws SQLException {
        long count = 0;
        for (Json.JsonValue value : marks) {
            Json.JsonObject mark = value.asObject();
            String name = string(mark, "name");
            if (name == null) {
                continue;
            }
            long at = longOr(mark, "atMs", 0);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mark WHERE name = ? AND at_ms = ?")) {
                select.setString(1, name);
                select.setLong(2, at);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        continue;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO mark (at_ms, name, service, note) VALUES (?, ?, ?, ?)")) {
                insert.setLong(1, at);
                insert.setString(2, name);
                insert.setString(3, string(mark, "service"));
                insert.setString(4, Writer.cut(string(mark, "note"), 1024));
                insert.executeUpdate();
            }
            count++;
        }
        return count;
    }

    // --- the index catalog ----------------------------------------------------------

    /**
     * The writer's own merge on {@code (service, schema_name, table_name)}: a
     * table the file describes replaces the row this store has for it, as a table
     * looked up again after a restart does (storage.adoc), so a second import of the
     * same file leaves the one row it wrote.
     */
    private static long mergeCatalog(Connection connection, Json.JsonArray tables)
            throws SQLException {
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(Writer.MERGE_CATALOG)) {
            for (Json.JsonValue value : tables) {
                Json.JsonObject table = value.asObject();
                String service = string(table, "service");
                String name = string(table, "tableName");
                if (service == null || name == null) {
                    continue;
                }
                int i = 1;
                statement.setString(i++, Writer.cut(service, 255));
                statement.setString(i++, Writer.cut(or(string(table, "schemaName"), ""), 255));
                statement.setString(i++, Writer.cut(name, 255));
                statement.setString(i++, Writer.cut(string(table, "product"), 64));
                statement.setString(i++, Writer.cut(indexes(table), 65535));
                statement.setLong(i, longOr(table, "seenMs", 0));
                statement.addBatch();
                count++;
            }
            if (count > 0) {
                statement.executeBatch();
            }
        }
        return count;
    }

    /** The array as its text, or the text the export carried when the stored one did not parse. */
    private static String indexes(Json.JsonObject table) {
        if (table.has("indexes") && table.get("indexes").isString()) {
            return table.get("indexes").asString();
        }
        return nested(table, "indexes", AttrJson.EMPTY_ARRAY);
    }

    // --- services ----------------------------------------------------------------

    /**
     * {@code first_seen} is the older of the two and {@code last_seen} the newer,
     * so importing an older session into a live database widens the row rather
     * than rewriting it. No {@code start} mark: an import is not a restart.
     */
    private static void mergeServices(Connection connection, Json.JsonArray services)
            throws SQLException {
        for (Json.JsonValue value : services) {
            Json.JsonObject service = value.asObject();
            String name = string(service, "name");
            if (name == null) {
                continue;
            }
            long firstSeen = longOr(service, "firstSeen", 0);
            long lastSeen = longOr(service, "lastSeen", firstSeen);
            String resource = nested(service, "resource", AttrJson.EMPTY_OBJECT);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE service SET language = ?, pid = ?, first_seen = LEAST(first_seen, ?),"
                            + " last_seen = GREATEST(last_seen, ?), resource = ? WHERE name = ?")) {
                update.setString(1, Writer.cut(string(service, "language"), 64));
                Writer.setLong(update, 2, number(service, "pid"));
                update.setLong(3, firstSeen);
                update.setLong(4, lastSeen);
                update.setString(5, resource);
                update.setString(6, name);
                if (update.executeUpdate() > 0) {
                    continue;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO service (name, language, pid, first_seen, last_seen, resource)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, name);
                insert.setString(2, Writer.cut(string(service, "language"), 64));
                Writer.setLong(insert, 3, number(service, "pid"));
                insert.setLong(4, firstSeen);
                insert.setLong(5, lastSeen);
                insert.setString(6, resource);
                insert.executeUpdate();
            }
        }
    }

    // --- metrics -------------------------------------------------------------------

    /** Metadata is a description of an instrument, so the stored one wins if there is one. */
    private static void insertMetrics(Connection connection, Json.JsonArray metrics)
            throws SQLException {
        for (Json.JsonValue value : metrics) {
            Json.JsonObject metric = value.asObject();
            String name = string(metric, "name");
            if (name == null) {
                continue;
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM metric WHERE name = ?")) {
                select.setString(1, name);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        continue;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO metric (name, type, unit, description, monotonic, temporality)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, name);
                insert.setString(2, or(string(metric, "type"), "gauge"));
                insert.setString(3, Writer.cut(string(metric, "unit"), 64));
                insert.setString(4, Writer.cut(string(metric, "description"), 1024));
                insert.setBoolean(5, flag(metric, "monotonic"));
                insert.setString(6, string(metric, "temporality"));
                insert.executeUpdate();
            }
        }
    }

    /**
     * The file's series ids are its own; here they are whatever
     * {@code (service, name, attributes)} already is, or a new row.
     */
    private static Map<Long, Long> mapSeries(Connection connection, Json.JsonArray series)
            throws SQLException {
        Map<Long, Long> ids = new HashMap<>();
        for (Json.JsonValue value : series) {
            Json.JsonObject row = value.asObject();
            String service = string(row, "service");
            String name = string(row, "name");
            if (service == null || name == null) {
                continue;
            }
            String attributes = AttrJson.encodeSorted(
                    AttrJson.decode(nested(row, "attributes", AttrJson.EMPTY_OBJECT)));
            ids.put(longOr(row, "id", 0),
                    lookupOrCreate(connection, service, name, attributes));
        }
        return ids;
    }

    private static long lookupOrCreate(Connection connection, String service, String name,
            String attributes) throws SQLException {
        String hash = Ids.shortHash(attributes);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM metric_series WHERE service = ? AND name = ? AND attr_hash = ?")) {
            select.setString(1, service);
            select.setString(2, name);
            select.setString(3, hash);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO metric_series (service, name, attr_hash, attributes) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, service);
            insert.setString(2, name);
            insert.setString(3, hash);
            insert.setString(4, Writer.cut(attributes, 4096));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("No id for metric series " + name);
    }

    private static long mergePoints(Connection connection, Json.JsonArray points,
            Map<Long, Long> series) throws SQLException {
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(Writer.MERGE_POINT)) {
            for (Json.JsonValue value : points) {
                Json.JsonObject point = value.asObject();
                Long seriesId = series.get(longOr(point, "seriesId", -1));
                if (seriesId == null) {
                    continue;
                }
                int i = 1;
                statement.setLong(i++, seriesId);
                statement.setLong(i++, longOr(point, "atMs", 0));
                statement.setDouble(i++, point.optDouble("value", 0));
                statement.setLong(i++, longOr(point, "count", 0));
                statement.setDouble(i++, point.optDouble("sum", 0));
                statement.setDouble(i++, point.optDouble("min", 0));
                statement.setDouble(i++, point.optDouble("max", 0));
                statement.setString(i, buckets(point));
                statement.addBatch();
                count++;
            }
            if (count > 0) {
                statement.executeBatch();
            }
        }
        return count;
    }

    private static @Nullable String buckets(Json.JsonObject point) {
        Json.JsonObject object = point.optObject("buckets");
        return object == null ? null : Writer.cut(object.toJson(), 8192);
    }

    // --- reading the document -------------------------------------------------------

    private static Json.JsonArray array(Json.JsonObject document, String key) {
        Json.JsonArray array = document.optArray(key);
        return array == null ? Json.arr() : array;
    }

    private static long windowFrom(Json.JsonObject document) {
        Json.JsonObject window = window(document);
        return window == null ? 0 : window.optLong("from", 0);
    }

    private static long windowTo(Json.JsonObject document) {
        Json.JsonObject window = window(document);
        return window == null ? 0 : window.optLong("to", 0);
    }

    private static Json.@Nullable JsonObject window(Json.JsonObject document) {
        Json.JsonObject header = document.optObject("spiderSense");
        return header == null ? null : header.optObject("window");
    }

    private static @Nullable String string(Json.JsonObject object, String key) {
        return AttrJson.optionalString(object, key);
    }

    /** A nullable integer column: JSON null stays null rather than becoming zero. */
    private static @Nullable Long number(Json.JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isNull()) {
            return null;
        }
        return object.get(key).asLong();
    }

    private static long longOr(Json.JsonObject object, String key, long fallback) {
        return object.optLong(key, fallback);
    }

    private static boolean flag(Json.JsonObject object, String key) {
        return object.optBoolean(key, false);
    }

    /** A nested object or array as the JSON text its column holds. */
    private static String nested(Json.JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isNull()) {
            return fallback;
        }
        return object.get(key).toJson();
    }

    private static String or(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }
}
