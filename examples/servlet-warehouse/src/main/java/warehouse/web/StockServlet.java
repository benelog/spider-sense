package warehouse.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/stock/{sku}}: one row by the unique SKU, as JSON. One query,
 * on an index, about a millisecond — the fast endpoint the slow ones are
 * measured against.
 */
public class StockServlet extends HttpServlet {

    private final transient DataSource dataSource;

    public StockServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String sku = path == null || path.length() < 2 ? "" : path.substring(1);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "select sku, name, quantity, location from items where sku = ?")) {
            select.setString(1, sku);
            try (ResultSet found = select.executeQuery()) {
                if (!found.next()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No item with SKU " + sku);
                    return;
                }
                Out.json(response, """
                        {"sku":%s,"name":%s,"quantity":%d,"location":%s}""".formatted(
                        Out.quote(found.getString("sku")), Out.quote(found.getString("name")),
                        found.getInt("quantity"), Out.quote(found.getString("location"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read stock for " + sku, e);
        }
    }
}
