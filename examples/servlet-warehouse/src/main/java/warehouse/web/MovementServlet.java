package warehouse.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code POST /api/movements}: the only write in the application, and the only
 * transaction.
 *
 * <p>The body is {@code application/x-www-form-urlencoded} and read with
 * {@code request.getParameter}, which is the Servlet way and means no JSON
 * parser is needed. The row is locked with {@code select … for update}, so the
 * trace shows a connection held across three statements, and a movement that
 * would take the stock below zero is rolled back and answered 409 rather than
 * written and apologised for.
 */
public class MovementServlet extends HttpServlet {

    private final transient DataSource dataSource;

    public MovementServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String sku = request.getParameter("sku");
        String note = request.getParameter("note");
        int delta;
        try {
            delta = Integer.parseInt(request.getParameter("delta"));
        } catch (NumberFormatException | NullPointerException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "delta must be a whole number, was " + request.getParameter("delta"));
            return;
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);

            Locked locked = lock(connection, sku);
            if (locked == null) {
                connection.rollback();
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No item with SKU " + sku);
                return;
            }
            int quantity = locked.quantity + delta;
            if (quantity < 0) {
                connection.rollback();
                response.sendError(HttpServletResponse.SC_CONFLICT,
                        "Stock of " + sku + " would go negative");
                return;
            }

            long id = insert(connection, locked, delta, note);
            update(connection, locked.id, quantity);
            connection.commit();

            Out.json(response, HttpServletResponse.SC_CREATED,
                    """
                    {"id":%d,"sku":%s,"quantity":%d}""".formatted(id, Out.quote(sku), quantity));
        } catch (SQLException e) {
            rollback(connection);
            throw new IllegalStateException("Could not record a movement for " + sku, e);
        } finally {
            close(connection);
        }
    }

    /** {@code for update} holds the row until the commit, which is the point of the transaction. */
    private Locked lock(Connection connection, String sku) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "select id, supplier_id, quantity from items where sku = ? for update")) {
            select.setString(1, sku);
            try (ResultSet found = select.executeQuery()) {
                return found.next()
                        ? new Locked(found.getLong("id"), found.getLong("supplier_id"),
                                found.getInt("quantity"))
                        : null;
            }
        }
    }

    private long insert(Connection connection, Locked locked, int delta, String note)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into movements (item_id, supplier_id, delta, note, moved_at)
                values (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, locked.id);
            insert.setLong(2, locked.supplierId);
            insert.setInt(3, delta);
            insert.setString(4, note == null ? "manual adjustment" : note);
            insert.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private void update(Connection connection, long itemId, int quantity) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "update items set quantity = ? where id = ?")) {
            update.setInt(1, quantity);
            update.setLong(2, itemId);
            update.executeUpdate();
        }
    }

    private void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Nothing useful is left to do about it.
            }
        }
    }

    private void close(Connection connection) {
        if (connection != null) {
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException ignored) {
                // The pool will discard it.
            }
        }
    }

    private record Locked(long id, long supplierId, int quantity) {
    }
}
