package worker;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The pool and the schema.
 *
 * <p>The pool is three connections wide on purpose. Eight archive slices asking
 * three connections for a second each is what makes the pool the bottleneck,
 * and a pool that is the bottleneck is what {@code pool-exhausted} is about.
 * The two-second connection timeout is short for the same reason: the slices at
 * the back of the queue give up instead of waiting all afternoon.
 */
public final class Database {

    public static final int MAX_POOL_SIZE = 3;
    public static final long CONNECTION_TIMEOUT_MS = 2_000;

    private Database() {
    }

    public static HikariDataSource pool(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setPoolName("worker");
        return new HikariDataSource(config);
    }

    /**
     * Creates the two tables, and deliberately no index on {@code events.account_id}.
     *
     * <p>Every reconcile scans the whole table because of that absence; putting
     * the index back would take the point of this example away.
     */
    public static void createSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists accounts (
                        id          int auto_increment primary key,
                        name        varchar(120) not null,
                        balance     decimal(12,2) not null,
                        reminded_at timestamp null
                    )
                    """);
            statement.execute("""
                    create table if not exists events (
                        id          int auto_increment primary key,
                        account_id  int not null,
                        amount      decimal(12,2) not null,
                        kind        varchar(20) not null,
                        note        varchar(200) not null,
                        occurred_at timestamp not null
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the worker schema", e);
        }
    }
}
