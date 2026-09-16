package bookstore.domain;

/** A review with its reviewer's name resolved — one extra query per review, on purpose. */
public record ReviewView(Long id, int rating, String body, String authorName) {
}
