package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code mark} without its id (storage.adoc#schema), in the one shape
 * {@link Marks}, the writer's {@code start} marks, the importer and the export
 * share, as {@link SpanRow} is for a span.
 */
public record MarkRow(long atMs, @Nullable String name, @Nullable String service, @Nullable String note) {

    static final String INSERT = "INSERT INTO mark (at_ms, name, service, note) VALUES (?, ?, ?, ?)";

    static MarkRow fromJson(Json.JsonObject mark) {
        return new MarkRow(RowJson.longOr(mark, "atMs", 0), RowJson.string(mark, "name"),
                RowJson.string(mark, "service"), RowJson.string(mark, "note"));
    }

    /** The row of a {@code SELECT *} on {@code mark}. */
    public static MarkRow read(ResultSet rs) throws SQLException {
        return new MarkRow(rs.getLong("at_ms"), rs.getString("name"), rs.getString("service"),
                rs.getString("note"));
    }

    /** The row as the export document holds it. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("atMs", atMs)
                .put("name", name)
                .put("service", service)
                .put("note", note);
    }

    /** The note as {@link #bind} stores it. */
    @Nullable String storedNote() {
        return Columns.cut(note, Columns.MARK_NOTE);
    }

    /** Binds the row to {@link #INSERT}, the note cut to its column. */
    void bind(PreparedStatement statement) throws SQLException {
        statement.setLong(1, atMs);
        statement.setString(2, name);
        statement.setString(3, service);
        statement.setString(4, storedNote());
    }
}
