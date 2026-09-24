package bookstore;

import java.util.Arrays;

import javax.sql.DataSource;

import bookstore.service.Seeder;
import net.benelog.spidersilk.App;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * Application startup: BookstoreContext builds the whole App, and this class
 * only seeds the database and starts it.
 *
 * <p>The bookstore's job is to behave badly in interesting ways, so that
 * Spider Sense has something to show: a full scan over 200,000 rows, an N+1
 * page, a query that sleeps, an endpoint that sleeps without a query, one that
 * throws, one that answers 404 on purpose, and one that rejects bad input.
 *
 * <p>{@code --dev} switches the templates to the source tree, so an edited .jte
 * file shows up on browser refresh; {@link BookstoreContext} has the details.
 */
public class BookstoreApp {

    public static final int PORT = 8081;

    /** The database file, shared with anything else that opens it thanks to AUTO_SERVER. */
    public static final String JDBC_URL = "jdbc:h2:~/db/spider-sense/bookstore;AUTO_SERVER=TRUE";

    public static void main(String[] args) {
        boolean devMode = Arrays.asList(args).contains("--dev");
        DataSource dataSource = JdbcConnectionPool.create(JDBC_URL, "sa", "");
        new Seeder(dataSource).seed();

        App app = new BookstoreContext(dataSource, devMode)
                .start(PORT);
        System.out.println("silk-bookstore: http://localhost:" + app.port());
        app.join();
    }
}
