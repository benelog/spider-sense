package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/**
 * Every worked query in the skill's SQL reference, run against the real schema.
 *
 * <p>A reference page of queries that do not parse is worse than no page: an
 * agent reaching for the escape hatch is already past the questions the named
 * commands answer, and a column name that moved would send it looking for the
 * mistake in its own statement. So the page is the fixture — the blocks are read
 * out of it rather than copied here, and a rename that breaks one breaks this.
 */
class SqlReferenceTest {

    private static final String REFERENCE = "skills/spider-sense/references/sql.md";

    @Test
    void everyWorkedQueryOfTheReferenceRuns() {
        List<String> queries = statements(read());
        assertThat(queries).as("the reference's ```sql blocks").hasSizeGreaterThanOrEqualTo(6);

        String url = TestStore.memoryUrl();
        try (Database database = Database.open(url, null)) {
            ReadOnlyQuery sql = new ReadOnlyQuery(database);
            for (String query : queries) {
                ReadOnlyQuery.Result result = sql.run(query, 200);
                assertThat(result.columns()).as(query).isNotEmpty();
            }
        }
    }

    /** The ```sql blocks that are whole statements; the window idioms are fragments. */
    private static List<String> statements(String page) {
        List<String> found = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = page.indexOf("```sql", at);
            if (open < 0) {
                return found;
            }
            int start = page.indexOf('\n', open) + 1;
            int close = page.indexOf("```", start);
            String block = page.substring(start, close).trim();
            at = close + 3;
            if (first(block).startsWith("SELECT") || first(block).startsWith("WITH")
                    || first(block).startsWith("TABLE") || first(block).startsWith("VALUES")) {
                found.add(block);
            }
        }
    }

    /** The first line that is not a comment, upper-cased. */
    private static String first(String block) {
        for (String line : block.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                return trimmed.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    /**
     * The page, found by walking up from wherever the tests were started.
     *
     * <p>It lives beside the skill and not in the test resources, because it is the
     * page an agent reads; a copy would be the thing that drifts.
     */
    private static String read() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null) {
            Path page = at.resolve(REFERENCE);
            if (Files.isRegularFile(page)) {
                try {
                    return Files.readString(page, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            at = at.getParent();
        }
        throw new IllegalStateException("no " + REFERENCE + " above " + Path.of("").toAbsolutePath());
    }
}
