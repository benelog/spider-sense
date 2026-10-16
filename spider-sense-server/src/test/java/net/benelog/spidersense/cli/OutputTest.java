package net.benelog.spidersense.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

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

    /**
     * Every answer and every message is UTF-8, whatever charset the platform gave the process's
     * streams: without {@code LANG}, or through a pipe on Windows, it is not UTF-8, and each
     * {@code →} and {@code —} came out as {@code ?} (cli.adoc#text-rendering, mcp.adoc#stdio).
     */
    @Test
    void theAnswerIsUtf8WhateverCharsetThePlatformStreamsUse() {
        String db = "--db=" + TestStore.writtenUrl();
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";

        assertThat(onAsciiStreams("", "findings", "--since=1h", "--no-git", db).out()).contains(" → ");
        assertThat(onAsciiStreams(initialize, "mcp", db).out()).contains("—");
        assertThat(onAsciiStreams("", "trace", "f".repeat(31) + "é", db).err())
                .contains("spider-sense: No such trace: " + "f".repeat(31) + "é\n");
    }

    private record Printed(String out, String err) {
    }

    /**
     * One command through the launcher's own entry point, with the process's streams encoding
     * US-ASCII, as a JVM's do when it starts without {@code LANG}; what they received is read as UTF-8.
     */
    private static Printed onAsciiStreams(String stdin, String... args) {
        InputStream platformIn = System.in;
        PrintStream platformOut = System.out;
        PrintStream platformErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.US_ASCII));
            System.setErr(new PrintStream(err, true, StandardCharsets.US_ASCII));
            Cli.run(args);
        } finally {
            System.setIn(platformIn);
            System.setOut(platformOut);
            System.setErr(platformErr);
        }
        return new Printed(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String printed(Consumer<PrintStream> body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            body.accept(stream);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
