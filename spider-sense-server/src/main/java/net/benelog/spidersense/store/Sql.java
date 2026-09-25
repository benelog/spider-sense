package net.benelog.spidersense.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The whole persistence framework: a pool, a row mapper and four methods.
 *
 * <p>Spider Silk's rule against reflection frameworks applies to the data side
 * too. Every statement in this module is written out, every column is read by
 * name, and the price of that is this class rather than a dependency.
 */
public final class Sql {

    /** A row, read by hand. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** A row read for what it does with the row rather than for what it answers with. */
    @FunctionalInterface
    public interface RowReader {
        void read(ResultSet rs) throws SQLException;
    }

    /** Work that needs the connection itself — the writer's batches, the sweeper's transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** A checked {@link SQLException} the handlers have no useful answer to. */
    public static final class SqlException extends RuntimeException {
        public SqlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final System.Logger LOG = System.getLogger(Sql.class.getName());

    private final DataSource dataSource;

    public Sql(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> List<T> query(String sql, List<Object> params, RowMapper<T> mapper) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(mapper.map(rs));
                    }
                    return rows;
                }
            }
        }, sql);
    }

    /**
     * Every row of a query that is read for its effect: the reader fills a map or
     * an array as it goes and there is no list of rows to build.
     */
    public void forEach(String sql, List<Object> params, RowReader reader) {
        // Work always answers with something; there is nothing to answer with here.
        Boolean unused = withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        reader.read(rs);
                    }
                }
            }
            return Boolean.TRUE;
        }, sql);
    }

    /** The first row, or null when there is none. */
    public <T> @Nullable T queryOne(String sql, List<Object> params, RowMapper<T> mapper) {
        List<T> rows = query(sql, params, mapper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String sql, List<Object> params) {
        Long value = queryOne(sql, params, rs -> rs.getLong(1));
        return value == null ? 0 : value;
    }

    public int update(String sql, List<Object> params) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        }, sql);
    }

    /** DDL and other statements with no parameters. */
    public void execute(String... statements) {
        // Work always answers with something; there is nothing to answer with here.
        Boolean unused = withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String each : statements) {
                    statement.execute(each);
                }
            }
            return Boolean.TRUE;
        }, statements.length == 0 ? "" : statements[0]);
    }

    /** Borrows a connection for work that spans several statements. */
    public <T> T withConnection(Work<T> work, String description) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new SqlException("SQL failed: " + description, e);
        }
    }

    /**
     * Borrows a connection and runs {@code work} in one transaction on it, see
     * {@link #inTransaction(Connection, Work)}.
     */
    public <T> T transaction(Work<T> work, String description) {
        return withConnection(connection -> inTransaction(connection, work), description);
    }

    /**
     * Runs {@code work} in one transaction on a connection the caller holds:
     * committed when it returns, rolled back when it throws, and the connection's
     * auto-commit set back to what it was either way.
     *
     * <p>An {@link Error} rolls back too. A {@link StackOverflowError} from an
     * absurdly nested attribute leaves the statements already run in the
     * transaction, and the reset of auto-commit would commit them: half a flush,
     * or half an imported document. A rollback that fails is added to the failure
     * as suppressed rather than replacing it, and a reset of auto-commit that fails
     * loses nothing once the transaction has ended, so it is only logged.
     */
    public static <T> T inTransaction(Connection connection, Work<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "Spider Sense could not reset auto-commit: " + e.getMessage());
            }
        }
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Binds {@code params} to the statement's placeholders, in order. */
    public static void bind(PreparedStatement statement, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    /**
     * How many values one {@code IN (...)} list of the store names.
     *
     * <p>H2's cost for one {@code IN} list grows with the square of its length, and
     * a flush after a burst, or an import, names tens of thousands of ids: 20,000
     * traces in one list took about ten seconds to read back, and the writer fell
     * behind its queue. A list of ids that may be that long is read in chunks of
     * this size.
     */
    public static final int IN_LIST_CHUNK = 500;

    /** {@code values} in consecutive chunks of at most {@link #IN_LIST_CHUNK}, as views of it. */
    public static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>((values.size() + IN_LIST_CHUNK - 1) / IN_LIST_CHUNK);
        for (int from = 0; from < values.size(); from += IN_LIST_CHUNK) {
            chunks.add(values.subList(from, Math.min(values.size(), from + IN_LIST_CHUNK)));
        }
        return chunks;
    }

    /** {@code ?, ?, ?} for an {@code IN} list of {@code count} values. */
    public static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, count), "?"));
    }

    /** A nullable integer column; JDBC's {@code getInt} cannot tell 0 from NULL. */
    public static @Nullable Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** The same, by column index, for an aggregate with no name. */
    public static @Nullable Long longOrNull(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
