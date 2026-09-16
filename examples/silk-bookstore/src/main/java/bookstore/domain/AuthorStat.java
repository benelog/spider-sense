package bookstore.domain;

/** One line of the aggregate report: books and average price for one author. */
public record AuthorStat(String author, long bookCount, double avgPrice) {
}
