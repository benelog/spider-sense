package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/** The transaction helper every writing path of the store goes through. */
class SqlTest {

    private final Database database = Database.open(TestStore.memoryUrl(), null);

    @AfterEach
    void close() {
        database.close();
    }

    private long marks() {
        return database.sql().count("SELECT COUNT(*) FROM mark", List.of());
    }

    private static int insertMark(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("INSERT INTO mark (at_ms, name) VALUES (1, 'before')");
        }
    }

    @Test
    void workThatReturnsIsCommitted() {
        int inserted = database.sql().transaction(SqlTest::insertMark, "insert");

        assertThat(inserted).isEqualTo(1);
        assertThat(marks()).isEqualTo(1);
    }

    /** An Error too: the statements run before it must not be committed by the reset of auto-commit. */
    @Test
    void workThatThrowsAnErrorIsRolledBack() {
        assertThatThrownBy(() -> database.sql().transaction(connection -> {
            insertMark(connection);
            throw new StackOverflowError("nested too deep");
        }, "insert")).isInstanceOf(StackOverflowError.class);

        assertThat(marks()).isZero();
    }

    @Test
    void theConnectionsAutoCommitIsSetBackToWhatItWas() throws SQLException {
        try (Connection connection = database.sql().connection()) {
            connection.setAutoCommit(true);
            Sql.inTransaction(connection, SqlTest::insertMark);
            assertThat(connection.getAutoCommit()).isTrue();

            connection.setAutoCommit(false);
            assertThatThrownBy(() -> Sql.inTransaction(connection, c -> {
                insertMark(c);
                throw new IllegalStateException("refused");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(connection.getAutoCommit()).isFalse();
            connection.rollback();
        }

        assertThat(marks()).isEqualTo(1);
    }
}
