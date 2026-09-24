package bookstore.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import bookstore.domain.BookNotFoundException;
import bookstore.domain.Review;
import bookstore.repository.BookRepository;
import bookstore.repository.ReviewRepository;

/** The one write path: posting a review, inside a transaction. */
public class ReviewService {

    private final ReviewRepository reviews;
    private final BookRepository books;
    private final Transactions tx;

    public ReviewService(ReviewRepository reviews, BookRepository books, Transactions tx) {
        this.reviews = reviews;
        this.books = books;
        this.tx = tx;
    }

    /**
     * Validates, then inserts. A rating outside 1..5 throws
     * {@link IllegalArgumentException}, which the app maps to 400 — a failed
     * request that is the caller's fault, not the server's.
     */
    public Review add(long bookId, long authorId, int rating, String body) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5, was " + rating);
        }
        return tx.write(() -> {
            if (books.findById(bookId).isEmpty()) {
                throw new BookNotFoundException(bookId);
            }
            return reviews.insert(new Review(null, bookId, authorId, rating, body,
                    LocalDateTime.now(ZoneId.systemDefault())));
        });
    }
}
