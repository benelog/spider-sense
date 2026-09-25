package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** What a command prints, the same from either source of an answer. */
class OutputTest {

    @Test
    void anAnswerEndsInOneNewline() {
        assertThat(printed(out -> Output.print(out, "# status"))).isEqualTo("# status\n");
        assertThat(printed(out -> Output.print(out, "# status\n"))).isEqualTo("# status\n");
    }

    @Test
    void anEmptyAnswerPrintsNothing() {
        assertThat(printed(out -> Output.print(out, ""))).isEmpty();
        assertThat(printed(out -> Output.print(out, null))).isEmpty();
    }

    @Test
    void anExportSaysWhereItWroteOnlyWhenItWroteAFile() {
        assertThat(printed(err -> Output.wroteExport(err, "session.json.gz")))
                .isEqualTo("wrote session.json.gz" + System.lineSeparator());
        assertThat(printed(err -> Output.wroteExport(err, null))).isEmpty();
    }

    private static String printed(Consumer<PrintStream> body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            body.accept(stream);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
