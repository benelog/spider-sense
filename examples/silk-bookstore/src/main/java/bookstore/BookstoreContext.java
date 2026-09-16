package bookstore;

import javax.sql.DataSource;

import bookstore.repository.AuthorRepository;
import bookstore.repository.BookRepository;
import bookstore.repository.ReviewRepository;
import bookstore.service.BookService;
import bookstore.service.ReviewService;
import bookstore.service.Transactions;
import bookstore.web.ApiController;
import bookstore.web.BookController;
import bookstore.web.HomeAction;

/**
 * The object graph, assembled by calling constructors. No DI container and no
 * reflection: the whole wiring of the application is these twelve lines.
 */
public class BookstoreContext {

    private final HomeAction homeAction;
    private final BookController bookController;
    private final ApiController apiController;

    public BookstoreContext(DataSource dataSource) {
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

    public HomeAction homeAction() {
        return homeAction;
    }

    public BookController bookController() {
        return bookController;
    }

    public ApiController apiController() {
        return apiController;
    }
}
