package bookstore.repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import bookstore.domain.AuthorStat;
import bookstore.domain.Book;

/**
 * Queries over {@code books}, half of them fast by design and half slow by design.
 *
 * <p>{@link #findById} and {@link #count} use the primary key and H2's row
 * count, so they answer in well under a millisecond. {@link #search} and
 * {@link #authorStats} have no index to lean on and read all 200,000 rows;
 * {@link #sleep} does no work at all and still takes its time. That contrast is
 * the whole point of this example.
 */
public class BookRepository {

    private static final RowMapper<Book> BOOK = DataClassRowMapper.newInstance(Book.class);
    private static final RowMapper<AuthorStat> AUTHOR_STAT =
            DataClassRowMapper.newInstance(AuthorStat.class);

    // The columns are aliased to the record component names on purpose.
    // DataClassRowMapper asks the ResultSet for "publishedYear" first and only
    // falls back to "published_year" when that lookup fails — and a failed
    // lookup is an exception H2 both raises and writes to its trace file, once
    // per row. Naming the column what the record calls it keeps that off the
    // hot path and out of ~/db/spider-sense/bookstore.trace.db.
    private static final String COLUMNS =
            "id, isbn, title, author, price, published_year as publishedYear, description";

    private final NamedParameterJdbcTemplate jdbc;

    public BookRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from books", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    /** Fast: the primary key. This is what spring-orders calls over HTTP. */
    public Optional<Book> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from books where id = :id",
                Map.of("id", id), BOOK).stream().findFirst();
    }

    /**
     * Fast: the primary key again, for a whole set at once. This is what a caller
     * that would otherwise call {@link #findById} in a loop should ask for.
     *
     * <p>The number of parameters varies with the number of ids, so the statement
     * text does too and an observability tool groups those calls separately. That is
     * the price of an {@code IN} list, and it is still one round trip instead of N.
     */
    public List<Book> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select " + COLUMNS + " from books where id in (:ids) order by id",
                Map.of("ids", ids), BOOK);
    }

    /** Fast: an ordered window over the primary key. */
    public List<Book> page(int limit, int offset) {
        return jdbc.query("select " + COLUMNS + " from books order by id limit :limit offset :offset",
                Map.of("limit", limit, "offset", offset), BOOK);
    }

    /**
     * Slow on purpose: a leading wildcard over two unindexed columns, so every
     * row is read and every description is compared. Around 300 ms against the
     * seeded 200,000 rows.
     *
     * <p>The ordering is part of that on purpose too. {@code order by id} would
     * let H2 walk the primary key and stop at the twenty-first match — for a
     * common word that is the first few hundred rows and under a millisecond,
     * which is not the query this example is here to show. Ordering by an
     * unindexed column makes it collect every match before it can answer, so the
     * scan is the whole table whatever the caller searched for.
     */
    public List<Book> search(String query, int limit, int offset) {
        return jdbc.query("""
                select id, isbn, title, author, price, published_year as publishedYear,
                       description
                from books
                where lower(title) like :pattern
                   or lower(description) like :pattern
                order by published_year desc, id
                limit :limit offset :offset
                """,
                Map.of("pattern", "%" + query.toLowerCase(Locale.ROOT) + "%",
                        "limit", limit, "offset", offset),
                BOOK);
    }

    /**
     * Slow on purpose: a group-by over the unindexed {@code author} column,
     * which means a full scan plus a sort of 200,000 rows.
     */
    public List<AuthorStat> authorStats(int limit) {
        return jdbc.query("""
                select author,
                       count(*)   as bookCount,
                       avg(price) as avgPrice
                from books
                group by author
                order by count(*) desc, author
                limit :limit
                """, Map.of("limit", limit), AUTHOR_STAT);
    }

    /**
     * Slow by decree: the {@code SLEEP} alias registered in
     * {@link bookstore.service.Seeder}, so the stats endpoint is over the
     * threshold even on a machine where the scan above happens to be quick.
     */
    public void sleep(long millis) {
        jdbc.query("select sleep(:millis)", Map.of("millis", millis), rs -> null);
    }
}
