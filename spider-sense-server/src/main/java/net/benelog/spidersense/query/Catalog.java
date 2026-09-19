package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.benelog.spidersense.store.Sql;
import net.benelog.spidersilk.json.Json;

/**
 * The index catalog of a service, as the extension read it through JDBC metadata.
 *
 * <p>It is what turns a guess about a statement into a fact: the columns a query
 * filters on are in the SQL, the indexes that serve them are only in the
 * database, and the extension is the one part of Spider Sense that has a
 * connection to it (design.md). The rows it sent are read back here and matched
 * against the statement in {@link SchemaBlock}.
 *
 * <p>One statement per answer: a service has a few dozen tables, and a finding
 * list that asked per statement would ask the same question fifty times.
 */
public final class Catalog {

    /** One index, in the order its columns form the key. */
    public record Index(String name, boolean unique, List<String> columns) {
    }

    /**
     * One table of one service.
     *
     * @param schema null when the database reports none, which is the empty
     *        string in the row (storage.md)
     * @param name   the database's own spelling: {@code ITEMS} on H2,
     *        {@code items} on PostgreSQL
     */
    public record Table(String schema, String name, List<Index> indexes) {
    }

    private final Sql sql;

    public Catalog(Sql sql) {
        this.sql = sql;
    }

    /**
     * Every table of the service, keyed by its lower-cased name.
     *
     * <p>The key is lower-cased because a statement spells a table however its
     * author did and the database spells it however it folds identifiers; the list
     * behind the key keeps both rows when one name is in two schemas, and the
     * caller picks by schema.
     */
    public Map<String, List<Table>> forService(String service) {
        Map<String, List<Table>> byName = new LinkedHashMap<>();
        sql.query("SELECT schema_name, table_name, product, indexes FROM db_table WHERE service = ?",
                List.of(service), rs -> {
                    String name = rs.getString("table_name");
                    List<Index> indexes = indexes(rs.getString("indexes"));
                    if (name == null || indexes == null) {
                        return null;
                    }
                    String schema = rs.getString("schema_name");
                    byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                            .add(new Table(schema == null || schema.isEmpty() ? null : schema, name,
                                    indexes));
                    return null;
                });
        return byName;
    }

    /**
     * The stored JSON array, or null when it is not one.
     *
     * <p>A row whose text does not parse is dropped rather than repaired: the
     * block it would feed says "no index serves this column", and a wrong block is
     * worse than no block at all (agent.md).
     */
    private static List<Index> indexes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Index> indexes = new ArrayList<>();
            for (Json.JsonValue value : Json.parse(json).asArray()) {
                Json.JsonObject index = value.asObject();
                List<String> columns = new ArrayList<>();
                Json.JsonArray stored = index.optArray("columns");
                if (stored != null) {
                    for (Json.JsonValue column : stored) {
                        columns.add(column.asString());
                    }
                }
                indexes.add(new Index(index.optString("name", ""),
                        index.optBoolean("unique", false), List.copyOf(columns)));
            }
            return List.copyOf(indexes);
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
