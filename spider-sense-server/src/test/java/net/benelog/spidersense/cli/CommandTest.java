package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The command table against the help that lists it and against itself. */
class CommandTest {

    /** A command added to the table and not to the help, or the other way round, fails here. */
    @Test
    void theHelpListsEveryCommandInTheOrderOfTheTable() {
        List<String> listed = Help.TEXT.lines()
                .dropWhile(line -> !line.equals("Commands:"))
                .skip(1)
                .takeWhile(line -> !line.isEmpty())
                .filter(line -> line.startsWith("  ") && !line.startsWith("   "))
                .map(line -> line.trim().split("[ \\[]", 2)[0])
                .toList();

        assertThat(listed).isEqualTo(Command.names());
    }

    @Test
    void aRowSentOverHttpHasAPathAndAPostHasABody() {
        for (Command command : Command.ALL) {
            if (command.method() == null) {
                assertThat(command.path()).as(command.commandName()).isNull();
                continue;
            }
            assertThat(command.path()).as(command.commandName()).isNotNull();
            assertThat(command.body() != null).as(command.commandName())
                    .isEqualTo(command.method() == Command.Method.POST);
        }
    }

    @Test
    void everyNameIsOneRow() {
        assertThat(Command.names()).doesNotHaveDuplicates();
        assertThat(Command.named("findings")).isNotNull();
        assertThat(Command.named("finding")).isNull();
    }

    /** What says rather than asks travels in a body; a question has none (api.adoc). */
    @Test
    void theBodyOfEachPostIsWhatTheCallerSays() {
        assertThat(body("ack", "f1", "--note=known")).isEqualTo("{\"note\":\"known\"}");
        assertThat(body("mark", "before-fix", "--service=orders"))
                .contains("\"name\":\"before-fix\"").contains("\"service\":\"orders\"");
        assertThat(body("sql", "select 1", "--limit=5")).isEqualTo("{\"sql\":\"select 1\",\"limit\":5}");
        assertThat(body("findings")).as("a question").isNull();
    }

    private static @Nullable String body(String... args) {
        Options options = Options.parse(args);
        return Command.named(options.command()).bodyOf(options);
    }
}
