package bookstore.web;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import bookstore.domain.Book;
import bookstore.domain.Review;
import bookstore.service.BookService;
import bookstore.service.ReviewService;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * The JSON API, which is what the load generator and spring-orders call.
 *
 * <p>Each handler is one shape of thing an APM should be able to tell apart:
 * fast, slow because of a query, slow with no query at all, failing, failing in
 * a way that is not a server error, and rejecting bad input.
 */
public class ApiController {

    /** How often {@link #flaky} fails. High enough that a minute of traffic shows a trend. */
    private static final double FAILURE_RATE = 0.30;

    private static final long DEFAULT_SLOW_MS = 800;
    private static final int SEARCH_LIMIT = 20;

    private final BookService books;
    private final ReviewService reviews;

    public ApiController(BookService books, ReviewService reviews) {
        this.books = books;
        this.reviews = reviews;
    }

    /** Fast: one primary key lookup. spring-orders calls this over HTTP. */
    public WebResponse showBook(WebRequest req) {
        return WebResponse.json(books.book(req.pathParamLong("id")), Codecs.BOOK);
    }

    /** Slow: the same unindexed {@code like '%...%'} the HTML search runs. */
    public WebResponse searchBooks(WebRequest req) {
        List<Book> found = books.search(req.param("q"), SEARCH_LIMIT);
        return WebResponse.json(found, Codecs.BOOKS);
    }

    /** Slow: a group-by over every row, and then a {@code select sleep(300)}. */
    public WebResponse stats(WebRequest req) {
        return WebResponse.json(books.stats(), Codecs.AUTHOR_STATS);
    }

    /** Slow with no database in it: a slow URL that is not a slow query. */
    public WebResponse slow(WebRequest req) {
        long millis = req.queryParam("ms", Long::parseLong, DEFAULT_SLOW_MS);
        books.slow(millis);
        return WebResponse.json(Json.obj().put("sleptMs", millis));
    }

    /** Fails about three times in ten, with an exception rather than a status. */
    public WebResponse flaky(WebRequest req) {
        if (ThreadLocalRandom.current().nextDouble() < FAILURE_RATE) {
            throw new IllegalStateException("Inventory service unavailable");
        }
        return WebResponse.json(Json.obj().put("inventory", "ok").put("warehouses", 3));
    }

    /**
     * Always 404, and deliberately not an error: a 4xx a caller asked for is a
     * normal answer, and an APM that counts it as a failure is reporting noise.
     */
    public WebResponse missing(WebRequest req) {
        throw new HttpException(HttpStatus.NOT_FOUND,
                "No such resource for book " + req.pathParamLong("id"));
    }

    /** The write path. A rating outside 1..5 is an IllegalArgumentException, so 400. */
    public WebResponse addReview(WebRequest req) {
        Codecs.NewReview body = req.bodyJson(Codecs.NEW_REVIEW);
        Review review = reviews.add(body.bookId(), body.authorId(), body.rating(), body.body());
        return WebResponse.json(review, Codecs.REVIEW)
                .status(HttpStatus.CREATED)
                .header("Location", "/api/books/" + review.bookId());
    }

    public WebResponse health(WebRequest req) {
        return WebResponse.json(Json.obj().put("status", "ok"));
    }
}
