package bookstore;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import javax.sql.DataSource;

import bookstore.service.Seeder;
import bookstore.web.ApiController;
import bookstore.web.BookController;
import bookstore.web.Tracing;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.TemplateRenderer;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * A small bookstore whose job is to behave badly in interesting ways, so that
 * Spider Sense has something to show: a full scan over 200,000 rows, an N+1
 * page, a query that sleeps, an endpoint that sleeps without a query, one that
 * throws, one that answers 404 on purpose, and one that rejects bad input.
 */
public class BookstoreApp {

    public static final int PORT = 8081;

    /** The database file, shared with anything else that opens it thanks to AUTO_SERVER. */
    public static final String JDBC_URL = "jdbc:h2:~/db/spider-sense/bookstore;AUTO_SERVER=TRUE";

    public static void main(String[] args) {
        DataSource dataSource = JdbcConnectionPool.create(JDBC_URL, "sa", "");
        new Seeder(dataSource).seed();

        App app = createApp(dataSource)
                .templates(templates(args))
                .start(PORT);
        System.out.println("silk-bookstore: http://localhost:" + app.port());
        app.join();
    }

    /**
     * jte's two modes. Production (no flag) renders the classes the build's
     * {@code generateJte} task compiled; {@code --dev} reads the .jte files from
     * the source tree, recompiling a template whose file changed, so an edit
     * shows on refresh. Run it as
     * {@code ./gradlew :examples:silk-bookstore:run --args=--dev}, whose working
     * directory makes the relative path below resolve.
     */
    static TemplateRenderer templates(String[] args) {
        if (Arrays.asList(args).contains("--dev")) {
            return new JteTemplates(
                    new DirectoryCodeResolver(Path.of("src/main/resources/jte")));
        }
        return new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html));
    }

    /** The app, with no server started: what the tests take. */
    public static App createApp(DataSource dataSource) {
        BookstoreContext context = new BookstoreContext(dataSource);
        App app = new App();

        app.gzip();

        // A bad rating, or a body missing a key, is the caller's mistake: 400.
        // Anything else is a 500 that Tracing records on the span.
        app.exception(IllegalArgumentException.class, (req, e) ->
                problem(req, HttpStatus.BAD_REQUEST, e.getMessage()));

        app.error(HttpStatus.NOT_FOUND, req -> problem(req, HttpStatus.NOT_FOUND,
                Objects.requireNonNullElse(req.errorMessage(), "Not found")));

        registerRoutes(app, context);

        // After the routes, because it reads app.routes(): see Tracing.
        Tracing.install(app);

        app.requestLogger((req, completion) -> System.out.printf("%-4s %-40s %3d %5d ms%n",
                req.method(), path(req), completion.statusCode(), completion.took().toMillis()));

        return app;
    }

    /** Every route this application answers, in one readable list. */
    static void registerRoutes(App app, BookstoreContext context) {
        app.get("/", "Home: counts and links to everything slow", context.homeAction());

        BookController booksPage = context.bookController();
        app.get("/books", "Paginated list; ?q= runs the slow unindexed search",
                booksPage::listBooks);
        app.get("/books/{id}", "One book and its reviews, N+1 by design",
                booksPage::showBook);

        ApiController api = context.apiController();
        app.get("/api/health", "Liveness", api::health);
        app.get("/api/slow", "Sleeps ?ms= (default 800) in Java, no query", api::slow);
        app.get("/api/flaky", "Fails about 30% of the time", api::flaky);
        app.post("/api/reviews", "Adds a review; rating outside 1..5 is a 400", api::addReview);

        // The literal routes register before /api/books/{id}: registration order
        // breaks ties, so "search" and "stats" must not be read as an id.
        app.path("/api/books", group -> {
            group.get("/search", "Slow search over title and description", api::searchBooks);
            group.get("/stats", "Slow aggregate report, plus select sleep(300)", api::stats);
            group.get("/{id}", "One book as JSON, fast", api::showBook);
            group.get("/{id}/missing", "Always 404: a 4xx that is not an error", api::missing);
        });
    }

    /** A JSON problem for the API, plain text for a page. */
    private static WebResponse problem(WebRequest req, HttpStatus status, String message) {
        if (req.path().startsWith("/api/")) {
            return WebResponse.json(Json.obj().put("error", message)).status(status);
        }
        return WebResponse.text(message).status(status);
    }

    /** The path with its query string, so the terminal shows which search was slow. */
    private static String path(WebRequest req) {
        String query = req.queryString();
        return query == null ? req.path() : req.path() + "?" + query;
    }
}
