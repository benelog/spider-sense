package net.benelog.spidersense.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The {@code metric_series} row of a {@code (service, name, attributes)}, found or
 * created, for the writer's flush and for an import alike.
 *
 * <p>The row is locked either way. The orphan sweep locks a series before it
 * deletes it and checks for points again once the lock is held
 * ({@link Sweeper#deleteOrphanSeries(Connection)}), so a sweep running while a
 * flush or an import adds the first points of a series waits for their commit
 * rather than deleting the series under them (storage.adoc#writer).
 */
final class MetricSeriesRows {

    private MetricSeriesRows() {
    }

    /**
     * The id of the series, locked for the rest of the caller's transaction.
     *
     * @param attributes the series' attributes as {@link AttrJson#encodeSorted}
     *                   writes them, which is what its {@code attr_hash} is of
     */
    static long lookupOrCreate(Connection connection, String service, String name, String attributes)
            throws SQLException {
        String hash = Ids.shortHash(attributes);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM metric_series WHERE service = ? AND name = ? AND attr_hash = ? FOR UPDATE")) {
            select.setString(1, service);
            select.setString(2, name);
            select.setString(3, hash);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO metric_series (service, name, attr_hash, attributes) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, service);
            insert.setString(2, name);
            insert.setString(3, hash);
            insert.setString(4, Writer.cut(attributes, 4096));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("No id for metric series " + name);
    }
}
