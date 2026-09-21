package bookstore.domain;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One page of the book list.
 *
 * <p>{@code total} is -1 for a search: counting the matches of an unindexed
 * {@code like '%...%'} would scan the table a second time, so the list asks for
 * one row more than it shows and reports {@code hasNext} instead.
 */
public record BookPage(List<Book> books, long total, int page, int pageSize, @Nullable String query,
                       boolean hasNext) {

    public int nextPage() {
        return page + 1;
    }

    public int previousPage() {
        return page - 1;
    }

    public boolean hasPrevious() {
        return page > 1;
    }
}
