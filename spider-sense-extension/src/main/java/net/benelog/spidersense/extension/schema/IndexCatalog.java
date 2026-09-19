package net.benelog.spidersense.extension.schema;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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

/**
 * Reads the index catalog of the tables a slow statement touched and emits it as one log record per
 * table ({@code docs/design.md}, "The extension"; {@code docs/agent.md}, "The schema block").
 *
 * <p>This class is a helper: the agent injects it into the class loader of the JDBC driver, beside
 * the advice that calls it, because advice never runs in the extension's own loader. That is why it
 * lives in a package of its own, why it references nothing but the JDK, the OpenTelemetry API and
 * {@link VirtualField}, and why every bit of its state is static — there is one copy of it per
 * application class loader and nothing to hand it from outside.
 *
 * <p>It answers the one question a span processor cannot: which columns of the tables a statement
 * filters on carry an index. That needs a {@link Connection}, and the only moment one is in reach
 * is inside the call, on the thread that borrowed it. Borrowing a second connection would be the
 * pool exhaustion the finding is meant to diagnose, and handing this one to another thread is not
 * possible either: the application returns it to the pool as soon as the statement is done.
 *
 * <p>Two kinds of statement are looked up. One has spent {@code spidersense.slow.query.ms} in the
 * database, which is what a {@code slow-query} finding is made of. The other is the fifth execution
 * of the same SQL within the current trace, counted the way {@code SlowQuerySpanProcessor} counts
 * it: the individual queries of an N+1 are never slow, so without that an {@code n-plus-one}
 * finding would be the one finding that never gets its schema block, which is the one place an
 * index is most often the answer.
 *
 * <p>What that costs is bounded on every side: each table is looked up once per process, at most
 * {@value #MAX_TABLES} tables are looked up at all, at most {@value #MAX_STATEMENTS} distinct
 * statements of one trace are counted, a thread already inside a lookup does nothing (a driver's
 * own catalog queries come back through the same advice), and every exception is swallowed. A
 * missing catalog is never worth a slow or a broken statement.
 */
public final class IndexCatalog {

    /** The same threshold and the same reading of it as {@code SlowQuerySpanProcessor}. */
    static final String THRESHOLD_PROPERTY = "spidersense.slow.query.ms";
    static final String THRESHOLD_ENV = "SPIDERSENSE_SLOW_QUERY_MS";
    static final long DEFAULT_THRESHOLD_MS = 100;

    /** What the sampler in the extension's own loader keys on; the two cannot share a constant. */
    static final String LOOKUP_KEY = "spidersense.schema.lookup";

    /** The instrumentation scope the server reads catalog records under. */
    static final String SCOPE_NAME = "spider-sense";

    static final AttributeKey<String> TABLE = AttributeKey.stringKey("spidersense.schema.table");
    static final AttributeKey<String> SCHEMA = AttributeKey.stringKey("spidersense.schema.schema");
    static final AttributeKey<String> PRODUCT = AttributeKey.stringKey("spidersense.schema.product");
    static final AttributeKey<String> INDEXES = AttributeKey.stringKey("spidersense.schema.indexes");

    /** How many tables one process ever looks up; past it the catalog stops growing. */
    static final int MAX_TABLES = 200;

    /** The repeat that gets the catalog: the same number the server calls an N+1. */
    static final int N_PLUS_ONE_REPEATS = 5;

    /** How many distinct statements of one trace are counted before the counter gives up. */
    static final int MAX_STATEMENTS = 256;

    /**
     * Read once, at class initialisation: this sits on the path of every statement the application
     * runs, and the value cannot change while it runs.
     *
     * <p>{@code SlowQuerySpanProcessor} parses the same two settings with the same few lines. They
     * are copied rather than shared because the processor lives in the extension's loader and this
     * class in the application's, and neither can see the other.
     */
    private static final long THRESHOLD_NANOS =
            TimeUnit.MILLISECONDS.toNanos(Math.max(0, configured()));

    /** One entry per table that has been attempted, successfully or not; bounded by MAX_TABLES. */
    private static final Set<String> looked = ConcurrentHashMap.newKeySet();

    /** Set for the length of a lookup, so the driver's own catalog queries fall straight through. */
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    /**
     * The repeats of the trace the thread is in the middle of, and nothing older.
     *
     * <p>A thread runs one trace at a time, so the counter can live on the thread and be re-keyed
     * when a statement of another trace runs on it, which costs no shared map and no lifecycle to
     * manage. A trace whose repeats run on several threads is counted per thread and may fall short
     * of five on each; that is the same known price {@code SlowQuerySpanProcessor} pays, and the
     * same reason.
     */
    private static final ThreadLocal<Repeats> repeats = new ThreadLocal<>();

    /** The trace a thread is counting, and how often each statement of it has run. */
    static final class Repeats {
        String traceId;
        final Map<String, Integer> counts = new HashMap<>();
    }

    /** Null means the agent's own, resolved at emit time; a test puts its own provider here. */
    private static volatile LoggerProvider provider;

    private IndexCatalog() {
    }

    /**
     * What the advice calls, on the thread that ran the statement, right after it returned — on
     * every statement, not only a slow one, because the repeat has to be counted to be found.
     *
     * @param statement the statement that ran, still open and still holding its connection
     * @param sql the SQL of an {@code execute(String)} call, or null for a prepared statement
     * @param elapsedNanos how long the call took
     */
    public static void afterExecute(Statement statement, String sql, long elapsedNanos) {
        afterExecute(statement, sql, elapsedNanos, THRESHOLD_NANOS);
    }

    /** The same, with the threshold given rather than configured, which is what a test wants. */
    static void afterExecute(Statement statement, String sql, long elapsedNanos, long thresholdNanos) {
        try {
            if (Boolean.TRUE.equals(inside.get())) {
                return;
            }
            String text = sql != null ? sql : preparedSql(statement);
            if (text == null) {
                return;
            }
            // Counted first and always: a mix of slow and fast repeats reaches five like any other,
            // and the cost on a statement that is neither is one map lookup.
            boolean fifthRepeat = count(text) == N_PLUS_ONE_REPEATS;
            if (elapsedNanos < thresholdNanos && !fifthRepeat) {
                return;
            }
            List<Word> tables = refsOf(text);
            if (tables.isEmpty()) {
                return;
            }
            Connection connection = statement.getConnection();
            if (connection == null) {
                return;
            }
            inside.set(Boolean.TRUE);
            try (Scope lookup = Baggage.current()
                    .toBuilder()
                    .put(LOOKUP_KEY, "1")
                    .build()
                    .storeInContext(Context.current())
                    .makeCurrent()) {
                lookUpAll(connection, tables);
            } finally {
                inside.remove();
            }
        } catch (Throwable swallowed) {
            // Documented: nothing here may reach the application.
        }
    }

    /**
     * How often this statement has now run in this trace on this thread.
     *
     * <p>Zero when it is not counted at all, which is what no current trace means — a statement
     * outside a request is nobody's N+1 — and what beyond {@value #MAX_STATEMENTS} distinct
     * statements of one trace means: the counter stops adding keys and nothing else changes.
     */
    private static int count(String sql) {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return 0;
        }
        String traceId = context.getTraceId();
        Repeats counting = repeats.get();
        if (counting == null) {
            counting = new Repeats();
            repeats.set(counting);
        }
        if (!traceId.equals(counting.traceId)) {
            counting.traceId = traceId;
            counting.counts.clear();
        }
        Integer seen = counting.counts.get(sql);
        if (seen == null && counting.counts.size() >= MAX_STATEMENTS) {
            return 0;
        }
        int count = seen == null ? 1 : seen + 1;
        counting.counts.put(sql, count);
        return count;
    }

    /**
     * The SQL of a prepared statement, which the call itself does not carry.
     *
     * <p>The agent's JDBC instrumentation puts it in a {@code VirtualField} at prepare time, and
     * that field is the only place it can be read from without asking the driver, which has no
     * portable way of telling. Outside the agent the field is empty, and a statement whose SQL
     * cannot be had is left alone.
     */
    private static String preparedSql(Statement statement) {
        if (!(statement instanceof PreparedStatement)) {
            return null;
        }
        return VirtualField.find(PreparedStatement.class, String.class).get((PreparedStatement) statement);
    }

    /** One attempt per table per process, recorded before the attempt so a failure is not retried. */
    private static void lookUpAll(Connection connection, List<Word> tables) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String url = meta.getURL();
        String catalog = catalogOf(connection);
        for (Word table : tables) {
            if (looked.size() >= MAX_TABLES) {
                return;
            }
            if (!looked.add(url + "\0" + table.text)) {
                continue;
            }
            lookUp(meta, catalog, table);
        }
    }

    /**
     * Asks the database whether the table is there and, when it is, what indexes it has.
     *
     * <p>{@code getTables} first, so a name the scanner misread costs one catalog query and emits
     * nothing: the alternative is a catalog row for a table that does not exist, which the server
     * would carry forever. A name found in several schemas is emitted once per schema, because the
     * statement does not say which one it meant and both answers are facts.
     */
    private static void lookUp(DatabaseMetaData meta, String catalog, Word table) throws SQLException {
        String schema = null;
        String name = table.text;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            schema = fold(meta, name.substring(0, dot), table.quoted);
            name = name.substring(dot + 1);
        }
        name = fold(meta, name, table.quoted);
        List<Word> found = new ArrayList<>();
        try (ResultSet rows = meta.getTables(catalog, schema, pattern(meta, name), null)) {
            while (rows.next()) {
                // Word is the pair this needs twice over: here the schema and the table as the
                // database spells them, in the scanner a name and whether it was quoted.
                found.add(new Word(rows.getString("TABLE_NAME"), false, rows.getString("TABLE_SCHEM")));
            }
        }
        for (Word row : found) {
            emit(meta, catalog, row.schema, row.text);
        }
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

    private static String catalogOf(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException unavailable) {
            return null;
        }
    }

    /** One record per table, under the scope the server reads catalog rows from. */
    private static void emit(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        String indexes = json(indexesOf(meta, catalog, schema, table));
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
     */
    private static List<Index> indexesOf(
            DatabaseMetaData meta, String catalog, String schema, String table) throws SQLException {
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
                if (column != null) {
                    index.columns.put((int) rows.getShort("ORDINAL_POSITION"), column);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** One index of one table, its columns kept by ordinal so the seek column comes first. */
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
    static String json(List<Index> indexes) {
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
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
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
    static void loggerProvider(LoggerProvider loggerProvider) {
        provider = loggerProvider;
    }

    /** For tests: forget which tables have been looked up and what this thread was counting. */
    static void forget() {
        looked.clear();
        repeats.remove();
    }

    private static long configured() {
        try {
            String value = System.getProperty(THRESHOLD_PROPERTY);
            if (value == null || value.isBlank()) {
                value = System.getenv(THRESHOLD_ENV);
            }
            return value == null || value.isBlank()
                    ? DEFAULT_THRESHOLD_MS
                    : Long.parseLong(value.trim());
        } catch (RuntimeException malformed) {
            return DEFAULT_THRESHOLD_MS;
        }
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
        List<Word> refs = refsOf(sql);
        List<String> names = new ArrayList<>(refs.size());
        for (Word ref : refs) {
            names.add(ref.text);
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
    static List<Word> refsOf(String sql) {
        List<Word> found = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return found;
        }
        List<Word> words = words(sql);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (word.quoted) {
                continue;
            }
            String keyword = word.text.toLowerCase(Locale.ROOT);
            boolean list = keyword.equals("from");
            if (!list && !keyword.equals("join") && !keyword.equals("into") && !keyword.equals("update")) {
                continue;
            }
            int at = i + 1;
            while (at < words.size()) {
                Word first = words.get(at);
                if (!isName(first) || isKeyword(first)) {
                    // A subquery, a parameter or a keyword: nothing to name here.
                    break;
                }
                StringBuilder text = new StringBuilder(first.text);
                boolean quoted = first.quoted;
                at++;
                while (at + 1 < words.size()
                        && ".".equals(words.get(at).text)
                        && isName(words.get(at + 1))) {
                    text.append('.').append(words.get(at + 1).text);
                    quoted |= words.get(at + 1).quoted;
                    at += 2;
                }
                String name = text.toString();
                if (seen.add(name)) {
                    found.add(new Word(name, quoted));
                }
                at = afterAlias(words, at);
                if (!list || at >= words.size() || !",".equals(words.get(at).text)) {
                    break;
                }
                at++;
            }
            i = at - 1;
        }
        return found;
    }

    /** Past the table's alias, whether it was written with {@code as} or without one at all. */
    private static int afterAlias(List<Word> words, int at) {
        if (at >= words.size()) {
            return at;
        }
        Word next = words.get(at);
        if (!next.quoted && "as".equalsIgnoreCase(next.text)) {
            at++;
            return at < words.size() && isName(words.get(at)) ? at + 1 : at;
        }
        return isName(next) && !isKeyword(next) ? at + 1 : at;
    }

    private static boolean isName(Word word) {
        if (word.quoted) {
            return true;
        }
        char c = word.text.charAt(0);
        return Character.isLetter(c) || c == '_' || c == '$' || c == '#';
    }

    private static boolean isKeyword(Word word) {
        return !word.quoted && KEYWORDS.contains(word.text.toLowerCase(Locale.ROOT));
    }

    /**
     * The statement as words: identifiers, keywords and single characters, with comments and the
     * contents of string literals thrown away.
     *
     * <p>A literal becomes the one character {@code '} rather than its text, so that nothing inside
     * it is ever read as a keyword or a name. Every other character that is not part of an
     * identifier is a word of its own, which is all the scanner above needs of punctuation.
     */
    private static List<Word> words(String sql) {
        List<Word> words = new ArrayList<>();
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
                words.add(new Word("'", false));
            } else if (c == '"' || c == '`' || c == '[') {
                StringBuilder text = new StringBuilder();
                i = readQuoted(sql, i, text);
                words.add(new Word(text.toString(), true));
            } else if (isNameStart(c)) {
                int start = i;
                while (i < length && isNamePart(sql.charAt(i))) {
                    i++;
                }
                words.add(new Word(sql.substring(start, i), false));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                words.add(new Word(sql.substring(start, i), false));
            } else {
                words.add(new Word(String.valueOf(c), false));
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

    /** A word of a statement, or a table the catalog answered with: text, and how to read it. */
    static final class Word {
        final String text;
        final boolean quoted;
        final String schema;

        Word(String text, boolean quoted) {
            this(text, quoted, null);
        }

        Word(String text, boolean quoted, String schema) {
            this.text = text;
            this.quoted = quoted;
            this.schema = schema;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
