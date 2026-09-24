package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;

/**
 * Every Markdown table keeps its shape whatever text the application put in a
 * cell: a newline collapses, a bar is escaped (cli.adoc#text-rendering).
 */
class TextTest {

    private static final Window WINDOW = Window.of(1_000_000L, 1_060_000L);

    /** An H2 message as the error group keeps it, newline and all. */
    private static final String H2_MESSAGE =
            "Table \"X\" not found; SQL statement:\nSELECT a | b FROM x [42102-224]";

    /** The table rows of a rendering: every line that starts with a bar. */
    private static List<String> rows(String text) {
        List<String> rows = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("|")) {
                rows.add(line);
            }
        }
        return rows;
    }

    /** How many cells a row has, counting only the bars no backslash escapes. */
    private static int cells(String row) {
        int bars = 0;
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == '|' && (i == 0 || row.charAt(i - 1) != '\\')) {
                bars++;
            }
        }
        return bars - 1;
    }

    /** Every row of every table has as many cells as the header over it. */
    private static void assertTablesHoldTheirShape(String text) {
        int columns = -1;
        boolean inTable = false;
        for (String line : text.split("\n", -1)) {
            if (!line.startsWith("|")) {
                inTable = false;
                continue;
            }
            if (!inTable) {
                columns = cells(line);
                inTable = true;
                continue;
            }
            assertThat(cells(line)).as("cells in %s", line).isEqualTo(columns);
        }
    }

    @Test
    void anErrorMessageWithANewlineAndABarStaysInItsRow() {
        Stats.ErrorGroup group = new Stats.ErrorGroup("abcdefabcdef", "svc", "org.h2.Jdbc|Exception",
                H2_MESSAGE, 3, 1_000_000L, 1_050_000L,
                List.of(new Stats.EndpointCount("GET /a|b", 3)), null);

        String text = Text.errors(WINDOW, null, List.of(group), 3, false, new CodeFrames(null),
                "http://localhost:4000");

        assertTablesHoldTheirShape(text);
        assertThat(rows(text)).hasSize(3);
        assertThat(rows(text).get(2))
                .contains("org.h2.Jdbc\\|Exception")
                .contains("Table \"X\" not found; SQL statement: SELECT a \\| b FROM x [42102-224]")
                .contains("GET /a\\|b ×3");
    }

    @Test
    void compareErrorsCellsAreOneLineToo() {
        Compare.Comparison comparison = new Compare.Comparison(WINDOW, WINDOW,
                Stats.Totals.EMPTY, Stats.Totals.EMPTY, List.of(), List.of(),
                List.of(new Compare.ErrorDiff("abcdefabcdef", "svc", "java.lang.Ill|egal", H2_MESSAGE,
                        0, 2, "new")));

        String text = Text.compare(comparison, null, false);

        assertTablesHoldTheirShape(text);
        assertThat(text).contains("| new | abcdefabcdef | java.lang.Ill\\|egal | Table \"X\" not found;"
                + " SQL statement: SELECT a \\| b FROM x [42102-224] | 0 | 2 |");
    }

    @Test
    void aNoteKeepsTheMarksAndTheAcksTablesInShape() {
        String marks = Text.marks(List.of(new Marks.Mark(1, 1_000_000L, "before", "svc", "a|b\nc")));
        assertTablesHoldTheirShape(marks);
        assertThat(rows(marks).get(2)).endsWith("| before | svc | a\\|b c |");

        String acks = Text.acks(List.of(new Acks.Ack("abcdefabcdef", 1_000_000L, "known |\r\nflaky")));
        assertTablesHoldTheirShape(acks);
        assertThat(rows(acks).get(2)).endsWith("| abcdefabcdef | known \\| flaky |");

        assertThat(Text.mark(new Marks.Mark(1, 1_000_000L, "before", null, "two\nlines")))
                .as("the one-line answer to a mark").endsWith(" — two lines\n");
    }

    @Test
    void aStatusValueAndACheckDetailAreCellsLikeAnyOther() {
        Map<String, @Nullable String> fields = new LinkedHashMap<>();
        fields.put("mode", "standalone");
        fields.put("fallback reason", "java.io.IOException: bind failed\n\tat a|b");
        fields.put("empty", " ");
        String status = Text.status(fields);
        assertTablesHoldTheirShape(status);
        assertThat(status).contains("| fallback reason | java.io.IOException: bind failed at a\\|b |")
                .contains("| empty | — |");

        Check.CheckResult result = new Check.CheckResult(false, 5, null, List.of(
                new Check.RuleCheck(Check.MAX_ERROR_RATE, 0, 0.2, false,
                        "worst: GET /a|b\n(2 of 10)")));
        String check = Text.check(result, WINDOW, null, null);
        assertTablesHoldTheirShape(check);
        assertThat(check).contains("| worst: GET /a\\|b (2 of 10) |");
    }

    @Test
    void aSqlCellIsCutBeforeItIsEscapedSoTheCutNeverSplitsAnEscape() {
        String value = "x".repeat(199) + "|" + "y".repeat(10);
        ReadOnlyQuery.Result result = new ReadOnlyQuery.Result(List.of("A|B", "C"),
                List.of(Arrays.asList(value, "line\nbreak")), false, 1);

        String text = Text.sql(result, 200, false);

        assertTablesHoldTheirShape(text);
        assertThat(rows(text).get(0)).isEqualTo("| A\\|B | C |");
        assertThat(rows(text).get(2)).isEqualTo("| " + "x".repeat(199) + "\\|… | line break |");
    }
}
