package net.benelog.spidersense.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What a statement selects from and filters on: the tables, and the columns a
 * {@code where}, an {@code on} or an {@code order by} names.
 *
 * <p>It is a light parse, not a parser. The statement it reads is the sanitised
 * one the instrumentation recorded — every literal is already a {@code ?} — and
 * the question it answers is narrow enough that a scan over tokens beats a
 * grammar: which column of which table would an index have to lead with
 * (findings.adoc#schema).
 *
 * <p>The scan is flat where names are concerned: a subquery is not a scope of
 * its own, so the tables and the predicates of
 * {@code where x in (select id from y where y.a = ?)} are those of both. Only
 * the clause a token sits in is kept per parenthesis, so the {@code where} that
 * a subquery interrupts goes on after its {@code )}. The one thing it refuses
 * to guess is attribution, and
 * {@link #readable()} says so: an unqualified column in a join, a column
 * qualified by the alias of a derived table, or a statement with no table at all
 * makes the whole shape unusable rather than half right. A block that is wrong
 * is worse than no block.
 */
final class SqlShape {

    /** A table the statement names, with the schema it was qualified by. */
    record TableRef(String name, @Nullable String schema) {
    }

    /** A column a predicate refers to, attributed to the table it belongs to. */
    record ColumnRef(String table, String column) {
    }

    /**
     * The words a table name, an alias or a column can never be.
     *
     * <p>It is the union of what opens or closes a clause and what an expression
     * is built from; a word that is not in it and is not a function call is a
     * name. Erring towards "keyword" costs a predicate, erring the other way
     * invents one.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "on", "join", "inner", "left", "right", "outer", "cross",
            "full", "natural", "group", "order", "by", "limit", "offset", "fetch", "having",
            "union", "set", "values", "insert", "update", "delete", "into", "using", "returning",
            "for", "as", "and", "or", "not", "is", "null", "in", "like", "ilike", "escape",
            "between", "exists", "case", "when", "then", "else", "end", "true", "false", "asc",
            "desc", "nulls", "first", "last", "distinct", "all", "any", "some", "interval",
            "current_date", "current_timestamp", "current_time", "date", "time", "timestamp",
            "cast", "only", "rows", "row", "next", "with", "recursive");

    /** Where a table list ends: the next clause, or a parenthesis. */
    private static final Set<String> TABLE_LIST_STOP = Set.of(
            "where", "on", "join", "inner", "left", "right", "outer", "cross", "full", "natural",
            "group", "order", "limit", "offset", "fetch", "having", "union", "set", "values",
            "select", "using", "returning", "for");

    /** The keywords that open a table list, and what follows each. */
    private static final Set<String> TABLE_INTRO = Set.of("from", "join", "update", "into");

    /**
     * What closes a predicate or an order region at the depth it is read at; a
     * {@code )} restores the region its {@code (} interrupted.
     */
    private static final Set<String> REGION_END = Set.of(
            "select", "from", "group", "having", "limit", "offset", "fetch", "union", "set",
            "values", "insert", "update", "delete", "join");

    private enum Region { NONE, PREDICATE, ORDER }

    private final List<TableRef> tables = new ArrayList<>();
    private final List<ColumnRef> predicates = new ArrayList<>();
    private final Set<String> predicateKeys = new LinkedHashSet<>();

    /** alias or table name → the table it resolves to, null for a derived table. */
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private boolean readable = true;

    private SqlShape() {
    }

    static SqlShape of(String statement) {
        SqlShape shape = new SqlShape();
        if (statement != null && !statement.isBlank()) {
            shape.scan(Token.of(statement));
        }
        if (shape.tables.isEmpty()) {
            shape.readable = false;
        }
        return shape;
    }

    List<TableRef> tables() {
        return List.copyOf(tables);
    }

    List<ColumnRef> predicates() {
        return List.copyOf(predicates);
    }

    /** Whether the parse can vouch for what it found (findings.adoc#schema). */
    boolean readable() {
        return readable;
    }

    // --- the scan ------------------------------------------------------------

    private void scan(List<Token> tokens) {
        Region region = Region.NONE;
        // The region each open parenthesis interrupted: a subquery's "select"
        // ends the region inside it, never the one around it.
        Deque<Region> outer = new ArrayDeque<>();
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                outer.push(region);
                i++;
                continue;
            }
            if (token.is(")")) {
                if (!outer.isEmpty()) {
                    region = outer.pop();
                }
                i++;
                continue;
            }
            String word = token.keyword();
            if (word != null && TABLE_INTRO.contains(word)) {
                // "insert into" and "delete from" name a table; so does a join.
                i = tableList(tokens, i + 1, word.equals("from") || word.equals("update"));
                if (REGION_END.contains(word)) {
                    region = Region.NONE;
                }
                continue;
            }
            if (word != null) {
                Region opened = opens(tokens, i, word);
                if (opened != null) {
                    region = opened;
                    i += opened == Region.ORDER ? 2 : 1;
                    continue;
                }
                if (REGION_END.contains(word)) {
                    region = Region.NONE;
                }
                i++;
                continue;
            }
            if (region == Region.NONE || token.kind() != Token.Kind.NAME) {
                i++;
                continue;
            }
            if (followedByParenthesis(tokens, i)) {
                // A function: in a predicate its arguments are still columns
                // ("where lower(name) like ?"), in an order list the expression as a
                // whole is what is sorted by and no index seeks on its arguments.
                i = region == Region.ORDER ? skipGroup(tokens, i + 1) : i + 1;
                continue;
            }
            if (i > 0 && "as".equals(tokens.get(i - 1).keyword())) {
                i++;     // the target type of a cast, not a column
                continue;
            }
            column(token);
            i++;
        }
    }

    /**
     * {@code having} is skipped rather than read: its columns are aggregates over
     * groups, which no index on a column serves.
     */
    private static @Nullable Region opens(List<Token> tokens, int i, String word) {
        if (word.equals("where") || word.equals("on")) {
            return Region.PREDICATE;
        }
        if (word.equals("order") && i + 1 < tokens.size() && "by".equals(tokens.get(i + 1).keyword())) {
            return Region.ORDER;
        }
        return null;
    }

    /**
     * The tables after {@code from}, {@code join}, {@code update} or {@code into}.
     *
     * @param list whether a comma continues the list ({@code from a, b}) rather
     *        than ending it
     * @return the index of the token that stopped the list, which the caller reads
     */
    private int tableList(List<Token> tokens, int start, boolean list) {
        int i = start;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                // A derived table: it has no name, and its alias resolves to nothing.
                i = skipGroup(tokens, i + 1);
                i = alias(tokens, i, null);
                if (list && i < tokens.size() && tokens.get(i).is(",")) {
                    i++;
                    continue;
                }
                return i;
            }
            if (token.kind() != Token.Kind.NAME || token.isKeyword()) {
                return i;
            }
            List<String> parts = token.parts();
            String name = parts.get(parts.size() - 1);
            String schema = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
            if (!aliases.containsKey(name)) {
                aliases.put(name, name);
            }
            if (!contains(name, schema)) {
                tables.add(new TableRef(name, schema));
            }
            i = alias(tokens, i + 1, name);
            if (list && i < tokens.size() && tokens.get(i).is(",")) {
                i++;
                continue;
            }
            return i;
        }
        return i;
    }

    private boolean contains(String name, @Nullable String schema) {
        for (TableRef table : tables) {
            if (table.name().equals(name) && java.util.Objects.equals(table.schema(), schema)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The optional alias after a table, with or without {@code as}.
     *
     * @param table the table it stands for, null for a derived table, whose alias
     *        is recorded all the same so that a column qualified by it is known to
     *        be unattributable
     */
    private int alias(List<Token> tokens, int start, @Nullable String table) {
        int i = start;
        if (i < tokens.size() && "as".equals(tokens.get(i).keyword())) {
            i++;
        }
        if (i >= tokens.size()) {
            return i;
        }
        Token token = tokens.get(i);
        if (token.kind() != Token.Kind.NAME || token.isKeyword() || token.parts().size() > 1
                || TABLE_LIST_STOP.contains(token.text())) {
            return i;
        }
        aliases.put(token.parts().get(0), table);
        return i + 1;
    }

    /** A column reference, resolved to its table or giving up on the whole shape. */
    private void column(Token token) {
        List<String> parts = token.parts();
        String column = parts.get(parts.size() - 1);
        String table;
        if (parts.size() == 1) {
            if (tables.size() != 1) {
                readable = false;     // an unqualified column in a join
                return;
            }
            table = tables.get(0).name();
        } else {
            String qualifier = parts.get(parts.size() - 2);
            if (!aliases.containsKey(qualifier)) {
                readable = false;     // an alias from somewhere this scan did not see
                return;
            }
            table = aliases.get(qualifier);
            if (table == null) {
                readable = false;     // the alias of a derived table
                return;
            }
        }
        if (predicateKeys.add(table + "." + column)) {
            predicates.add(new ColumnRef(table, column));
        }
    }

    private static boolean followedByParenthesis(List<Token> tokens, int i) {
        return i + 1 < tokens.size() && tokens.get(i + 1).is("(");
    }

    /** @return the index just past the {@code )} that matches the {@code (} before {@code start} */
    private static int skipGroup(List<Token> tokens, int start) {
        int depth = 1;
        int i = start;
        while (i < tokens.size() && depth > 0) {
            if (tokens.get(i).is("(")) {
                depth++;
            } else if (tokens.get(i).is(")")) {
                depth--;
            }
            i++;
        }
        return i;
    }

    // --- tokens --------------------------------------------------------------

    /**
     * One token: a name (possibly a dotted chain), a punctuation mark, a parameter
     * or a number. String literals are dropped as they are read — a sanitised
     * statement has none left worth looking at.
     *
     * @param parts the segments of a name, unquoted and lower-cased; empty otherwise
     */
    private record Token(Kind kind, String text, List<String> parts, boolean quoted) {

        enum Kind { NAME, PUNCTUATION, PARAMETER, NUMBER }

        boolean is(String punctuation) {
            return kind == Kind.PUNCTUATION && text.equals(punctuation);
        }

        boolean isKeyword() {
            return keyword() != null;
        }

        /** The word this token is, when it is one that can never be a name. */
        @Nullable String keyword() {
            return kind == Kind.NAME && !quoted && parts.size() == 1 && KEYWORDS.contains(text)
                    ? text : null;
        }

        static List<Token> of(String statement) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            int length = statement.length();
            while (i < length) {
                char c = statement.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '\'') {
                    i = literal(statement, i);
                } else if (c == '?') {
                    tokens.add(new Token(Kind.PARAMETER, "?", List.of(), false));
                    i++;
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < length && (Character.isDigit(statement.charAt(i))
                            || statement.charAt(i) == '.')) {
                        i++;
                    }
                    tokens.add(new Token(Kind.NUMBER, statement.substring(start, i), List.of(), false));
                } else if (starts(statement, i)) {
                    i = name(statement, i, tokens);
                } else {
                    tokens.add(new Token(Kind.PUNCTUATION, String.valueOf(c), List.of(), false));
                    i++;
                }
            }
            return tokens;
        }

        /** Past a string literal, {@code ''} included. */
        private static int literal(String statement, int start) {
            int i = start + 1;
            while (i < statement.length()) {
                if (statement.charAt(i) == '\'') {
                    if (i + 1 < statement.length() && statement.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return i;
        }

        private static boolean starts(String statement, int i) {
            char c = statement.charAt(i);
            return Character.isLetter(c) || c == '_' || c == '"' || c == '`' || c == '[';
        }

        /** A name and the {@code .}-chained parts that belong to it. */
        private static int name(String statement, int start, List<Token> tokens) {
            List<String> parts = new ArrayList<>();
            boolean[] quoted = {false};
            int i = part(statement, start, parts, quoted);
            while (i < statement.length() && statement.charAt(i) == '.'
                    && i + 1 < statement.length() && starts(statement, i + 1)) {
                i = part(statement, i + 1, parts, quoted);
            }
            tokens.add(new Token(Kind.NAME, String.join(".", parts), List.copyOf(parts), quoted[0]));
            return i;
        }

        private static int part(String statement, int start, List<String> parts, boolean[] quoted) {
            char c = statement.charAt(start);
            char close = c == '"' ? '"' : c == '`' ? '`' : c == '[' ? ']' : 0;
            if (close != 0) {
                quoted[0] = true;
                int end = statement.indexOf(close, start + 1);
                if (end < 0) {
                    parts.add(statement.substring(start + 1).toLowerCase(Locale.ROOT));
                    return statement.length();
                }
                parts.add(statement.substring(start + 1, end).toLowerCase(Locale.ROOT));
                return end + 1;
            }
            int i = start;
            while (i < statement.length() && (Character.isLetterOrDigit(statement.charAt(i))
                    || statement.charAt(i) == '_' || statement.charAt(i) == '$')) {
                i++;
            }
            parts.add(statement.substring(start, i).toLowerCase(Locale.ROOT));
            return i;
        }
    }
}
