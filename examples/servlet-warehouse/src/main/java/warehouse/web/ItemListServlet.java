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
 * {@code GET /items?page=&q=}: fifty items as a table.
 *
 * <p>Two queries live behind one endpoint, which is the interesting part.
 * Without {@code q} it is a window over the primary key and takes about a
 * millisecond; with {@code q} it is {@code lower(name) like '%…%'} over every
 * row, and there is no index on {@code name} or {@code category}, so it is a
 * full scan measured in hundreds of milliseconds. The observability tool shows them as one
 * endpoint with two statements, one of them slow.
 */
public class ItemListServlet extends HttpServlet {

    private static final int PAGE_SIZE = 50;

    private final transient DataSource dataSource;

    public ItemListServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int page = Math.max(0, parse(request.getParameter("page")));
        String q = request.getParameter("q");
        StringBuilder rows = new StringBuilder();
        try (Connection connection = dataSource.getConnection()) {
            if (q == null || q.isBlank()) {
                listByPage(connection, page, rows);
            } else {
                search(connection, q.toLowerCase(), rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list items", e);
        }

        String heading = (q == null || q.isBlank())
                ? "Items, page %d".formatted(page)
                : "Items matching &ldquo;%s&rdquo;".formatted(Out.esc(q));
        Out.html(response, """
                <h1>%s</h1>
                <p><a href="/">index</a> — <a href="/items?page=%d">next page</a></p>
                <table><tr><th>SKU</th><th>Name</th><th>Category</th><th>Qty</th><th>Price</th><th>Bay</th></tr>
                %s
                </table>
                """.formatted(heading, page + 1, rows));
    }

    /** Indexed, because the primary key orders it: fast whatever the table holds. */
    private void listByPage(Connection connection, int page, StringBuilder rows) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                select sku, name, category, quantity, unit_price, location
                from items order by id limit 50 offset ?
                """)) {
            select.setInt(1, page * PAGE_SIZE);
            append(select, rows);
        }
    }

    /**
     * Unindexed by design: every row is read and lowercased to answer this.
     *
     * <p>It orders by {@code name} rather than by {@code id} on purpose. Ordered
     * by the primary key the database could walk the index and stop at the
     * fiftieth match, which for a common word is a few hundred rows; ordered by
     * an unindexed column it has to look at every row and then sort.
     */
    private void search(Connection connection, String q, StringBuilder rows) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                select sku, name, category, quantity, unit_price, location
                from items where lower(name) like ? or lower(category) like ?
                order by name limit 50
                """)) {
            select.setString(1, "%" + q + "%");
            select.setString(2, "%" + q + "%");
            append(select, rows);
        }
    }

    private void append(PreparedStatement select, StringBuilder rows) throws SQLException {
        try (ResultSet found = select.executeQuery()) {
            while (found.next()) {
                String sku = found.getString("sku");
                rows.append("<tr><td><a href=\"/items/%s\">%s</a></td><td>%s</td><td>%s</td>"
                        .formatted(Out.esc(sku), Out.esc(sku), Out.esc(found.getString("name")),
                                Out.esc(found.getString("category"))))
                        .append("<td>%d</td><td>%s</td><td>%s</td></tr>%n"
                                .formatted(found.getInt("quantity"), found.getBigDecimal("unit_price"),
                                        Out.esc(found.getString("location"))));
            }
        }
    }

    private int parse(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
