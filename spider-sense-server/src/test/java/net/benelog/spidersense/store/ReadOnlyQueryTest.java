package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/** The read-only SQL behind {@code POST /api/sql} and the MCP {@code sql} tool (api.adoc#sql). */
class ReadOnlyQueryTest {

    private final Database database = Database.open(TestStore.memoryUrl(), null);
    private final ReadOnlyQuery sql = new ReadOnlyQuery(database);

    @AfterEach
    void close() {
        database.close();
    }

    private List<Object> row(String statement) {
        return sql.run(statement, 10).rows().get(0);
    }

    /** A cell is the number the database computed, not that number modulo 2^64. */
    @Test
    void aWholeDecimalPastALongIsADoubleRatherThanAWrappedLong() {
        assertThat(row("SELECT CAST(42 AS DECIMAL(30, 0)), 1e19, 1e20, 12.5")).containsExactly(42L, 1e19, 1e20, 12.5);
        assertThat(row("SELECT 1e400")).containsExactly("1E+400");
    }
}
