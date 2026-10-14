package bookstore.web;

import java.util.List;
import java.util.Objects;

import bookstore.domain.AuthorStat;
import bookstore.domain.Book;
import bookstore.domain.Review;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;

/**
 * The wire format of the JSON API, written by hand in one place.
 *
 * <p>They live in the web layer rather than on the records, so
 * {@code bookstore.domain} needs no import from the framework.
 */
final class Codecs {

    private Codecs() {
    }

    static final JsonWriter<Book> BOOK = JsonWriter.tree(book -> Json.object()
            .put("id", book.id())
            .put("isbn", book.isbn())
            .put("title", book.title())
            .put("author", book.author())
            .put("price", book.price())
            .put("publishedYear", book.publishedYear())
            .put("description", book.description()));

    static final JsonWriter<List<Book>> BOOKS = JsonWriter.list(BOOK);

    static final JsonWriter<AuthorStat> AUTHOR_STAT = JsonWriter.tree(stat -> Json.object()
            .put("author", stat.author())
            .put("bookCount", stat.bookCount())
            .put("avgPrice", Math.round(stat.avgPrice() * 100) / 100.0));

    static final JsonWriter<List<AuthorStat>> AUTHOR_STATS = JsonWriter.list(AUTHOR_STAT);

    // Only a review that has been inserted is ever written out, and the insert returns its id.
    static final JsonWriter<Review> REVIEW = JsonWriter.tree(review -> Json.object()
            .put("id", Objects.requireNonNull(review.id(), "a review that has been inserted has an id"))
            .put("bookId", review.bookId())
            .put("authorId", review.authorId())
            .put("rating", review.rating())
            .put("body", review.body()));

    /** The body of {@code POST /api/reviews}. */
    record NewReview(long bookId, long authorId, int rating, String body) {
    }

    static final JsonReader<NewReview> NEW_REVIEW = JsonReader.object(json -> new NewReview(
            json.getLong("bookId"),
            json.getLong("authorId", 1),
            (int) json.getLong("rating"),
            json.getString("body", "")));
}
