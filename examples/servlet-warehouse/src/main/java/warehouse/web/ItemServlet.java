package warehouse.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /items/{sku}}: one item, its movements, and the supplier behind
 * each movement — fetched one at a time.
 *
 * <p>That last part is the N+1, and it is deliberate: a seeded SKU has forty
 * movements, so the page runs 1 + 1 + 40 queries, all of them fast, and only an observability
 * tool that counts queries per request will say so.
 *
 * <p>The mapping is {@code /items/*}, so every SKU is the same endpoint:
 * {@code GET /items/*} is what the agent puts in {@code http.route} and what
 * Spider Sense groups by. That is the honest name for it — four hundred thousand
 * SKUs are one piece of code.
 */
public class ItemServlet extends HttpServlet {

    private final transient DataSource dataSource;

    public ItemServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String sku = path == null || path.length() < 2 ? "" : path.substring(1);

        try (Connection connection = dataSource.getConnection()) {
            Item item = findItem(connection, sku);
            if (item == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No item with SKU " + sku);
                return;
            }
            List<Movement> movements = findMovements(connection, item.id);

            StringBuilder rows = new StringBuilder();
            for (Movement movement : movements) {
                // One query per movement, on purpose. A join would do it once.
                String supplier = supplierName(connection, movement.supplierId);
                rows.append("<tr><td>%+d</td><td>%s</td><td>%s</td></tr>%n".formatted(
                        movement.delta, Out.esc(movement.note), Out.esc(supplier)));
            }

            Out.html(response, """
                    <h1>%s</h1>
                    <p><a href="/items">back to the list</a></p>
                    <p>%s, category %s, %d in stock at %s, %s each.</p>
                    <h2>Movements (%d)</h2>
                    <table><tr><th>Delta</th><th>Note</th><th>Supplier</th></tr>
                    %s
                    </table>
                    """.formatted(Out.esc(item.sku), Out.esc(item.name), Out.esc(item.category),
                    item.quantity, Out.esc(item.location), item.unitPrice, movements.size(), rows));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read item " + sku, e);
        }
    }

    private @Nullable Item findItem(Connection connection, String sku) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                select id, sku, name, category, quantity, unit_price, location
                from items where sku = ?
                """)) {
            select.setString(1, sku);
            try (ResultSet found = select.executeQuery()) {
                if (!found.next()) {
                    return null;
                }
                return new Item(found.getLong("id"), found.getString("sku"), found.getString("name"),
                        found.getString("category"), found.getInt("quantity"),
                        found.getBigDecimal("unit_price").toPlainString(), found.getString("location"));
            }
        }
    }

    private List<Movement> findMovements(Connection connection, long itemId) throws SQLException {
        List<Movement> movements = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                select id, supplier_id, delta, note from movements
                where item_id = ? order by id
                """)) {
            select.setLong(1, itemId);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    movements.add(new Movement(found.getLong("supplier_id"), found.getInt("delta"),
                            found.getString("note")));
                }
            }
        }
        return movements;
    }

    private String supplierName(Connection connection, long supplierId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "select name from suppliers where id = ?")) {
            select.setLong(1, supplierId);
            try (ResultSet found = select.executeQuery()) {
                return found.next() ? found.getString(1) : "unknown";
            }
        }
    }

    private record Item(long id, String sku, String name, String category, int quantity,
                        String unitPrice, String location) {
    }

    private record Movement(long supplierId, int delta, String note) {
    }
}
