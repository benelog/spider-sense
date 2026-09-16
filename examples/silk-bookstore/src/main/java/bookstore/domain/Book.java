package bookstore.domain;

/** One row of {@code books}. Mapped by column name, so {@code published_year} lands on {@code publishedYear}. */
public record Book(
        Long id,
        String isbn,
        String title,
        String author,
        double price,
        int publishedYear,
        String description) {
}
