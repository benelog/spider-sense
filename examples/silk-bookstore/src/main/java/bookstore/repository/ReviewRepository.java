package bookstore.repository;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SimplePropertySqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import bookstore.domain.Review;

public class ReviewRepository {

    private static final RowMapper<Review> MAPPER = DataClassRowMapper.newInstance(Review.class);

    private final NamedParameterJdbcTemplate jdbc;
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
                select id, book_id as bookId, author_id as authorId, rating, body,
                       created_at as createdAt
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
