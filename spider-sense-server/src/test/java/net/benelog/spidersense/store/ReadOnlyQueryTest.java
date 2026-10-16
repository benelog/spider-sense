package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
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

    /**
     * A double the database computed as NaN or infinite is its name, which the JSON and the text
     * renderings both carry, so a null cell is only ever a SQL NULL.
     */
    @Test
    void aNonFiniteDoubleIsItsNameAndNeverANull() {
        assertThat(row("SELECT SQRT(-1), POWER(10, 400), -POWER(10, 400), CAST(0 AS DOUBLE),"
                + " ARRAY[SQRT(-1)], CAST(SQRT(-1) AS REAL)"))
                .containsExactly("NaN", "Infinity", "-Infinity", 0.0, List.of("NaN"), "NaN");
    }

    /**
     * An ARRAY and a row value are lists of their elements and a JSON value is its text, not H2's
     * object dump, whose counter made the same statement answer different bytes on every call.
     */
    @Test
    void anArrayARowAndAJsonValueAreTheirElementsAndTheirText() {
        String statement = "SELECT ARRAY['b', 'a'], (1, 'x'), JSON '{\"a\":1}', ARRAY[ARRAY[1, 2], ARRAY[3]],"
                + " CAST(NULL AS INTEGER ARRAY), ARRAY[NULL, 2]";

        assertThat(row(statement)).containsExactly(List.of("b", "a"), List.of(1L, "x"), "{\"a\":1}",
                List.of(List.of(1L, 2L), List.of(3L)), null, Arrays.asList(null, 2L));
        assertThat(row(statement)).isEqualTo(row(statement));
    }

    /** A JSON element of an ARRAY is its text as well, as a JSON column's value is, not its bytes in hex. */
    @Test
    void aJsonElementOfAnArrayIsItsText() {
        assertThat(row("SELECT ARRAY[JSON '{\"a\":1}', JSON '[2]'], ARRAY[ARRAY[JSON '3']],"
                + " (SELECT ARRAY_AGG(JSON_OBJECT('n': X) ORDER BY X) FROM (VALUES 1, 2) T(X))"))
                .containsExactly(List.of("{\"a\":1}", "[2]"), List.of(List.of("3")),
                        List.of("{\"n\":1}", "{\"n\":2}"));
    }

    /**
     * A lone surrogate, typed into a literal or made by the query, is U+FFFD in the cell, which the
     * JSON and the text renderings then both show.
     */
    @Test
    void aLoneSurrogateInTheStatementOrACellIsTheReplacementCharacter() {
        assertThat(row("SELECT 'a\ud800b', CHAR(55296), ARRAY[CHAR(56320)], 'x😀'"))
                .containsExactly("a�b", "�", List.of("�"), "x😀");
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
