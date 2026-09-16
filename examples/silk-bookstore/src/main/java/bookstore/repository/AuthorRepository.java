package bookstore.repository;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class AuthorRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuthorRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from authors", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * One name, by primary key. Fast in itself — the book page calls it once per
     * review, which is what makes that page an N+1.
     */
    public String findNameById(long id) {
        return jdbc.query("select name from authors where id = :id", Map.of("id", id),
                        (rs, row) -> rs.getString("name"))
                .stream().findFirst().orElse("Unknown");
    }
}
