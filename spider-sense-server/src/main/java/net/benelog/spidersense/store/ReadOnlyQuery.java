package net.benelog.spidersense.store;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The escape hatch: one read-only statement over the schema storage.adoc#schema documents.
 *
 * <p>Findings, compare and check answer the questions Spider Sense anticipated.
 * This answers the one it did not, and it is the only place where an agent's own
 * words reach the database — so it is read-only three times over, and each layer
 * catches what the one before it cannot (cli.adoc#read-only):
 *
 * <ol>
 * <li>The statement allowlist below. It reads the first keyword after the
 *     comments and refuses a second statement, which is cheap and stops the
 *     obvious mistake. It is a heuristic over SQL text and is not trusted to be
 *     more than that.</li>
 * <li>{@link Schema#READER}, an H2 user with {@code SELECT} and nothing else.
 *     This is the layer that actually holds: whatever the parser here thought it
 *     saw, H2 refuses the write, the DDL and the administrator-only functions
 *     ({@code FILE_WRITE}, {@code CSVWRITE}, {@code FILE_READ},
 *     {@code LINK_SCHEMA}, {@code RUNSCRIPT}) to a user that does not have them.</li>
 * <li>The limits on this connection: a row cap that also detects truncation, a
 *     query timeout, a read-only connection with autocommit off, and a
 *     {@code rollback} whatever happens.</li>
 * </ol>
 */
public final class ReadOnlyQuery {

    /** One answer: the column names, the rows, and whether the cap cut them off. */
    public record Result(List<String> columns, List<List<Object>> rows, boolean truncated,
            long elapsedMs) {
    }

    /**
     * A statement that was not run, or one H2 would not run: the message is the
     * whole answer.
     *
     * <p>An {@link IllegalArgumentException} because that is what the API answers
     * {@code 400} for and what the CLI exits {@code 2} on, and both are right: the
     * statement, not the store, is what was wrong.
     */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    /** Rows returned when nobody said, and the most that may be asked for. */
    public static final int LIMIT = 200;
    public static final int LIMIT_MAX = 5000;

    /** Longer than this and the question was not one a development database answers. */
    private static final int TIMEOUT_SECONDS = 10;

    private static final Set<String> ALLOWED =
            Set.of("SELECT", "WITH", "TABLE", "VALUES", "EXPLAIN", "SHOW");

    private final Database database;

    public ReadOnlyQuery(Database database) {
        this.database = database;
    }

    /**
     * Runs one statement and reads at most {@code limit} rows of it.
     *
     * @param limit clamped to {@code [1, LIMIT_MAX]}; one row more is fetched than
     *              returned, which is how truncation is known rather than guessed
     */
    public Result run(@Nullable String statement, int limit) {
        guard(statement);
        // guard refuses a statement that is null or blank, so this one is neither.
        String sql = Objects.requireNonNull(statement, "guard let a null statement through");
        int cap = Math.min(Math.max(1, limit), LIMIT_MAX);
        long started = System.nanoTime();
        try (Connection connection = database.reader()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement command = connection.createStatement()) {
                command.setMaxRows(cap + 1);
                command.setQueryTimeout(TIMEOUT_SECONDS);
                try (ResultSet rs = command.executeQuery(sql)) {
                    return read(rs, cap, started);
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new Refused(oneLine(e.getMessage()));
        }
    }

    private static Result read(ResultSet rs, int cap, long started) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int width = meta.getColumnCount();
        List<String> columns = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() == cap) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(width);
            for (int i = 1; i <= width; i++) {
                row.add(value(rs, i));
            }
            rows.add(row);
        }
        return new Result(List.copyOf(columns), List.copyOf(rows), truncated,
                Math.round((System.nanoTime() - started) / 1_000_000.0));
    }

    /**
     * A cell as JSON has it: a number, a string, a boolean or null.
     *
     * <p>The store keeps every instant as epoch milliseconds, so the numbers that
     * matter stay numbers; a {@code TIMESTAMP} a query computes itself becomes an
     * ISO string rather than an object nobody can read back.
     */
    private static Object value(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            if (decimal.scale() <= 0) {
                try {
                    return decimal.longValueExact();
                } catch (ArithmeticException pastALong) {
                    // 1E+20 has one digit of precision and no long holds it: a double does.
                }
            }
            double approximate = decimal.doubleValue();
            return Double.isFinite(approximate) ? (Object) approximate : decimal.toString();
        }
        if (value instanceof BigInteger integer) {
            return integer.bitLength() < 64 ? (Object) integer.longValue() : integer.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        return String.valueOf(value);
    }

    /**
     * The allowlist: one statement, and it reads.
     *
     * <p>Read over {@link #bare(String)}, so a keyword inside a comment or a
     * string literal is not mistaken for the statement's own.
     */
    static void guard(@Nullable String statement) {
        if (statement == null || statement.isBlank()) {
            throw new Refused("a statement is needed: sql was empty");
        }
        String bare = bare(statement).trim();
        if (bare.isEmpty()) {
            throw new Refused("a statement is needed: sql was only comments");
        }
        int end = 0;
        while (end < bare.length()
                && (Character.isLetter(bare.charAt(end)) || bare.charAt(end) == '_')) {
            end++;
        }
        String keyword = bare.substring(0, end).toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(keyword)) {
            throw new Refused("only " + String.join(", ", new java.util.TreeSet<>(ALLOWED))
                    + " may be run here, and this statement starts with "
                    + (keyword.isEmpty() ? "'" + bare.charAt(0) + "'" : keyword));
        }
        int semicolon = bare.indexOf(';');
        if (semicolon >= 0 && !bare.substring(semicolon + 1).isBlank()) {
            throw new Refused("one statement at a time: there is more after the first ';'");
        }
        if (FOR_UPDATE.matcher(bare).find()) {
            // A read, but one that locks rows the writer then waits on past its lock
            // timeout, losing a flush.
            throw new Refused("FOR UPDATE takes locks the writer would wait on; read without it");
        }
    }

    private static final java.util.regex.Pattern FOR_UPDATE = java.util.regex.Pattern.compile(
            "\\bFOR\\s+UPDATE\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The statement with its comments and its literals blanked out.
     *
     * <p>{@code SELECT 1 -- ; DELETE FROM span}, {@code SELECT ';' FROM span} and
     * {@code SELECT $$';$$ FROM span} are one statement each, and this is what makes
     * the guard see that. It is a scanner and not a parser, which is the honest
     * description of the first layer and the reason there is a second one.
     */
    static String bare(String statement) {
        StringBuilder bare = new StringBuilder(statement.length());
        int at = 0;
        int end = statement.length();
        while (at < end) {
            char c = statement.charAt(at);
            if (c == '-' && at + 1 < end && statement.charAt(at + 1) == '-') {
                while (at < end && statement.charAt(at) != '\n') {
                    at++;
                }
                bare.append(' ');
            } else if (c == '/' && at + 1 < end && statement.charAt(at + 1) == '*') {
                at += 2;
                while (at + 1 < end
                        && !(statement.charAt(at) == '*' && statement.charAt(at + 1) == '/')) {
                    at++;
                }
                at = Math.min(end, at + 2);
                bare.append(' ');
            } else if (c == '\'' || c == '"') {
                at = past(statement, at, c);
                bare.append(' ');
            } else if (c == '$' && at + 1 < end && statement.charAt(at + 1) == '$') {
                // H2's dollar-quoted string: $$ … $$, with nothing escaped inside.
                int close = statement.indexOf("$$", at + 2);
                at = close < 0 ? end : close + 2;
                bare.append(' ');
            } else {
                bare.append(c);
                at++;
            }
        }
        return bare.toString();
    }

    /** The index after the literal that opens at {@code at}, doubled quotes included. */
    private static int past(String statement, int at, char quote) {
        int end = statement.length();
        at++;
        while (at < end) {
            if (statement.charAt(at) == quote) {
                if (at + 1 < end && statement.charAt(at + 1) == quote) {
                    at += 2;
                    continue;
                }
                return at + 1;
            }
            at++;
        }
        return end;
    }

    /** H2 says why on several lines; an error is one line here, as cli.adoc#sql asks. */
    private static String oneLine(@Nullable String message) {
        return message == null ? "the statement failed" : message.replaceAll("\\s+", " ").trim();
    }
}
