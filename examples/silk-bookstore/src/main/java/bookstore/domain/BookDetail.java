package bookstore.domain;

import java.util.List;

/** What the book page shows: the book plus its reviews with reviewer names. */
public record BookDetail(Book book, List<ReviewView> reviews) {
}
