package bookstore.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SimplePropertySqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import bookstore.domain.Review;

public class ReviewRepository {

    // Written by hand for the reason BookRepository gives: no failed column
    // lookups, so nothing in H2's trace file.
    private static final RowMapper<Review> MAPPER = (rs, row) -> new Review(
            rs.getLong("id"),
            rs.getLong("book_id"),
            rs.getLong("author_id"),
            rs.getInt("rating"),
            rs.getString("body"),
            rs.getObject("created_at", LocalDateTime.class));

    private final NamedParameterJdbcOperations jdbc;
    private final SimpleJdbcInsert insert;

    public ReviewRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.insert = new SimpleJdbcInsert(dataSource)
                .withTableName("reviews")
                .usingGeneratedKeyColumns("id");
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from reviews", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    public List<Review> findByBookId(long bookId) {
        return jdbc.query("""
                select id, book_id, author_id, rating, body, created_at
                from reviews
                where book_id = :bookId
                order by id
                """, Map.of("bookId", bookId), MAPPER);
    }

    public Review insert(Review review) {
        long id = insert.executeAndReturnKey(new SimplePropertySqlParameterSource(review)).longValue();
        return new Review(id, review.bookId(), review.authorId(), review.rating(),
                review.body(), review.createdAt());
    }
}
