package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.benelog.spidersense.store.SpanRecord;
import org.jspecify.annotations.Nullable;

/**
 * Which columns a statement filters on, and which of them no index leads with.
 *
 * <p>The two halves come from different places and that is the point: the
 * predicates are read from the statement ({@link SqlShape}), the indexes are
 * read from the database by the extension ({@link Catalog}), so the answer is a
 * fact rather than a guess about what a schema probably looks like
 * (findings.adoc#schema).
 *
 * <p>Everything about it is all-or-nothing. A statement whose tables the scanner
 * cannot vouch for, or one table of which the catalog has never heard, produces
 * no block at all: a reader who is told which columns are unindexed will act on
 * it, and a half-read statement would send them to index the wrong column.
 *
 * @param tables the catalog rows of the statement's tables, in the order it names them
 */
public record SchemaBlock(List<Catalog.Table> tables, List<String> predicates, List<String> unindexed) {

    /**
     * The block for one statement, or null when it cannot be vouched for.
     *
     * @param catalog the tables of the statement's service, keyed by lower-cased
     *        name, as {@link Catalog#forService(String)} returns them
     */
    public static @Nullable SchemaBlock of(@Nullable String statement,
            @Nullable Map<String, List<Catalog.Table>> catalog) {
        if (statement == null || statement.isBlank() || catalog == null || catalog.isEmpty()) {
            return null;
        }
        if (SpanRecord.statementMayBeCut(statement)) {
            // A cut statement may end inside a predicate ("… and i"), whose stub
            // would read as a column. Only a statement known to be whole is vouched for.
            return null;
        }
        SqlShape shape = SqlShape.of(statement);
        if (!shape.readable()) {
            return null;
        }
        List<Catalog.Table> tables = new ArrayList<>();
        Map<String, Catalog.Table> byStatementName = new LinkedHashMap<>();
        for (SqlShape.TableRef reference : shape.tables()) {
            Catalog.Table known = pick(catalog.get(reference.name().toLowerCase(Locale.ROOT)),
                    reference.schema());
            if (known == null) {
                return null;
            }
            Catalog.Table named = byStatementName.put(reference.name(), known);
            if (named != null && !named.equals(known)) {
                // One name in two schemas: a column is attributed by the table's name alone,
                // so which of the two it belongs to cannot be told.
                return null;
            }
            tables.add(known);
        }

        List<String> predicates = new ArrayList<>();
        List<String> unindexed = new ArrayList<>();
        for (SqlShape.ColumnRef column : shape.predicates()) {
            predicates.add(column.table() + "." + column.column());
            if (!served(byStatementName.get(column.table()), column.column())) {
                unindexed.add(column.table() + "." + column.column());
            }
        }
        return new SchemaBlock(List.copyOf(tables), List.copyOf(predicates), List.copyOf(unindexed));
    }

    /**
     * The catalog row for a table the statement names.
     *
     * <p>A name in two schemas is two rows; the statement decides when it says
     * which schema it meant. A schema the statement names and no row has is a table
     * the catalog has no row for, however many of that name other schemas hold; only
     * a row with no schema, from a database that reports none (MySQL's are catalogs),
     * may still be the one. A name the statement does not qualify is never the
     * database's own catalog table ({@code INFORMATION_SCHEMA.USERS} on H2), and
     * when two other schemas still hold it, which one the database resolved it to
     * cannot be told here, so there is no row (findings.adoc#schema).
     */
    private static Catalog.@Nullable Table pick(@Nullable List<Catalog.Table> rows,
            @Nullable String schema) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (schema != null) {
            Catalog.Table unplaced = null;
            for (Catalog.Table row : rows) {
                if (row.schema() == null) {
                    unplaced = unplaced == null ? row : unplaced;
                } else if (row.schema().equalsIgnoreCase(schema)) {
                    return row;
                }
            }
            return unplaced;
        }
        Catalog.Table only = null;
        for (Catalog.Table row : rows) {
            if (isSystemSchema(row.schema())) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = row;
        }
        return only;
    }

    /** The schemas a database describes itself in, whose tables an unqualified name never means. */
    private static boolean isSystemSchema(@Nullable String schema) {
        return schema != null && (schema.equalsIgnoreCase("INFORMATION_SCHEMA")
                || schema.equalsIgnoreCase("pg_catalog"));
    }

    /**
     * Whether an index can seek on the column: one of them has it first.
     *
     * <p>A column an index carries second is served no better by it than a column
     * no index carries at all, so only the leading column counts (findings.adoc#schema).
     */
    private static boolean served(Catalog.@Nullable Table table, String column) {
        if (table == null) {
            return false;
        }
        for (Catalog.Index index : table.indexes()) {
            if (!index.columns().isEmpty() && leads(index.columns().get(0), column)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an index's first key part is the column, or an expression over it.
     *
     * <p>PostgreSQL's driver names the key part of an expression index by the
     * expression's text ({@code lower((email)::text)}), and a quoted column by its
     * quoted name; the names the text holds, less its functions and its types,
     * are the columns it is over. The statement, not the block, says whether the
     * predicate uses the same expression (findings.adoc#schema).
     */
    static boolean leads(String keyPart, String column) {
        return keyPart.equalsIgnoreCase(column)
                || namesOf(keyPart).contains(column.toLowerCase(Locale.ROOT));
    }

    /**
     * The column names an index expression holds, lower-cased: every name
     * {@link SqlShape.Token} reads in it, less a type after {@code ::} and a
     * function before {@code (}.
     */
    private static List<String> namesOf(String expression) {
        List<SqlShape.Token> tokens = SqlShape.Token.of(expression);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            SqlShape.Token token = tokens.get(i);
            boolean type = i > 0 && tokens.get(i - 1).is("::");
            boolean call = i + 1 < tokens.size() && tokens.get(i + 1).is("(");
            if (token.kind() == SqlShape.Token.Kind.NAME && !type && !call) {
                names.addAll(token.parts());
            }
        }
        return names;
    }
}
