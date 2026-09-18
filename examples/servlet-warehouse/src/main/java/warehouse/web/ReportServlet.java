package warehouse.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.StringJoiner;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/report}: two aggregates over every item, as JSON.
 *
 * <p>Slow on purpose. Neither {@code category} nor {@code supplier_id} is
 * indexed, so each {@code group by} reads all 100,000 rows; together they take
 * a few hundred milliseconds, which is what an endpoint looks like when it is
 * the query that is slow rather than the code around it.
 */
public class ReportServlet extends HttpServlet {

    private final transient DataSource dataSource;

    public ReportServlet(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        StringJoiner categories = new StringJoiner(",", "[", "]");
        StringJoiner suppliers = new StringJoiner(",", "[", "]");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                    select category, count(*), sum(quantity * unit_price)
                    from items group by category order by 3 desc
                    """)) {
                while (rows.next()) {
                    categories.add("""
                            {"category":%s,"items":%d,"value":%s}""".formatted(
                            Out.quote(rows.getString(1)), rows.getLong(2),
                            rows.getBigDecimal(3) == null ? "0" : rows.getBigDecimal(3).toPlainString()));
                }
            }
            try (ResultSet rows = statement.executeQuery("""
                    select supplier_id, count(*) from items
                    group by supplier_id order by 2 desc limit 10
                    """)) {
                while (rows.next()) {
                    suppliers.add("""
                            {"supplierId":%d,"items":%d}""".formatted(rows.getLong(1), rows.getLong(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not build the report", e);
        }

        Out.json(response, """
                {"categories":%s,"topSuppliers":%s}""".formatted(categories, suppliers));
    }
}
