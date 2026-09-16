package bookstore.web;

import java.util.Map;

import bookstore.domain.BookDetail;
import bookstore.domain.BookPage;
import bookstore.service.BookService;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

/** The two HTML pages over books. */
public class BookController {

    private static final int PAGE_SIZE = 20;

    private final BookService books;

    public BookController(BookService books) {
        this.books = books;
    }

    /** The list. With {@code ?q=} it is the unindexed scan over every row. */
    public WebResponse listBooks(WebRequest req) {
        String query = req.queryParamOrNull("q");
        int page = Math.max(1, req.queryParam("page", Integer::parseInt, 1));
        BookPage result = books.list(query, page, PAGE_SIZE);
        return WebResponse.template("books", Map.of("page", result));
    }

    /** One book and its reviews — the N+1 page. */
    public WebResponse showBook(WebRequest req) {
        BookDetail detail = books.detail(req.pathParamLong("id"));
        return WebResponse.template("book", Map.of("detail", detail));
    }
}
