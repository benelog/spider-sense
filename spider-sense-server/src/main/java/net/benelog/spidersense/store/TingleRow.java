package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code tingle} (storage.adoc#schema), in the one shape the writer,
 * the importer and the export share, as {@link SpanRow} is for a span.
 *
 * @param durationMs NaN for a stored NULL, which the export writes as null
 */
public record TingleRow(
        long atMs,
        @Nullable String kind,
        @Nullable String service,
        String title,
        @Nullable String detail,
        @Nullable String traceId,
        @Nullable String spanId,
        double durationMs) {

    /**
     * The row, unless a tingle of the same span and kind is stored: {@code (trace_id, span_id,
     * kind)} is the unique key of {@code tingle}, so an export the exporter sends again raises
     * no second one (storage.adoc#writer). Its update count says whether the row went in, and
     * only a tingle that did is published. A tingle without a span has no key, and goes in
     * as a plain insert would, since a comparison with NULL matches no stored row.
     */
    static final String INSERT = """
            INSERT INTO tingle (at_ms, kind, service, title, detail, trace_id, span_id, duration_ms)
            SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                SELECT 1 FROM tingle WHERE trace_id = ? AND span_id = ? AND kind = ?)""";

    /**
     * The columns that make two tingles the same one, in the order of {@link #sameTingle()}:
     * what the importer recognises a tingle without a span by, since it has no key.
     */
    static final String SAME_TINGLE = """
            SELECT COUNT(*) FROM tingle WHERE at_ms = ? AND kind IS NOT DISTINCT FROM ?
                AND service IS NOT DISTINCT FROM ? AND title = ? AND detail = ?
                AND trace_id IS NOT DISTINCT FROM ? AND span_id IS NOT DISTINCT FROM ?""";

    static TingleRow of(Tingle tingle) {
        return new TingleRow(tingle.at(), tingle.kind(), tingle.service(), tingle.title(), tingle.detail(),
                tingle.traceId(), tingle.spanId(), tingle.durationMs());
    }

    static TingleRow fromJson(Json.JsonObject tingle) {
        return new TingleRow(
                RowJson.longOr(tingle, "atMs", 0),
                RowJson.string(tingle, "kind"),
                RowJson.string(tingle, "service"),
                RowJson.or(RowJson.string(tingle, "title"), ""),
                RowJson.or(RowJson.string(tingle, "detail"), ""),
                RowJson.string(tingle, "traceId"),
                RowJson.string(tingle, "spanId"),
                tingle.optDouble("durationMs", 0));
    }

    /** The row of a {@code SELECT *} on {@code tingle}. */
    public static TingleRow read(ResultSet rs) throws SQLException {
        return new TingleRow(
                rs.getLong("at_ms"),
                rs.getString("kind"),
                rs.getString("service"),
                RowJson.or(rs.getString("title"), ""),
                rs.getString("detail"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                RowJson.doubleOrNaN(rs, "duration_ms"));
    }

    /** The row as the export document holds it. */
    public Json.JsonObject toJson() {
        return Json.obj()
                .put("atMs", atMs)
                .put("kind", kind)
                .put("service", service)
                .put("title", title)
                .put("detail", detail)
                .put("traceId", traceId)
                .put("spanId", spanId)
                .put("durationMs", RowJson.number(durationMs));
    }

    /** The values {@link #SAME_TINGLE} binds, cut as {@link #bind} cuts them, so a stored tingle compares equal. */
    List<@Nullable Object> sameTingle() {
        return Arrays.asList(atMs, kind, service, Columns.cut(title, Columns.TINGLE_TITLE),
                Columns.cut(detail, Columns.TINGLE_DETAIL), traceId, spanId);
    }

    /** Whether the row has the key {@link #INSERT} checks: a tingle without a span has none. */
    boolean hasSpan() {
        return traceId != null && spanId != null;
    }

    /** Binds the row to {@link #INSERT}, each text cut to its column, and then its key. */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setLong(i++, atMs);
        statement.setString(i++, kind);
        statement.setString(i++, service);
        statement.setString(i++, Columns.cut(title, Columns.TINGLE_TITLE));
        statement.setString(i++, Columns.cut(detail, Columns.TINGLE_DETAIL));
        statement.setString(i++, traceId);
        statement.setString(i++, spanId);
        statement.setDouble(i++, durationMs);
        statement.setString(i++, traceId);
        statement.setString(i++, spanId);
        statement.setString(i, kind);
    }
}
