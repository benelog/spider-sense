package bookstore;

import java.nio.file.Path;
import java.util.Objects;

import javax.sql.DataSource;

import bookstore.domain.BookNotFoundException;
import bookstore.repository.AuthorRepository;
import bookstore.repository.BookRepository;
import bookstore.repository.ReviewRepository;
import bookstore.service.BookService;
import bookstore.service.ReviewService;
import bookstore.service.Transactions;
import bookstore.web.ApiController;
import bookstore.web.BookController;
import bookstore.web.HomeAction;
import bookstore.web.Tracing;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.TemplateRenderer;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * The object graph, assembled by calling constructors, and the App its
 * handlers are served by. No DI container and no reflection: the wiring, the
 * exception handlers, the route table, and the templates are all right here.
 * BookstoreApp picks the database and whether to run in dev mode.
 */
public class BookstoreContext {

    private final HomeAction homeAction;
    private final BookController bookController;
    private final ApiController apiController;
    private final boolean devMode;

    /** A context on the precompiled templates, as production and the tests run it. */
    public BookstoreContext(DataSource dataSource) {
        this(dataSource, false);
    }

    public BookstoreContext(DataSource dataSource, boolean devMode) {
        this.devMode = devMode;
        Transactions tx = new Transactions(dataSource);

        BookRepository bookRepository = new BookRepository(dataSource);
        ReviewRepository reviewRepository = new ReviewRepository(dataSource);
        AuthorRepository authorRepository = new AuthorRepository(dataSource);

        BookService bookService = new BookService(bookRepository, reviewRepository,
                authorRepository, tx);
        ReviewService reviewService = new ReviewService(reviewRepository, bookRepository, tx);

        this.homeAction = new HomeAction(bookService);
        this.bookController = new BookController(bookService);
        this.apiController = new ApiController(bookService, reviewService);
    }

    /**
     * jte's two modes. Production renders the classes the build's
     * {@code generateJte} task compiled; dev mode reads the .jte files from
     * the source tree, recompiling a template whose file changed, so an edit
     * shows on refresh. Run it as
     * {@code ./gradlew :examples:silk-bookstore:run --args=--dev}, whose working
     * directory makes the relative path below resolve.
     */
    private TemplateRenderer templates() {
        if (devMode) {
            return new JteTemplates(
                    new DirectoryCodeResolver(Path.of("src/main/resources/jte")));
        }
        return new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html));
    }

    /**
     * The App with its response-wide concerns, exception handlers, and routes.
     * Tests take it as it is; {@link #start(int)} starts it on a port.
     */
    App createApp() {
        App app = new App();
        app.templates(templates());

        app.gzip();

        // A bad rating, or a body missing a key, is the caller's mistake: 400.
        // Anything else is the framework's 500, whose body is filled in below and
        // whose exception the request logger hands to Tracing.
        app.exception(IllegalArgumentException.class, (req, e) ->
                problem(req, HttpStatus.BAD_REQUEST,
                        Objects.requireNonNullElse(e.getMessage(), "Bad request")));
        // The services know nothing of HTTP: a book that is not there is their
        // own exception, and it is here that it becomes a 404.
        app.exception(BookNotFoundException.class, (req, e) ->
                problem(req, HttpStatus.NOT_FOUND,
                        Objects.requireNonNullElse(e.getMessage(), "Not found")));

        // A 404 thrown as HttpException is a status, not a failure: it comes
        // here for its body without passing through any exception handler.
        app.error(HttpStatus.NOT_FOUND, req -> problem(req, HttpStatus.NOT_FOUND,
                Objects.requireNonNullElse(req.errorMessage(), "Not found")));
        app.error(HttpStatus.INTERNAL_SERVER_ERROR, req -> problem(req,
                HttpStatus.INTERNAL_SERVER_ERROR,
                Objects.requireNonNullElse(req.errorMessage(), "Internal server error")));

        registerRoutes(app);

        app.requestLogger((req, completion) -> {
            Tracing.record(completion);
            System.out.printf("%-4s %-40s %3d %5d ms%n",
                    req.method(), path(req), completion.statusCode(), completion.took().toMillis());
        });

        return app;
    }

    /** Starts {@link #createApp()} on the given port. */
    public App start(int port) {
        return createApp().start(port);
    }

    /** Every route this application answers, in one readable list. */
    private void registerRoutes(App app) {
        app.get("/", "Home: counts and links to everything slow", homeAction);

        app.get("/books", "Paginated list; ?q= runs the slow unindexed search",
                bookController::listBooks);
        app.get("/books/{id}", "One book and its reviews, N+1 by design",
                bookController::showBook);

        app.get("/api/health", "Liveness", apiController::health);
        app.get("/api/slow", "Sleeps ?ms= (default 800) in Java, no query", apiController::slow);
        app.get("/api/flaky", "Fails about 30% of the time", apiController::flaky);
        app.post("/api/reviews", "Adds a review; rating outside 1..5 is a 400",
                apiController::addReview);

        // /api/books?ids=1,2,3 is its own path rather than a route of the group below,
        // because it has no segment after /api/books at all.
        app.get("/api/books", "Several books in one call: ?ids=1,2,3", apiController::booksByIds);

        // The literal routes register before /api/books/{id}: registration order
        // breaks ties, so "search" and "stats" must not be read as an id.
        app.path("/api/books", group -> {
            group.get("/search", "Slow search over title and description",
                    apiController::searchBooks);
            group.get("/stats", "Slow aggregate report, plus select sleep(300)",
                    apiController::stats);
            group.get("/{id}", "One book as JSON, fast", apiController::showBook);
            group.get("/{id}/missing", "Always 404: a 4xx that is not an error",
                    apiController::missing);
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
