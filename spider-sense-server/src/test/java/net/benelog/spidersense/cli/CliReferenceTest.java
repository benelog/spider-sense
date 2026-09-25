package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The skill's CLI reference, {@code skills/spider-sense/references/cli.md}, against
 * the CLI it describes.
 *
 * <p>{@code init} installs that page into every project as "every command, its
 * flags, its exit codes", so a command or a flag the page lacks is one an agent
 * never learns about. The page is read as the jar carries it, which is also what
 * {@code init} copies.
 */
class CliReferenceTest {

    private static final String PAGE = Init.SKILL_PREFIX + "spider-sense/references/cli.md";

    private static final String HELP_COMMAND = "$ java -jar spider-sense.jar help\n";

    @Test
    void theHelpListingIsWhatHelpPrints() throws IOException {
        String page = page();
        int start = page.indexOf(HELP_COMMAND);
        assertThat(start).as("the page shows a help run").isNotNegative();
        start += HELP_COMMAND.length();
        int end = page.indexOf("\n```", start);

        assertThat(page.substring(start, end)).isEqualTo(Help.TEXT);
    }

    @Test
    void theCommandTableHasARowForEveryCommand() throws IOException {
        String page = page();
        List<String> commands = Help.TEXT.lines()
                .dropWhile(line -> !line.equals("Commands:"))
                .skip(1)
                .takeWhile(line -> !line.isEmpty())
                .filter(line -> line.startsWith("  ") && !line.startsWith("   "))
                .map(line -> line.trim().split("[ \\[]", 2)[0])
                .toList();
        assertThat(commands).contains("status", "tail", "help");

        for (String command : commands) {
            assertThat(page).as("a row for %s", command)
                    .containsAnyOf("| `" + command + "`", "| `" + command + " ", "`, `" + command + "`");
        }
    }

    private static String page() throws IOException {
        try (InputStream in = CliReferenceTest.class.getClassLoader().getResourceAsStream(PAGE)) {
            assertThat(in).as(PAGE + " in the jar").isNotNull();
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
