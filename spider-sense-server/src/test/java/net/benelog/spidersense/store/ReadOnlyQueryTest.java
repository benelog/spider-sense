package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** A second statement cannot hide behind a dollar-quoted string, where a quote means nothing. */
    @Test
    void aDollarQuotedStringHidesNoSecondStatement() {
        assertThatThrownBy(() -> ReadOnlyQuery.guard("SELECT $$'$$; SET QUERY_TIMEOUT 0"))
                .isInstanceOf(ReadOnlyQuery.Refused.class).hasMessageContaining("one statement");
        assertThat(row("SELECT $$it's; fine$$")).containsExactly("it's; fine");
    }

    /** FOR UPDATE reads, but locks rows the writer would wait on past its lock timeout. */
    @Test
    void forUpdateIsRefused() {
        assertThatThrownBy(() -> ReadOnlyQuery.guard("SELECT * FROM span FOR  update"))
                .isInstanceOf(ReadOnlyQuery.Refused.class).hasMessageContaining("FOR UPDATE");
        assertThat(sql.run("SELECT 'for update' AS words", 10).rows()).hasSize(1);
    }

    /** H2 ends a line comment at a carriage return, so a second statement cannot hide past one. */
    @Test
    void aLineCommentEndsAtACarriageReturn() {
        assertThatThrownBy(() -> ReadOnlyQuery.guard("SELECT 1 -- x\r; SET @x = 42"))
                .isInstanceOf(ReadOnlyQuery.Refused.class).hasMessageContaining("one statement");
        assertThat(row("SELECT 1 -- x\r")).containsExactly(1L);
    }

    /** Any space H2 separates words with separates FOR from UPDATE for the guard too. */
    @Test
    void forUpdateIsRefusedAcrossAUnicodeSpace() {
        for (String space : List.of("\u00A0", "\u2003", "\u3000", "\u2028")) {
            assertThatThrownBy(() -> ReadOnlyQuery.guard("SELECT * FROM span FOR" + space + "UPDATE"))
                    .as("U+%04X", (int) space.charAt(0))
                    .isInstanceOf(ReadOnlyQuery.Refused.class).hasMessageContaining("FOR UPDATE");
        }
    }
}
