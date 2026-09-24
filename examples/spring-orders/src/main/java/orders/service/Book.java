package orders.service;

/**
 * A book as silk-bookstore's {@code /api/books} answers it, field for field.
 * Spring Boot's Jackson ignores a field it does not know, so the bookstore can add one
 * without breaking this side.
 */
public record Book(
        Long id,
        String isbn,
        String title,
        String author,
        double price,
        int publishedYear,
        String description) {
}
