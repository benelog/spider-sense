package bookstore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import bookstore.domain.AuthorStat;
import bookstore.domain.Book;
import bookstore.domain.BookDetail;
import bookstore.domain.BookPage;
import bookstore.domain.Review;
import bookstore.domain.ReviewView;
import bookstore.repository.AuthorRepository;
import bookstore.repository.BookRepository;
import bookstore.repository.ReviewRepository;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import org.jspecify.annotations.Nullable;

/**
 * The reading side of the bookstore, including the parts that are slow on purpose.
 *
 * <p>Nothing here is an accident: {@link #detail} runs one query per review
 * where a join would do, {@link #stats} scans the table and then sleeps, and
 * {@link #slow} takes its time without touching the database at all. Each is an
 * example of a different shape of problem for an observability tool to point at.
 */
public class BookService {

    /** How long the stats report sleeps in SQL, so it is always over the slow threshold. */
    private static final long STATS_SLEEP_MS = 300;

    private static final int STATS_LIMIT = 20;

    private final BookRepository books;
    private final ReviewRepository reviews;
    private final AuthorRepository authors;
    private final Transactions tx;

    public BookService(BookRepository books, ReviewRepository reviews,
                       AuthorRepository authors, Transactions tx) {
        this.books = books;
        this.reviews = reviews;
        this.authors = authors;
        this.tx = tx;
    }

    public long bookCount() {
        return books.count();
    }

    public long reviewCount() {
        return reviews.count();
    }

    public long authorCount() {
        return authors.count();
    }

    /** Fast: a primary key lookup. This is the one spring-orders calls over HTTP. */
    public Book book(long id) {
        return books.findById(id)
                .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "No book " + id));
    }

    /**
     * Fast: the same lookup for a whole set. spring-orders calls this over HTTP
     * instead of calling {@link #book} once per order line.
     *
     * <p>An id the bookstore does not know is left out rather than a 404: the caller
     * asked about several books and one missing one is not an error.
     */
    public List<Book> books(List<Long> ids) {
        return books.findByIds(ids);
    }

    /**
     * One page of the list. Without a query this is an indexed window; with one
     * it is the unindexed {@code like '%...%'} over every row.
     */
    public BookPage list(@Nullable String query, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        if (query == null || query.isBlank()) {
            List<Book> rows = books.page(pageSize + 1, offset);
            return page(rows, books.count(), page, pageSize, null);
        }
        List<Book> rows = books.search(query, pageSize + 1, offset);
        return page(rows, -1, page, pageSize, query);
    }

    private BookPage page(List<Book> rows, long total, int page, int pageSize, @Nullable String query) {
        boolean hasNext = rows.size() > pageSize;
        List<Book> shown = hasNext ? rows.subList(0, pageSize) : rows;
        return new BookPage(List.copyOf(shown), total, page, pageSize, query, hasNext);
    }

    /** The slow search, as the JSON API serves it. */
    public List<Book> search(String query, int limit) {
        return books.search(query, limit, 0);
    }

    /**
     * The N+1: the book, then its reviews, then one query per review to put a
     * name on its author. Around twenty extra queries for a seeded book, where a
     * single join would have answered the same question.
     */
    public BookDetail detail(long id) {
        return tx.read(() -> {
            Book book = book(id);
            List<Review> rows = reviews.findByBookId(id);
            List<ReviewView> views = new ArrayList<>(rows.size());
            for (Review review : rows) {
                String name = authors.findNameById(review.authorId());   // one query, per review
                // A review read back from the database always carries its generated id.
                Long reviewId = Objects.requireNonNull(review.id(), "a stored review has an id");
                views.add(new ReviewView(reviewId, review.rating(), review.body(), name));
            }
            return new BookDetail(book, List.copyOf(views));
        });
    }

    /**
     * The aggregate report: a group-by over 200,000 unindexed rows, and then a
     * {@code select sleep(300)} so the endpoint is reliably over the threshold
     * even where the scan happens to be fast.
     */
    public List<AuthorStat> stats() {
        List<AuthorStat> stats = books.authorStats(STATS_LIMIT);
        books.sleep(STATS_SLEEP_MS);
        return stats;
    }

    /** Slow with no database in sight: the URL is slow, no query is. */
    public void slow(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
