package warehouse.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The three counts and a link to everything below, so the example is walkable. */
public class IndexServlet extends HttpServlet {

    private final transient DataSource dataSource;

    public IndexServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long items;
        long suppliers;
        long movements;
        try (Connection connection = dataSource.getConnection()) {
            items = count(connection, "items");
            suppliers = count(connection, "suppliers");
            movements = count(connection, "movements");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the counts", e);
        }

        Out.html(response, """
                <h1>servlet-warehouse</h1>
                <p>%,d items, %,d suppliers, %,d movements.</p>
                <ul>
                  <li><a href="/items">/items</a> — the first 50 items, by id, fast</li>
                  <li><a href="/items?q=hinge">/items?q=hinge</a> — a full scan, slow on purpose</li>
                  <li><a href="/items/SKU-000001">/items/SKU-000001</a> — one item and its movements, N+1 on purpose</li>
                  <li><a href="/api/stock/SKU-000002">/api/stock/SKU-000002</a> — stock as JSON, one query</li>
                  <li><a href="/api/report">/api/report</a> — aggregates over every row, slow on purpose</li>
                  <li><a href="/api/async?ms=700">/api/async?ms=700</a> — completed on another thread; over 1500 ms it is a 503</li>
                  <li><a href="/api/flaky">/api/flaky</a> — fails about 30%% of the time</li>
                  <li><a href="/api/health">/api/health</a> — liveness</li>
                </ul>
                <p>A movement is a <code>POST /api/movements</code> with the form fields
                <code>sku</code>, <code>delta</code> and <code>note</code>.</p>
                """.formatted(items, suppliers, movements));
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
