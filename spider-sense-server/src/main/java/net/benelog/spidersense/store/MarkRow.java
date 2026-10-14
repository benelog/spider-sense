package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code mark} without its id (storage.adoc#schema), in the one shape
 * {@link Marks}, the writer's {@code start} marks, the importer and the export
 * share, as {@link SpanRow} is for a span.
 */
public record MarkRow(long atMs, @Nullable String name, @Nullable String service, @Nullable String note) {

    static final String INSERT = "INSERT INTO mark (at_ms, name, service, note) VALUES (?, ?, ?, ?)";

    static MarkRow fromJson(JsonObject mark) {
        return new MarkRow(RowJson.longOr(mark, "atMs", 0), RowJson.string(mark, "name"),
                RowJson.string(mark, "service"), RowJson.string(mark, "note"));
    }

    /** The row of a {@code SELECT *} on {@code mark}. */
    public static MarkRow read(ResultSet rs) throws SQLException {
        return new MarkRow(rs.getLong("at_ms"), rs.getString("name"), rs.getString("service"),
                rs.getString("note"));
    }

    /** The row as the export document holds it. */
    public JsonObject toJson() {
        return Json.object()
                .put("atMs", atMs)
                .put("name", name)
                .put("service", service)
                .put("note", note);
    }

    /**
     * The service as {@link #bind} stores it: well-formed ({@link Utf16}) and cut to the width
     * every service name has.
     */
    @Nullable String storedService() {
        return Columns.cut(Utf16.wellFormedOrNull(service), Columns.SERVICE);
    }

    /** The note as {@link #bind} stores it: well-formed and cut to its column. */
    @Nullable String storedNote() {
        return Columns.cut(Utf16.wellFormedOrNull(note), Columns.MARK_NOTE);
    }

    /** Binds the row to {@link #INSERT}, the service and the note made well-formed and cut to their columns. */
    void bind(PreparedStatement statement) throws SQLException {
        statement.setLong(1, atMs);
        statement.setString(2, name);
        statement.setString(3, storedService());
        statement.setString(4, storedNote());
    }
}
