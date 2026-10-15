package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.SpanRecord;

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

    /** A finding's why and its numbers are one line each, whatever the message carried. */
    @Test
    void aFindingsWhyAndNumbersStayOnTheirLines() {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("name", "GET /a");
        endpoint.put("count", 3L);
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("count", 3L);
        numbers.put("message", H2_MESSAGE);
        numbers.put("endpoints", List.of(endpoint));
        numbers.put("notes", List.of("one\ntwo"));
        Findings.Finding finding = new Findings.Finding("error:abcdefabcdef", "error", "high", "svc",
                "Table \"X\" not found", "3 occurrences in GET /a; " + H2_MESSAGE,
                Findings.Subject.error("abcdefabcdef"),
                numbers, null, List.of(), List.of());

        String text = Text.findings(WINDOW, null, 3, List.of(finding), false, "http://localhost:4000");

        assertTablesHoldTheirShape(text);
        assertThat(text)
                .contains("\n1. error:abcdefabcdef — 3 occurrences in GET /a; Table \"X\" not found;"
                        + " SQL statement: SELECT a | b FROM x [42102-224]\n")
                .contains("\n   count 3, message Table \"X\" not found; SQL statement: SELECT a | b FROM x"
                        + " [42102-224], endpoints [name GET /a count 3], notes one two\n");
    }

    /** An error with no message (a client call's status) prints it as the errors table does. */
    @Test
    void aBlankMessageInTheNumbersReadsAsADash() {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("count", 3L);
        numbers.put("type", "500");
        numbers.put("message", "");
        Findings.Finding finding = new Findings.Finding("error:abcdefabcdef", "error", "high", "caller",
                "500 from GET localhost:8081/api/flaky", "3 occurrences from GET localhost:8081/api/flaky",
                Findings.Subject.error("abcdefabcdef"),
                numbers, null, List.of(), List.of());

        assertThat(Text.findings(WINDOW, null, 3, List.of(finding), false, "http://localhost:4000"))
                .contains("\n   count 3, type 500, message —\n");
    }

    /** A list the limit cut says how many there are, and how to see the rest. */
    @Test
    void aCutListSaysHowManyFindingsThereAre() {
        Findings.Finding finding = new Findings.Finding("slow-endpoint:abcdefabcdef", "slow-endpoint",
                "medium", "svc", "GET /a is slow", "p95 600.0 ms over 3 calls",
                Findings.Subject.endpoint("abcdefabcdef"), Map.of("calls", 3L), null, List.of(),
                List.of());

        assertThat(Text.findings(WINDOW, null, 3, 0, 0, 54, List.of(finding), false, null))
                .contains(")\n\n1 of 54 findings; --limit=54 shows them all\n\n| # |");
        assertThat(Text.findings(WINDOW, null, 3, 0, 0, 154, List.of(finding), false, null))
                .contains("\n1 of 154 findings; --limit=100 shows the first 100\n");
        assertThat(Text.findings(WINDOW, null, 3, 0, 0, 154, java.util.Collections.nCopies(100, finding),
                false, null)).contains("\n100 of 154 findings; --service= or a shorter window shows the rest\n");
        assertThat(Text.findings(WINDOW, null, 3, 0, 0, 1, List.of(finding), false, null))
                .doesNotContain(" of 1 finding");
    }

    /** A statement, a log body and a note outside a table keep their bars, so they read as written. */
    @Test
    void aLineThatIsNoTableRowKeepsItsBars() {
        String statement = "select c1_0.id from customer c1_0\nwhere lower(c1_0.name) like (?||?||?) escape ?";
        Findings.Finding finding = new Findings.Finding("n-plus-one:abcdefabcdef", "n-plus-one",
                "medium", "svc", "GET /a runs SELECT customer 6 times", "6 times per request",
                Findings.Subject.error("abcdefabcdef"), Map.of("count", 6L), statement, List.of(),
                List.of());

        String findings = Text.findings(WINDOW, null, 3, List.of(finding), false, "http://localhost:4000");
        assertThat(findings).contains("\n   select c1_0.id from customer c1_0 where lower(c1_0.name)"
                + " like (?||?||?) escape ?\n");

        String logs = Text.logs(WINDOW, null, List.of(new LogRecord(1, 1_000_000L, "svc", "WARN", 13,
                "rate|limit hit\nfor a|b", "orders.Limiter", null, null, Map.of())), 1, 1,
                "http://localhost:4000");
        assertThat(logs).contains("orders.Limiter  rate|limit hit for a|b\n");

        assertThat(Text.mark(new Marks.Mark(1, 1_000_000L, "before", null, "a|b")))
                .endsWith(" — a|b\n");
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

    /**
     * A cell or a statement cut at 200 characters leaves out a surrogate pair the cut would split,
     * so it never ends in half of one, which UTF-8 carries as {@code ?} (storage.adoc#writer).
     */
    @Test
    void aCutForDisplayLeavesOutASurrogatePairWhole() {
        String kept = "a".repeat(199);
        String value = kept + "😀z";
        ReadOnlyQuery.Result result = new ReadOnlyQuery.Result(List.of("C"), List.of(List.of(value)), false, 1);

        assertThat(rows(Text.sql(result, 200, false)).get(2)).isEqualTo("| " + kept + "… |");
        assertThat(Text.statementLine(value, false)).isEqualTo(kept + "…");
        assertThat(Text.statementLine(value + "b".repeat(SpanRecord.MAX_STATEMENT), false))
                .isEqualTo(kept + "… (cut at 2,000 characters)");
    }

    /** An ARRAY cell reads as its JSON text, the way the JSON answer writes it. */
    @Test
    void aSqlArrayCellIsItsJsonText() {
        ReadOnlyQuery.Result result = new ReadOnlyQuery.Result(List.of("NAMES"),
                List.of(List.of(Arrays.asList("GET /a", null, 2L))), false, 1);

        assertThat(rows(Text.sql(result, 200, false)).get(2)).isEqualTo("| [\"GET /a\",null,2] |");
    }

    /**
     * A statement as long as the store keeps may have been cut there: a query row, a compare row
     * and a finding say so, {@code --full} included, and so does the JSON beside them.
     */
    @Test
    void aStatementTheStoreMayHaveCutSaysSoInEveryRendering() {
        String stored = ("select x from t where id in (" + "?,".repeat(1500))
                .substring(0, SpanRecord.MAX_STATEMENT);
        String cut = "… (cut at 2,000 characters)";
        Stats.QueryStats query = new Stats.QueryStats("abcdefabcdef", "svc", "h2", null, "SELECT", "t",
                stored, 4, 0, 1.0, 1.0, 1.0, 1.0, 4.0, 0, List.of(), 1_000_000L, null);
        Stats.QueryStats whole = new Stats.QueryStats("bcdefabcdefa", "svc", "h2", null, "SELECT", "t",
                "select x from t", 4, 0, 1.0, 1.0, 1.0, 1.0, 4.0, 0, List.of(), 1_000_000L, null);

        assertThat(rows(Text.queries(WINDOW, null, List.of(query, whole), 3, true, "http://localhost:4000")))
                .anySatisfy(row -> assertThat(row).endsWith("| " + stored + cut + " |"))
                .anySatisfy(row -> assertThat(row).endsWith("| select x from t |"));
        assertThat(rows(Text.queries(WINDOW, null, List.of(query), 3, false, "http://localhost:4000")))
                .anySatisfy(row -> assertThat(row).endsWith("| " + stored.substring(0, 200) + cut + " |"));

        Compare.Comparison comparison = new Compare.Comparison(WINDOW, WINDOW,
                Stats.Totals.EMPTY, Stats.Totals.EMPTY, List.of(),
                List.of(new Compare.QueryDiff("abcdefabcdef", "svc", stored, null, null, "new")), List.of());
        assertThat(rows(Text.compare(comparison, null, true)))
                .anySatisfy(row -> assertThat(row).endsWith("| " + stored + cut + " |"));

        Findings.Finding finding = new Findings.Finding("n-plus-one:abcdefabcdef", "n-plus-one",
                "medium", "svc", "GET /a runs SELECT t 6 times", "6 times per request",
                Findings.Subject.error("abcdefabcdef"), Map.of("count", 6L), stored, List.of(),
                List.of());
        assertThat(Text.findings(WINDOW, null, 3, List.of(finding), true, "http://localhost:4000"))
                .contains("\n   " + stored + cut + "\n");
        assertThat(Text.findings(WINDOW, null, 3, List.of(finding), false, "http://localhost:4000"))
                .contains("\n   " + stored.substring(0, 200) + cut + "\n");

        assertThat(Codecs.query(query).getBoolean("statementCut")).isTrue();
        assertThat(Codecs.query(whole).getBoolean("statementCut")).isFalse();
        assertThat(Codecs.finding(finding).getBoolean("statementCut")).isTrue();
        assertThat(Codecs.comparison(comparison).getArray("queries").get(0).asObject()
                .getBoolean("statementCut")).isTrue();
    }

    @Test
    void aCheckOverOneRequestSaysOneRequestLikeEveryOtherHeading() {
        Check.CheckResult one = new Check.CheckResult(true, 1, null, List.of());
        Check.CheckResult many = new Check.CheckResult(false, 12, null, List.of());

        assertThat(Text.check(one, WINDOW, null, null).lines().findFirst().orElseThrow())
                .startsWith("# check  pass  ").endsWith("(1m, all services, 1 request)");
        assertThat(Text.check(many, WINDOW, "orders", "GET /a").lines().findFirst().orElseThrow())
                .startsWith("# check  fail  ").endsWith("(1m, orders, GET /a, 12 requests)");
        assertThat(Text.check(one, WINDOW, null, null)).contains("1 request)\n\n| rule |");
    }

    @Test
    void theSmallPiecesOfAHeading() {
        assertThat(Text.plural(1, "request")).isEqualTo("1 request");
        assertThat(Text.plural(1234, "request")).isEqualTo("1234 requests");
        assertThat(Text.scope(null)).isEqualTo("all services");
        assertThat(Text.scope("orders")).isEqualTo("orders");
        assertThat(Text.interval(WINDOW.from(), WINDOW.to()))
                .isEqualTo(Text.instant(WINDOW.from()) + " → " + Text.clock(WINDOW.to()));
    }

    /**
     * A heading's start keeps its milliseconds, so pasted into {@code --since} it asks for the
     * same window again rather than one up to a second wider (marks-and-compare.adoc#time-selectors).
     */
    @Test
    void aHeadingsStartReadsBackAsTheSameInstant() {
        long from = 1_791_990_085_846L;
        String start = Text.instant(from);

        assertThat(start).contains(":25.846");
        assertThat(java.time.OffsetDateTime.parse(start).toInstant().toEpochMilli()).isEqualTo(from);
        assertThat(Text.interval(from, from + 300_000)).startsWith(start + " → ");
        assertThat(Text.instant(1_791_990_085_000L)).as("a whole second prints as one").doesNotContain(".");
    }

    /**
     * A heading, a mark and a log line print in the zone Spider Sense found, whatever default an
     * application under the agent sets later (marks-and-compare.adoc#time-zone).
     */
    @Test
    void anApplicationThatChangesTheDefaultZoneLaterMovesNoPrintedTime() {
        long at = 1_791_990_085_846L;
        ZoneId started = ZoneId.systemDefault();
        String instant = Text.instant(at);
        ZoneId other = ZoneId.of(started.getRules().getOffset(Instant.ofEpochMilli(at)).getTotalSeconds()
                == 20_700 ? "UTC" : "Asia/Kathmandu");
        TimeZone before = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(other));
        try {
            assertThat(Text.instant(at)).isEqualTo(instant);
            assertThat(Text.instantMillis(at)).isEqualTo(instant);
            assertThat(Text.clockMillis(at)).isEqualTo(instant.substring(11, 23));
            assertThat(Text.clock(at)).isEqualTo(instant.substring(11, 19));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /**
     * The sources are text: a control character saved as the byte itself rather than as an escape
     * makes {@code grep} and {@code file} treat the file as binary, and javac accepts it silently.
     */
    @Test
    void theSourcesHoldNoControlCharacter() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(f -> f.toString().matches(".*\\.(java|js|css|html)")).toList()) {
                String text = Files.readString(file);
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                        found.add(file + " at " + i + ": " + (int) c);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
