package bookstore.domain;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

/** One row of {@code reviews}. The reviewer is an {@code authors} id, which is what makes the book page N+1. */
public record Review(
        @Nullable Long id,
        Long bookId,
        Long authorId,
        int rating,
        String body,
        LocalDateTime createdAt) {
}
