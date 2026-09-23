package bookstore.domain;

/** One row of {@code books}. */
public record Book(
        Long id,
        String isbn,
        String title,
        String author,
        double price,
        int publishedYear,
        String description) {
}
