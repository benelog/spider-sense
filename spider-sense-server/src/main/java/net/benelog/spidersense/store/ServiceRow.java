package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code service} (storage.adoc#schema), in the one shape the writer,
 * the importer and the export share, as {@link SpanRow} is for a span.
 *
 * @param resource the JSON object text the column holds
 */
public record ServiceRow(String name, @Nullable String language, @Nullable Long pid, long firstSeen,
        long lastSeen, String resource) {

    static final String INSERT = "INSERT INTO service (name, language, pid, first_seen, last_seen, resource)"
            + " VALUES (?, ?, ?, ?, ?, ?)";

    /** The writer's update of a service it has a row of: {@code first_seen} survives. */
    static final String UPDATE = "UPDATE service SET language = ?, pid = ?, last_seen = ?, resource = ? WHERE name = ?";

    /** The row a sighting describes, first and last seen at its receipt. */
    static ServiceRow of(Batch.Sighting sighting) {
        Object language = sighting.resource().get("telemetry.sdk.language");
        Object pid = sighting.resource().get("process.pid");
        return new ServiceRow(sighting.name(), language == null ? null : String.valueOf(language),
                pid instanceof Number number ? number.longValue() : null, sighting.at(), sighting.at(),
                AttrJson.encode(sighting.resource(), Columns.JSON_TEXT));
    }

    /** The row a document holds, or null when it names no service; its last sighting is its first when it gives none. */
    static @Nullable ServiceRow fromJson(Json.JsonObject service) {
        String name = RowJson.string(service, "name");
        if (name == null) {
            return null;
        }
        long firstSeen = RowJson.longOr(service, "firstSeen", 0);
        return new ServiceRow(name, RowJson.string(service, "language"), RowJson.nullableLong(service, "pid"),
                firstSeen, RowJson.longOr(service, "lastSeen", firstSeen),
                RowJson.nested(service, "resource", AttrJson.EMPTY_OBJECT));
    }

    /** The row of a {@code SELECT *} on {@code service}. */
    public static ServiceRow read(ResultSet rs) throws SQLException {
        return new ServiceRow(
                RowJson.or(rs.getString("name"), ""),
                rs.getString("language"),
                Sql.longOrNull(rs, "pid"),
                rs.getLong("first_seen"),
                rs.getLong("last_seen"),
                RowJson.or(rs.getString("resource"), AttrJson.EMPTY_OBJECT));
    }

    /** The row as the export document holds it, with {@code resource} as the object it is. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("name", name)
                .put("language", language)
                .put("pid", RowJson.number(pid))
                .put("firstSeen", firstSeen)
                .put("lastSeen", lastSeen)
                .put("resource", RowJson.parsed(resource, AttrJson.EMPTY_OBJECT));
    }

    /** Binds the row to {@link #INSERT}, each text cut to its column. */
    void bindInsert(PreparedStatement statement) throws SQLException {
        statement.setString(1, name);
        statement.setString(2, Columns.cut(language, Columns.LANGUAGE));
        Columns.setLong(statement, 3, pid);
        statement.setLong(4, firstSeen);
        statement.setLong(5, lastSeen);
        statement.setString(6, resource);
    }

    /** Binds the row to {@link #UPDATE}, each text cut to its column. */
    void bindUpdate(PreparedStatement statement) throws SQLException {
        statement.setString(1, Columns.cut(language, Columns.LANGUAGE));
        Columns.setLong(statement, 2, pid);
        statement.setLong(3, lastSeen);
        statement.setString(4, resource);
        statement.setString(5, name);
    }
}
