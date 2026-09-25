package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * One row of the index catalog, {@code db_table} (storage.adoc#schema), in the one
 * shape the writer, the importer and the export share, as {@link SpanRow} is for a
 * span.
 *
 * @param schemaName the empty string when the database reports no schema
 * @param indexes    the JSON array text the column holds, kept as it is even when it is not JSON
 */
public record CatalogRow(String service, String schemaName, String tableName, @Nullable String product,
        String indexes, long seenMs) {

    /**
     * A merge on the row's key: a table looked up again after a restart is the
     * newer truth about the same table, and replaces the older row (storage.adoc#writer).
     */
    static final String MERGE = """
            MERGE INTO db_table (service, schema_name, table_name, product, indexes, seen_ms)
            KEY (service, schema_name, table_name) VALUES (?, ?, ?, ?, ?, ?)""";

    static CatalogRow of(Batch.Catalog catalog) {
        return new CatalogRow(catalog.service(), catalog.schemaName(), catalog.table(), catalog.product(),
                catalog.indexes(), catalog.at());
    }

    /** The row a document holds, or null when it names no service or no table. */
    static @Nullable CatalogRow fromJson(Json.JsonObject table) {
        String service = RowJson.string(table, "service");
        String name = RowJson.string(table, "tableName");
        if (service == null || name == null) {
            return null;
        }
        // The text the export carried when the stored one did not parse, else the array as its text.
        String indexes = table.has("indexes") && table.get("indexes").isString()
                ? table.get("indexes").asString()
                : RowJson.nested(table, "indexes", AttrJson.EMPTY_ARRAY);
        return new CatalogRow(service, RowJson.or(RowJson.string(table, "schemaName"), ""), name,
                RowJson.string(table, "product"), indexes, RowJson.longOr(table, "seenMs", 0));
    }

    /** The row of a {@code SELECT *} on {@code db_table}. */
    public static CatalogRow read(ResultSet rs) throws SQLException {
        return new CatalogRow(
                RowJson.or(rs.getString("service"), ""),
                RowJson.or(rs.getString("schema_name"), ""),
                RowJson.or(rs.getString("table_name"), ""),
                rs.getString("product"),
                RowJson.or(rs.getString("indexes"), AttrJson.EMPTY_ARRAY),
                rs.getLong("seen_ms"));
    }

    /**
     * The row as the export document holds it: {@code indexes} as the array it is,
     * or as its text when that does not parse, since the export repairs nothing
     * (the reader drops such a row, storage.adoc).
     */
    public Json.JsonObject toJson() {
        Json.JsonObject row = Json.obj()
                .put("service", service)
                .put("schemaName", schemaName)
                .put("tableName", tableName)
                .put("product", product);
        try {
            row.put("indexes", RowJson.parsed(indexes, AttrJson.EMPTY_ARRAY));
        } catch (RuntimeException notJson) {
            row.put("indexes", indexes);
        }
        return row.put("seenMs", seenMs);
    }

    /** Binds the row to {@link #MERGE}, each text cut to its column. */
    void bind(PreparedStatement statement) throws SQLException {
        int i = 1;
        statement.setString(i++, Columns.cut(service, Columns.SERVICE));
        statement.setString(i++, Columns.cut(schemaName, Columns.CATALOG_NAME));
        statement.setString(i++, Columns.cut(tableName, Columns.CATALOG_NAME));
        statement.setString(i++, Columns.cut(product, Columns.DB_PRODUCT));
        statement.setString(i++, Columns.cut(indexes, Columns.JSON_TEXT));
        statement.setLong(i, seenMs);
    }
}
