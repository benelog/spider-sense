package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersilk.json.Json;

/**
 * {@code java -jar spider-sense.jar mcp}: the stdio transport of
 * mcp.adoc#stdio.
 *
 * <p>The contract it has to keep is narrow and total — newline-delimited JSON on
 * stdout and nothing else on it, one line per request, none for a notification —
 * because a host parses this stream and a stray word breaks the session.
 */
class McpCommandTest {

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String stdin, String... args) {
        return runAt(closedUrl(), stdin, args);
    }

    private static Run runAt(String defaultUrl, String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(args, defaultUrl, new ByteArrayInputStream(stdin.getBytes(UTF_8)),
                    toOut, toErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    private static String closedUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}";
    private static final String INITIALIZED =
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

    @Test
    void aSessionOverStdinAnswersOneJsonLinePerRequestAndNothingElse() {
        String db = "--db=" + TestStore.writtenUrl();
        String findings = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"findings\",\"arguments\":{\"since\":\"1h\"}}}";

        Run run = run(String.join("\n", INITIALIZE, INITIALIZED, "", TOOLS_LIST, findings) + "\n",
                "mcp", db);

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(run.err()).as("--db asks no server, so nothing is said about one").isEmpty();

        List<String> lines = run.out().lines().toList();
        assertThat(lines).as("initialize, tools/list, findings — the notification is silent")
                .hasSize(3);
        assertThat(run.out()).endsWith("\n");

        Json.JsonObject initialized = Json.parse(lines.get(0)).asObject();
        assertThat(initialized.getLong("id")).isEqualTo(1);
        assertThat(initialized.getObject("result").getString("protocolVersion"))
                .isEqualTo("2025-06-18");

        Json.JsonObject tools = Json.parse(lines.get(1)).asObject();
        assertThat(tools.getLong("id")).isEqualTo(2);
        assertThat(tools.getObject("result").getArray("tools").size()).isEqualTo(7);

        Json.JsonObject answered = Json.parse(lines.get(2)).asObject();
        assertThat(answered.getLong("id")).isEqualTo(3);
        Json.JsonObject result = answered.getObject("result");
        assertThat(result.getBoolean("isError")).isFalse();
        assertThat(result.getArray("content").get(0).asObject().getString("text"))
                .startsWith("# findings  ");
    }

    /**
     * A mark is a row and not a message to a server, so it is recorded here too;
     * the second call proves it by resolving the mark as a selector.
     */
    @Test
    void aMarkIsRecordedInTheFileJustAsTheCliRecordsItThere() {
        String db = "--db=" + TestStore.writtenUrl();
        String mark = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"mark\",\"arguments\":{\"name\":\"before\",\"note\":\"the slow one\"}}}";
        String since = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"findings\",\"arguments\":{\"since\":\"before\"}}}";

        Run run = run(mark + "\n" + since + "\n", "mcp", db);

        List<String> lines = run.out().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(text(lines.get(0))).startsWith("mark before at ").contains("the slow one");
        assertThat(Json.parse(lines.get(1)).asObject().getObject("result").getBoolean("isError"))
                .as("the mark resolved, so it is in the file").isFalse();
        assertThat(text(lines.get(1))).startsWith("# findings  ");
    }

    /**
     * A file no server of this version has opened has no read-only user, and
     * {@code sql} over it is a tool result the model can read, as the CLI's exit
     * code 2 and message are, rather than a JSON-RPC internal error.
     */
    @Test
    void sqlOverAFileWithNoReaderUserIsAToolErrorAndNotAProtocolError() {
        String db = "--db=" + TestStore.writtenUrlWithoutReader();
        String sql = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"sql\",\"arguments\":{\"sql\":\"SELECT 1\"}}}";

        Run run = run(sql + "\n", "mcp", db);

        List<String> lines = run.out().lines().toList();
        assertThat(lines).hasSize(1);
        Json.JsonObject response = Json.parse(lines.get(0)).asObject();
        assertThat(response.has("error")).as("not a protocol error").isFalse();
        assertThat(response.getObject("result").getBoolean("isError")).isTrue();
        assertThat(text(lines.get(0))).contains("the database has no read-only user yet");
    }

    /** The {@code resolve} tool writes to the file as the CLI's {@code resolve} does. */
    @Test
    void aResolutionIsRecordedInTheFileJustAsTheCliRecordsItThere() {
        String db = "--db=" + TestStore.writtenUrl();
        String resolve = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"resolve\",\"arguments\":{\"findingId\":\"n-plus-one:0011223344ff\","
                + "\"note\":\"fetch join\"}}}";
        String missing = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"resolve\",\"arguments\":{}}}";

        Run run = run(resolve + "\n" + missing + "\n", "mcp", db);

        List<String> lines = run.out().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(text(lines.get(0))).isEqualTo("resolved n-plus-one:0011223344ff — fetch join\n");
        assertThat(Json.parse(lines.get(1)).asObject().getObject("error").getLong("code"))
                .as("findingId is required").isEqualTo(-32602);
    }

    /**
     * A named {@code --url} is a statement that there is a server there, so the file
     * is never quietly answered instead — the same rule the other commands follow.
     */
    @Test
    void aNamedUrlThatAnswersNothingFailsTheCallRatherThanReadingTheFile() {
        String closed = closedUrl();
        String findings = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"findings\",\"arguments\":{}}}";

        Run run = run(TOOLS_LIST + "\n" + findings + "\n", "mcp", "--url=" + closed);

        assertThat(run.exit()).isZero();
        List<String> lines = run.out().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(Json.parse(lines.get(0)).asObject().getObject("result").getArray("tools").size())
                .as("tools/list is answered in process either way").isEqualTo(7);
        Json.JsonObject failed = Json.parse(lines.get(1)).asObject().getObject("result");
        assertThat(failed.getBoolean("isError")).isTrue();
        assertThat(failed.getArray("content").get(0).asObject().getString("text"))
                .startsWith("no Spider Sense at " + closed + " (");
    }

    /**
     * Nothing listening and no {@code --url}: the file answers, and stderr says so
     * in the one line the CLI prints, once for the whole session.
     */
    @Test
    void nothingListeningFallsBackToTheFileAndSaysSoOnStderrOnce() {
        String memory = TestStore.writtenUrl();
        System.setProperty("spidersense.db", memory);
        try {
            String closed = closedUrl();
            String findings = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"findings\",\"arguments\":{}}}";
            String again = findings.replace("\"id\":1", "\"id\":2");

            Run run = runAt(closed, findings + "\n" + again + "\n", "mcp");

            assertThat(run.exit()).isZero();
            List<String> lines = run.out().lines().toList();
            assertThat(lines).hasSize(2);
            assertThat(text(lines.get(1))).startsWith("# findings  ");
            assertThat(run.err()).as("said once, not once per call")
                    .isEqualTo("(no Spider Sense at " + closed + "; reading " + memory
                            + " directly)\n");
        } finally {
            System.clearProperty("spidersense.db");
        }
    }

    /**
     * A running Spider Sense answers the call, and its bytes are forwarded as they
     * came: the same dispatcher runs on both sides, so there is nothing to render
     * twice.
     */
    @Test
    void aRunningSpiderSenseAnswersTheCallAndItsResponseIsForwardedVerbatim() {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            String base = "http://127.0.0.1:" + server.port();
            String mark = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"mark\",\"arguments\":{\"name\":\"over-http\"}}}";

            Run run = run(mark + "\n", "mcp", "--url=" + base);

            assertThat(run.exit()).isZero();
            assertThat(run.err()).isEmpty();
            List<String> lines = run.out().lines().toList();
            assertThat(lines).hasSize(1);
            assertThat(Json.parse(lines.get(0)).asObject().getLong("id")).isEqualTo(9);
            assertThat(text(lines.get(0))).startsWith("mark over-http at ");
            assertThat(server.store().marks().newest("over-http", null))
                    .as("recorded by the server, not by this process").isNotNull();
        } finally {
            server.stop();
        }
    }

    /**
     * A server that answers a forwarded message with an error status other than
     * 404 or 405 is running and refusing that message: the call fails, and the
     * next one asks the server again rather than turning to the file for good.
     */
    @Test
    void aStatusFromARunningServerFailsTheCallAndTheSessionKeepsAskingIt() throws IOException {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer refusing = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        refusing.createContext("/mcp", exchange -> {
            asked.incrementAndGet();
            byte[] body = "{\"error\":\"The body is larger than 1 MB\"}".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(413, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        refusing.start();
        try {
            String base = "http://127.0.0.1:" + refusing.getAddress().getPort();
            String findings = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"findings\",\"arguments\":{}}}";
            String again = findings.replace("\"id\":1", "\"id\":2");

            Run run = runAt(base, findings + "\n" + again + "\n", "mcp");

            List<String> lines = run.out().lines().toList();
            assertThat(lines).hasSize(2);
            for (String line : lines) {
                assertThat(Json.parse(line).asObject().getObject("result").getBoolean("isError")).isTrue();
                assertThat(text(line)).contains("refused the call").contains("413");
            }
            assertThat(run.err()).as("no turn to the file").isEmpty();
            assertThat(asked.get()).as("both calls reached the server").isEqualTo(2);
        } finally {
            refusing.stop(0);
        }
    }

    @Test
    void mcpTakesOnlyTheTwoOptionsThatSayWhereToRead() {
        assertThat(run("", "mcp", "--since=5m").exit()).isEqualTo(2);
        assertThat(run("", "mcp", "--since=5m").err())
                .startsWith("spider-sense: unknown option for mcp: --since");
        assertThat(run("", "mcp", "--json").exit()).isEqualTo(2);
        assertThat(run("", "mcp", "--db=" + TestStore.writtenUrl()).exit())
                .as("end of input is the end of the session").isZero();
    }

    private static String text(String line) {
        return Json.parse(line).asObject().getObject("result").getArray("content").get(0)
                .asObject().getString("text");
    }
}
