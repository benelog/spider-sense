package net.benelog.spidersense.extension.schema;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Reads the index catalog of the tables the application's statements touch and emits it as one log
 * record per table ({@code design.adoc#extension}; {@code findings.adoc#schema}).
 *
 * <p>This class is a helper: the agent injects it into the class loader of the JDBC driver, beside
 * the advice that calls it, because advice never runs in the extension's own loader. That is why it
 * lives in a package of its own, why it references nothing but the JDK, the OpenTelemetry API,
 * {@link VirtualField} and the helpers beside it ({@link Thresholds}, {@link Settings}), and why
 * every bit of its state is static — there is one copy of it per
 * application class loader and nothing to hand it from outside.
 *
 * <p>It answers the one question a span processor cannot: which columns of the tables a statement
 * filters on carry an index. That needs a {@link Connection}, and the only moment one is in reach
 * is inside the call, on the thread that borrowed it. Borrowing a second connection would be the
 * pool exhaustion the finding is meant to diagnose, and handing this one to another thread is not
 * possible either: the application returns it to the pool as soon as the statement is done.
 *
 * <p>A table is looked up on the first statement of the process that names it, however fast,
 * and again on the first one after DDL that names it ({@code create index … on t},
 * {@code alter table t}), because a catalog read only when a statement was slow would outlive the
 * index that made it fast: the developer adds the index the block asked for, restarts, and the
 * statement never becomes slow enough to correct the row (findings.adoc#schema).
 *
 * <p>What that costs is bounded on every side: a statement already handled since the last DDL
 * costs one map lookup by its text, each table is looked up once per process and DDL, at most
 * {@value #MAX_TABLES} tables are looked up at all, past {@value #MAX_STATEMENTS} distinct
 * statements a new one is read only when it was slow or is DDL, a thread already inside a lookup does
 * nothing (a driver's own catalog queries come back through the same advice), and every exception
 * is swallowed. A missing catalog is never worth a slow or a broken statement.
 */
public final class IndexCatalog {

    /**
     * The baggage entry that marks a lookup, which {@code SchemaLookupSampler} keys on.
     *
     * <p>A compile-time constant, so the sampler in the extension's own loader names it without a
     * runtime reference to this class: javac copies the value.
     */
    public static final String LOOKUP_KEY = "spidersense.schema.lookup";

    /** The instrumentation scope the server reads catalog records under. */
    static final String SCOPE_NAME = "spider-sense";

    static final AttributeKey<String> TABLE = AttributeKey.stringKey("spidersense.schema.table");
    static final AttributeKey<String> SCHEMA = AttributeKey.stringKey("spidersense.schema.schema");
    static final AttributeKey<String> PRODUCT = AttributeKey.stringKey("spidersense.schema.product");
    static final AttributeKey<String> INDEXES = AttributeKey.stringKey("spidersense.schema.indexes");

    /** How many tables one process ever looks up; past it the catalog stops growing. */
    static final int MAX_TABLES = 200;

    /** How many statement texts are remembered as handled; past it only a slow one is read. */
    static final int MAX_STATEMENTS = 2000;

    /**
     * Read once, at class initialisation: this sits on the path of every statement the application
     * runs, and the value cannot change while it runs.
     *
     * <p>{@code CallSiteSpanProcessor} reads the same {@link Thresholds}, which is injected beside
     * this class, so the stack and the catalog of one statement are captured at one threshold.
     */
    private static final long THRESHOLD_NANOS =
            TimeUnit.MILLISECONDS.toNanos(Math.max(0, Thresholds.slowQueryMillis()));

    /** One entry per table that has been attempted, successfully or not; bounded by MAX_TABLES. */
    private static final Set<String> attemptedTables = ConcurrentHashMap.newKeySet();

    /**
     * The statements already read, by their text, each with the {@link #ddl} count it was read
     * under: a statement read since the last DDL names no table that has not been attempted.
     */
    private static final Map<String, Integer> handled = new ConcurrentHashMap<>();

    /** How many DDL statements have made a table's catalog worth reading again. */
    private static final AtomicInteger ddl = new AtomicInteger();

    /** Set for the length of a lookup, so the driver's own catalog queries fall straight through. */
    private static final ThreadLocal<Boolean> insideLookup = new ThreadLocal<>();

    /** Null means the agent's own, resolved at emit time; a test puts its own provider here. */
    private static volatile @Nullable LoggerProvider provider;

    private IndexCatalog() {
    }

    /**
     * What the advice calls, on the thread that ran the statement, right after it returned.
     *
     * @param statement the statement that ran, still open and still holding its connection
     * @param sql the SQL of an {@code execute(String)} call, or null for a prepared statement
     * @param elapsedNanos how long the call took
     */
    public static void afterExecute(Statement statement, @Nullable String sql, long elapsedNanos) {
        afterExecute(statement, sql, elapsedNanos, THRESHOLD_NANOS);
    }

    /**
     * The same, with the threshold given rather than configured, which is what a test wants.
     *
     * <p>The threshold only matters once {@value #MAX_STATEMENTS} statement texts are remembered:
     * past that, a statement not among them is read only when it was slow, as every statement was
     * before a fast one could correct the catalog. DDL is read at any speed, because the index a
     * fast {@code create index} made would otherwise never be read in this run.
     */
    static void afterExecute(
            Statement statement, @Nullable String sql, long elapsedNanos, long thresholdNanos) {
        try {
            if (Boolean.TRUE.equals(insideLookup.get())) {
                return;
            }
            String text = sql != null ? sql : preparedSql(statement);
            if (text == null) {
                return;
            }
            int generation = ddl.get();
            Integer seen = handled.get(text);
            if (seen != null && seen == generation) {
                return;
            }
            boolean remembered = seen != null || handled.size() < MAX_STATEMENTS;
            boolean read = remembered || elapsedNanos >= thresholdNanos;
            // DDL is asked about before the cap, at any speed: a fast create index past the cap
            // would otherwise leave its table's old catalog in place for the rest of the run.
            if (!read && !mayBeDdl(text)) {
                return;
            }
            List<String> changed = ddlTargets(text);
            if (changed != null) {
                // Not remembered: every run of a DDL statement changes what the catalog holds.
                forgetTables(changed);
                return;
            }
            if (!read) {
                return;
            }
            List<TableRef> tables = tableRefsOf(text);
            if (remembered) {
                handled.put(text, generation);
            }
            if (tables.isEmpty()) {
                return;
            }
            Connection connection = statement.getConnection();
            if (connection == null) {
                return;
            }
            insideLookup.set(Boolean.TRUE);
            try (Scope lookup = Baggage.current()
                    .toBuilder()
                    .put(LOOKUP_KEY, "1")
                    .build()
                    .storeInContext(Context.current())
                    .makeCurrent()) {
                lookUpAll(connection, tables);
            } finally {
                insideLookup.remove();
            }
        } catch (Throwable swallowed) {
            // Documented: nothing here may reach the application.
        }
    }

    /**
     * The SQL of a prepared statement, which the call itself does not carry.
     *
     * <p>The agent's JDBC instrumentation puts it in a {@code VirtualField} at prepare time, and
     * that field is the only place it can be read from without asking the driver, which has no
     * portable way of telling. Outside the agent the field is empty, and a statement whose SQL
     * cannot be had is left alone.
     */
    private static @Nullable String preparedSql(Statement statement) {
        if (!(statement instanceof PreparedStatement prepared)) {
            return null;
        }
        return VirtualField.find(PreparedStatement.class, String.class).get(prepared);
    }

    /**
     * One attempt per table per process and DDL, recorded before the attempt so a failure is not
     * retried.
     */
    private static void lookUpAll(Connection connection, List<TableRef> tables) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String url = meta.getURL();
        String catalog = catalogOf(connection);
        String current = schemaOf(connection);
        for (TableRef table : tables) {
            if (attemptedTables.size() >= MAX_TABLES) {
                return;
            }
            if (!attemptedTables.add(url + "\0" + table.name())) {
                continue;
            }
            lookUp(meta, catalog, current, table);
        }
    }

    /**
     * Asks the database whether the table is there and, when it is, what indexes it has.
     *
     * <p>{@code getTables} first, so a name the scanner misread costs one catalog query and emits
     * nothing: the alternative is a catalog row for a table that does not exist, which the server
     * would carry forever.
     *
     * <p>A name the statement does not qualify is the connection's current schema's table when that
     * schema has one, because that is the table the database resolved it to: H2 also answers
     * {@code INFORMATION_SCHEMA.USERS} for a {@code users}, and PostgreSQL an {@code audit.orders}
     * beside {@code public.orders}. Only when the current schema has no such table (a later schema
     * of PostgreSQL's {@code search_path}, or a driver that reports no current schema) is every
     * schema asked, less the database's own catalog schemas, and a name found in several of them
     * is emitted once per schema, because the statement does not say which one it meant and both
     * answers are facts; the server then gives no block (findings.adoc#schema).
     */
    private static void lookUp(DatabaseMetaData meta, @Nullable String catalog,
            @Nullable String current, TableRef table) throws SQLException {
        String name = table.name();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String schema = fold(meta, name.substring(0, dot), table.quoted());
            emitAll(meta, catalog, tablesOf(meta, catalog, schema,
                    fold(meta, name.substring(dot + 1), table.quoted())));
            return;
        }
        name = fold(meta, name, table.quoted());
        List<CatalogTable> found = current == null ? List.of() : tablesOf(meta, catalog, current, name);
        if (found.isEmpty()) {
            found = new ArrayList<>();
            for (CatalogTable row : tablesOf(meta, catalog, null, name)) {
                if (!isSystemSchema(row.schema())) {
                    found.add(row);
                }
            }
        }
        emitAll(meta, catalog, found);
    }

    /** The tables of that name, in that schema or, given none, in every schema. */
    private static List<CatalogTable> tablesOf(DatabaseMetaData meta, @Nullable String catalog,
            @Nullable String schema, String name) throws SQLException {
        List<CatalogTable> found = new ArrayList<>();
        try (ResultSet rows = meta.getTables(catalog,
                schema == null ? null : pattern(meta, schema), pattern(meta, name), null)) {
            while (rows.next()) {
                if (!hasOwnIndexes(rows.getString("TABLE_TYPE"))) {
                    continue;
                }
                found.add(new CatalogTable(rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME")));
            }
        }
        return found;
    }

    private static void emitAll(DatabaseMetaData meta, @Nullable String catalog,
            List<CatalogTable> found) throws SQLException {
        for (CatalogTable row : found) {
            emit(meta, catalog, row.schema(), row.name());
        }
    }

    /**
     * Whether the schema is the database's description of itself ({@code INFORMATION_SCHEMA} on
     * H2, {@code information_schema} and {@code pg_catalog} on PostgreSQL), whose tables a
     * statement that does not name the schema never means.
     */
    static boolean isSystemSchema(@Nullable String schema) {
        return schema != null && (schema.equalsIgnoreCase("INFORMATION_SCHEMA")
                || schema.equalsIgnoreCase("pg_catalog"));
    }

    /**
     * Whether a row of {@code getTables} is something that has indexes of its own: a table
     * ({@code TABLE} on most drivers, {@code BASE TABLE} on H2 2, {@code PARTITIONED TABLE} on
     * PostgreSQL) or a materialized view. A view has none, and reporting it as a table without
     * indexes would name every column its statement filters on as unindexed, when the indexes that
     * serve them are its base table's; like a misread name, it emits nothing.
     */
    private static boolean hasOwnIndexes(@Nullable String type) {
        if (type == null) {
            return true;
        }
        String upper = type.toUpperCase(Locale.ROOT);
        return upper.contains("TABLE") || upper.equals("MATERIALIZED VIEW");
    }

    /**
     * The identifier as the database stores it: an unquoted name is folded the way the database
     * says it folds them, a quoted one is kept, because that is what the database did with it when
     * the table was created.
     */
    private static String fold(DatabaseMetaData meta, String name, boolean quoted) throws SQLException {
        if (quoted) {
            return name;
        }
        if (meta.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        if (meta.storesLowerCaseIdentifiers()) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    /**
     * {@code getTables} takes a pattern, not a name, so an {@code order_line} would also match an
     * {@code orderXline}; the wildcards in the name are escaped the way the driver asks for.
     */
    private static String pattern(DatabaseMetaData meta, String name) {
        String escape;
        try {
            escape = meta.getSearchStringEscape();
        } catch (SQLException unavailable) {
            return name;
        }
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '%') {
                out.append(escape);
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The schema an unqualified name resolves in, or null when the driver does not say: MySQL has
     * no schemas, and a driver older than JDBC 4.1 has no such method at all.
     */
    private static @Nullable String schemaOf(Connection connection) {
        try {
            String schema = connection.getSchema();
            return schema == null || schema.isEmpty() ? null : schema;
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException unavailable) {
            return null;
        }
    }

    private static @Nullable String catalogOf(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException unavailable) {
            return null;
        }
    }

    /** One record per table, under the scope the server reads catalog rows from. */
    private static void emit(
            DatabaseMetaData meta, @Nullable String catalog, @Nullable String schema, String table)
            throws SQLException {
        String indexes = indexesJson(indexesOf(meta, catalog, schema, table));
        String product = meta.getDatabaseProductName();
        LogRecordBuilder record = loggerProvider()
                .get(SCOPE_NAME)
                .logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setBody("index catalog of " + table)
                .setAttribute(TABLE, table)
                .setAttribute(INDEXES, indexes);
        if (schema != null && !schema.isEmpty()) {
            record = record.setAttribute(SCHEMA, schema);
        }
        if (product != null && !product.isEmpty()) {
            record = record.setAttribute(PRODUCT, product);
        }
        record.emit();
    }

    /**
     * The indexes of one table, grouped by name, columns in ordinal order.
     *
     * <p>Approximate rows are asked for ({@code approximate = true}), because the alternative is
     * letting the driver update the table's statistics on a request thread. The statistic rows the
     * call also returns carry no index and are dropped.
     *
     * <p>A key part with no column, such as MySQL's functional one ({@code ((lower(email)), status)}),
     * keeps its place as an empty name: dropped, it would make the column after it the index's first,
     * the one an index seeks on, which it is not.
     */
    static List<Index> indexesOf(DatabaseMetaData meta, @Nullable String catalog,
            @Nullable String schema, String table) throws SQLException {
        Map<String, Index> byName = new LinkedHashMap<>();
        try (ResultSet rows = meta.getIndexInfo(catalog, schema, table, false, true)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String name = rows.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                String key = rows.getString("TABLE_SCHEM") + "\0" + name;
                Index index = byName.get(key);
                if (index == null) {
                    index = new Index(name, !rows.getBoolean("NON_UNIQUE"));
                    byName.put(key, index);
                }
                String column = rows.getString("COLUMN_NAME");
                index.columns.put((int) rows.getShort("ORDINAL_POSITION"), column == null ? "" : column);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * One index of one table, its columns kept by ordinal so the seek column comes first; a key part
     * that names no column is an empty name in its place.
     */
    static final class Index {
        final String name;
        final boolean unique;
        final TreeMap<Integer, String> columns = new TreeMap<>();

        Index(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    /**
     * The indexes as the JSON array the attribute carries, written by hand: the helper runs in the
     * application's class loader, where the only library it may assume is the JDK.
     */
    static String indexesJson(List<Index> indexes) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < indexes.size(); i++) {
            Index index = indexes.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":");
            quote(out, index.name);
            out.append(",\"unique\":").append(index.unique).append(",\"columns\":[");
            boolean first = true;
            for (String column : index.columns.values()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, column);
            }
            out.append("]}");
        }
        return out.append(']').toString();
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static LoggerProvider loggerProvider() {
        LoggerProvider configured = provider;
        // Resolved at emit time and never cached: the agent installs the global some way into
        // start-up, and the first slow statement may well come before that.
        return configured != null ? configured : GlobalOpenTelemetry.get().getLogsBridge();
    }

    /** For tests: where the records go. Null puts the agent's own provider back. */
    static void loggerProvider(@Nullable LoggerProvider loggerProvider) {
        provider = loggerProvider;
    }

    /**
     * After DDL, the tables it names are looked up again on their next statement; a DDL statement
     * that names none ({@code drop index idx}, on PostgreSQL and H2) may have changed any of them.
     * A name is matched without its schema and its case, since the DDL and the statements need not
     * spell it alike, and looking a table up once too often costs one catalog query.
     */
    private static void forgetTables(List<String> names) {
        if (names.isEmpty()) {
            attemptedTables.clear();
        } else {
            attemptedTables.removeIf(key -> names.contains(lastPart(key.substring(key.indexOf('\0') + 1))));
        }
        ddl.incrementAndGet();
    }

    private static String lastPart(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** For tests: forget which tables and statements have been looked up. */
    static void forget() {
        attemptedTables.clear();
        handled.clear();
    }

    // ---------------------------------------------------------------- the scanner

    /**
     * The words the table names follow, and the words that end a name.
     *
     * <p>The set is what tells a table from an alias: the token after a table name is the alias
     * unless it is one of these. It is deliberately broad — a keyword mistaken for an alias loses a
     * table, an alias mistaken for a table costs one catalog query that finds nothing.
     */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "all", "alter", "and", "any", "apply", "as", "asc", "between", "by", "case", "connect",
            "create", "cross", "default", "delete", "desc", "distinct", "drop", "else", "end",
            "escape", "except", "exists", "fetch", "first", "for", "force", "from", "full", "group",
            "having", "ignore", "in", "index", "inner", "insert", "intersect", "into", "is", "join",
            "key", "last", "lateral", "left", "like", "limit", "merge", "natural", "next", "not",
            "null", "offset", "on", "only", "option", "or", "order", "outer", "over", "partition",
            "pivot", "primary", "qualify", "returning", "right", "row", "rows", "sample", "select",
            "set", "some", "start", "straight_join", "table", "tablesample", "then", "top",
            "truncate", "union", "unpivot", "update", "use", "using", "values", "when", "where",
            "window", "with"));

    /** The table names of a statement, distinct and in the order they appear. */
    static List<String> tablesOf(String sql) {
        List<TableRef> refs = tableRefsOf(sql);
        List<String> names = new ArrayList<>(refs.size());
        for (TableRef ref : refs) {
            names.add(ref.name());
        }
        return names;
    }

    /**
     * The same, each name with whether it was written quoted, which decides whether it may be
     * case-folded before it is looked up.
     *
     * <p>The scan is a hand-written walk over the statement's words and nothing more: the names
     * after {@code from}, {@code join}, {@code update} and {@code into}, a comma-separated list
     * after {@code from}, an alias skipped, a {@code schema.table} kept as written. It is not a SQL
     * parser and is not meant to be one — what it cannot read it does not name, and a name it reads
     * wrongly is dropped by the {@code getTables} lookup that follows.
     */
    static List<TableRef> tableRefsOf(String sql) {
        List<TableRef> found = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return found;
        }
        List<Token> words = tokens(sql);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < words.size(); i++) {
            Token word = words.get(i);
            if (word.quoted()) {
                continue;
            }
            String keyword = word.text().toLowerCase(Locale.ROOT);
            boolean list = keyword.equals("from");
            if (!list && !keyword.equals("join") && !keyword.equals("into") && !keyword.equals("update")) {
                continue;
            }
            int at = i + 1;
            while (at < words.size()) {
                Token first = words.get(at);
                if (!isName(first) || isKeyword(first)) {
                    // A subquery, a parameter or a keyword: nothing to name here.
                    break;
                }
                StringBuilder text = new StringBuilder(first.text());
                boolean quoted = first.quoted();
                at++;
                while (at + 1 < words.size()
                        && ".".equals(words.get(at).text())
                        && isName(words.get(at + 1))) {
                    text.append('.').append(words.get(at + 1).text());
                    quoted |= words.get(at + 1).quoted();
                    at += 2;
                }
                String name = text.toString();
                if (seen.add(name)) {
                    found.add(new TableRef(name, quoted));
                }
                at = afterAlias(words, at);
                if (!list || at >= words.size() || !",".equals(words.get(at).text())) {
                    break;
                }
                at++;
            }
            i = at - 1;
        }
        return found;
    }

    /**
     * Whether the statement may be DDL, by its first word alone, so a statement past the cap that
     * is not DDL costs no tokenising; one that starts with a comment is handed to
     * {@link #ddlTargets(String)}, which reads past it.
     */
    static boolean mayBeDdl(String sql) {
        int at = 0;
        while (at < sql.length() && Character.isWhitespace(sql.charAt(at))) {
            at++;
        }
        return sql.startsWith("/*", at) || sql.startsWith("--", at)
                || sql.regionMatches(true, at, "create", 0, 6)
                || sql.regionMatches(true, at, "alter", 0, 5)
                || sql.regionMatches(true, at, "drop", 0, 4);
    }

    /**
     * The tables whose indexes a DDL statement may change, each lower-cased without its schema;
     * empty when it may change any table's, and null when the statement is not such DDL.
     *
     * <p>Read: {@code create [unique] index … on t}, {@code create table t}, {@code alter table t},
     * {@code drop table t, u}, {@code drop index idx on t} (MySQL), and {@code drop index idx} or
     * {@code alter index}, which name no table. A temporary table is no catalog a statement is
     * judged against, so its DDL is not read: an application creating one per request would
     * otherwise read every statement again on every request.
     */
    static @Nullable List<String> ddlTargets(String sql) {
        List<Token> words = tokens(sql);
        if (words.isEmpty() || words.get(0).quoted()) {
            return null;
        }
        String verb = words.get(0).text().toLowerCase(Locale.ROOT);
        if (!verb.equals("create") && !verb.equals("alter") && !verb.equals("drop")) {
            return null;
        }
        int at = 1;
        while (at < words.size() && !words.get(at).quoted() && DDL_MODIFIERS.contains(lower(words.get(at)))) {
            at++;
        }
        if (at >= words.size() || words.get(at).quoted()) {
            return null;
        }
        String object = lower(words.get(at));
        if (object.equals("index")) {
            if (verb.equals("alter")) {
                return List.of();
            }
            for (int i = at + 1; i < words.size(); i++) {
                if (!words.get(i).quoted() && lower(words.get(i)).equals("on")) {
                    return namesFrom(words, i + 1, false);
                }
            }
            return List.of();
        }
        if (object.equals("table")) {
            return namesFrom(words, at + 1, verb.equals("drop"));
        }
        return null;
    }

    /**
     * The words DDL may carry before the object it creates, alters or drops. A temporary table's
     * words are not among them, so its DDL reads as no DDL at all.
     */
    private static final Set<String> DDL_MODIFIERS = new HashSet<>(Arrays.asList(
            "or", "replace", "unique", "fulltext", "spatial", "bitmap", "clustered", "nonclustered",
            "cached", "memory"));

    /** The words between {@code table}, {@code on} or {@code index} and the name. */
    private static final Set<String> DDL_SKIPPED = new HashSet<>(Arrays.asList(
            "if", "not", "exists", "only", "concurrently"));

    /** The table names from there on, the last part of each lower-cased; empty when there is none. */
    private static List<String> namesFrom(List<Token> words, int at, boolean list) {
        while (at < words.size() && !words.get(at).quoted() && DDL_SKIPPED.contains(lower(words.get(at)))) {
            at++;
        }
        List<String> names = new ArrayList<>();
        while (at < words.size() && isName(words.get(at))) {
            String last = words.get(at).text();
            at++;
            while (at + 1 < words.size() && ".".equals(words.get(at).text()) && isName(words.get(at + 1))) {
                last = words.get(at + 1).text();
                at += 2;
            }
            names.add(last.toLowerCase(Locale.ROOT));
            if (!list || at >= words.size() || !",".equals(words.get(at).text())) {
                break;
            }
            at++;
        }
        return names;
    }

    private static String lower(Token token) {
        return token.text().toLowerCase(Locale.ROOT);
    }

    /** Past the table's alias, whether it was written with {@code as} or without one at all. */
    private static int afterAlias(List<Token> words, int at) {
        if (at >= words.size()) {
            return at;
        }
        Token next = words.get(at);
        if (!next.quoted() && "as".equalsIgnoreCase(next.text())) {
            at++;
            return at < words.size() && isName(words.get(at)) ? at + 1 : at;
        }
        return isName(next) && !isKeyword(next) ? at + 1 : at;
    }

    private static boolean isName(Token token) {
        return token.quoted() || isNameStart(token.text().charAt(0));
    }

    private static boolean isKeyword(Token token) {
        return !token.quoted() && KEYWORDS.contains(token.text().toLowerCase(Locale.ROOT));
    }

    /**
     * The statement as words: identifiers, keywords and single characters, with comments and the
     * contents of string literals thrown away.
     *
     * <p>A literal becomes the one character {@code '} rather than its text, so that nothing inside
     * it is ever read as a keyword or a name. Every other character that is not part of an
     * identifier is a word of its own, which is all the scanner above needs of punctuation.
     */
    private static List<Token> tokens(String sql) {
        List<Token> words = new ArrayList<>();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(length, i + 2);
            } else if (c == '\'') {
                i = afterLiteral(sql, i);
                words.add(new Token("'", false));
            } else if (c == '"' || c == '`' || c == '[') {
                StringBuilder text = new StringBuilder();
                i = readQuoted(sql, i, text);
                words.add(new Token(text.toString(), true));
            } else if (isNameStart(c)) {
                int start = i;
                while (i < length && isNamePart(sql.charAt(i))) {
                    i++;
                }
                words.add(new Token(sql.substring(start, i), false));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                words.add(new Token(sql.substring(start, i), false));
            } else {
                words.add(new Token(String.valueOf(c), false));
                i++;
            }
        }
        return words;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '#';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static int afterLiteral(String sql, int i) {
        int length = sql.length();
        i++;
        while (i < length) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    private static int readQuoted(String sql, int i, StringBuilder text) {
        int length = sql.length();
        char open = sql.charAt(i);
        char close = open == '[' ? ']' : open;
        i++;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == close) {
                if (close != ']' && i + 1 < length && sql.charAt(i + 1) == close) {
                    text.append(close);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            text.append(c);
            i++;
        }
        return length;
    }

    /**
     * A word of a statement as the scanner reads it: an identifier, a keyword or one character, and
     * whether it was written quoted, which makes it a name and never a keyword.
     */
    record Token(String text, boolean quoted) {
    }

    /**
     * A table a statement names, as written ({@code schema.table} kept whole), and whether any part
     * of it was quoted, which decides whether it may be case-folded before it is looked up.
     */
    record TableRef(String name, boolean quoted) {
    }

    /** A table the catalog answered with, its schema and its name as the database spells them. */
    record CatalogTable(@Nullable String schema, String name) {
    }
}
