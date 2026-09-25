package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Named moments: {@code before}, {@code after-fix}, {@code start}.
 *
 * <p>A mark is what turns "did my change help" into a question with an answer:
 * {@code compare --before=before --after=after} needs two instants, and an agent
 * thinks in names rather than in epoch milliseconds (marks-and-compare.adoc#marks). The rows are
 * shared like everything else in the database and swept with the retention, so a
 * mark written by the CLI is visible to the UI and to the next process.
 *
 * <p>The writer inserts a {@code start} mark of its own whenever a service
 * reports a process id it has not stored, which is what {@code since=start}
 * resolves to — "since the application was last restarted", with no cooperation
 * from anybody.
 */
public final class Marks {

    /** One named moment. {@code service} and {@code note} are optional. */
    public record Mark(long id, long at, String name, @Nullable String service, @Nullable String note) {
    }

    /** The most characters a mark's name may have, which is the column's width. */
    public static final int MAX_NAME = 64;

    /** What a mark may be called; the same expression api.adoc#marks states. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_NAME + "}");

    /** The name the writer uses for an automatic mark. */
    public static final String START = "start";

    /**
     * A condition on {@code mark o} that spares each service's newest start mark,
     * for the deletes that would otherwise leave a running service without one:
     * the writer inserts a start mark only when the process id changes, so the
     * one of the running process is never written again.
     */
    static final String NOT_NEWEST_START = "NOT (o.name = '" + START + "' AND o.at_ms = (SELECT MAX(m.at_ms)"
            + " FROM mark m WHERE m.name = '" + START + "' AND m.service IS NOT DISTINCT FROM o.service))";

    private final Sql sql;
    private final LongSupplier clock;

    public Marks(Sql sql) {
        this(sql, System::currentTimeMillis);
    }

    /** @param clock what a mark without an instant is taken at, in epoch milliseconds */
    public Marks(Sql sql, LongSupplier clock) {
        this.sql = sql;
        this.clock = clock;
    }

    /**
     * Records a mark.
     *
     * @param at the instant, or null for now
     * @throws IllegalArgumentException when the name is not {@link #NAME}
     */
    public Mark create(@Nullable String name, @Nullable String service, @Nullable String note,
            @Nullable Long at) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "A mark name is 1 to " + MAX_NAME + " characters of [A-Za-z0-9._-]: " + name);
        }
        MarkRow row = new MarkRow(at == null ? clock.getAsLong() : at, name, service, note);
        long id = sql.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(MarkRow.INSERT,
                    Statement.RETURN_GENERATED_KEYS)) {
                row.bind(statement);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        }, "insert mark");
        return new Mark(id, row.atMs(), name, service, row.storedNote());
    }

    /** The newest marks, newest first. */
    public List<Mark> list(int limit) {
        return new ArrayList<>(sql.query(
                "SELECT * FROM mark ORDER BY at_ms DESC, id DESC LIMIT " + Math.max(1, limit),
                List.of(), Marks::map));
    }

    /**
     * The newest mark with this name, or null.
     *
     * <p>When a service is named its own mark wins, because two applications
     * sharing the database both write a {@code start}; with none of that service
     * the newest of any service is the honest answer rather than nothing at all.
     */
    public @Nullable Mark newest(String name, @Nullable String service) {
        if (service != null) {
            Mark ofService = sql.queryOne(
                    "SELECT * FROM mark WHERE name = ? AND service = ? ORDER BY at_ms DESC, id DESC LIMIT 1",
                    List.of(name, service), Marks::map);
            if (ofService != null) {
                return ofService;
            }
        }
        return sql.queryOne("SELECT * FROM mark WHERE name = ? ORDER BY at_ms DESC, id DESC LIMIT 1",
                List.of(name), Marks::map);
    }

    private static Mark map(ResultSet rs) throws SQLException {
        return new Mark(rs.getLong("id"), rs.getLong("at_ms"), rs.getString("name"),
                rs.getString("service"), rs.getString("note"));
    }
}
