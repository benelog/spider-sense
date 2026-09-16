package bookstore.web;

import java.util.Map;

import bookstore.service.BookService;
import net.benelog.spidersilk.Handler;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

/** The home page: the three counts, and links to everything slow. */
public class HomeAction implements Handler {

    private final BookService books;

    public HomeAction(BookService books) {
        this.books = books;
    }

    @Override
    public WebResponse handle(WebRequest req) {
        return WebResponse.template("home", Map.of(
                "bookCount", books.bookCount(),
                "reviewCount", books.reviewCount(),
                "authorCount", books.authorCount()));
    }
}
