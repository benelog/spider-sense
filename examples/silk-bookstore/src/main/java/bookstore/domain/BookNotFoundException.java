package bookstore.domain;

/** No book has the id asked for: the caller's mistake, which the web layer answers with 404. */
public class BookNotFoundException extends RuntimeException {

    private final long bookId;

    public BookNotFoundException(long bookId) {
        super("No book " + bookId);
        this.bookId = bookId;
    }

    public long bookId() {
        return bookId;
    }
}
