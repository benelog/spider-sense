package bookstore.domain;

import java.time.LocalDateTime;

/** One row of {@code reviews}. The reviewer is an {@code authors} id, which is what makes the book page N+1. */
public record Review(
        Long id,
        Long bookId,
        Long authorId,
        int rating,
        String body,
        LocalDateTime createdAt) {
}
