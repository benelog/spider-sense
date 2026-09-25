package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersilk.json.Json;

/**
 * The CLI's one promise, tested as a promise: a command prints the same bytes on stdout and on
 * stderr, and leaves the same exit code, whether a running Spider Sense answered it over HTTP or
 * the CLI read the file itself (cli.adoc).
 *
 * <p>Both runs read one database: a server is started on it, and the file path opens the same
 * database beside it, the way {@code AUTO_SERVER=TRUE} lets the CLI join a running server's file.
 * The window is two epoch instants rather than a duration, so both runs ask about the same
 * milliseconds however long the first one took.
 */
class CliParityTest {

    private static final long NOW = System.currentTimeMillis() - 60_000;
    private static final String SINCE = "--since=" + (NOW - 60_000);
    private static final String UNTIL = "--until=" + (NOW + 30_000);
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String FAILING = "4bf92f3577b34da6a3ce929d0e0e4737";

    /**
     * The commands with no second way to answer, and why; every other command is a case below.
     */
    private static final Map<String, String> ONE_WAY = Map.of(
            Options.HELP, "prints the table and asks nobody",
            Options.INIT, "writes into a project and asks nobody (agent-skill.adoc#init)",
            Options.TAIL, "has no direct-file path, because there is no file to tail (cli.adoc)");

    private static String database;
    private static SpiderSenseServer server;
    private static String base;
    private static String findingId;
    private static Path exported;

    private record Run(int exit, String out, String err) {
    }

    /**
     * One command, as a list of command lines run in order: a write is followed by the command
     * that undoes it, so the second mode starts from the state the first one found.
     *
     * @param normalize what a line may carry that is the moment of the run, not the data
     */
    private record Case(String name, List<List<String>> steps, String stdin,
            UnaryOperator<String> normalize) {

        String command() {
            return steps.get(0).get(0);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Case of(String name, List<List<String>> steps) {
        return new Case(name, steps, "", UnaryOperator.identity());
    }

    private static Case of(String... args) {
        return of(String.join(" ", args), List.of(List.of(args)));
    }

    @BeforeAll
    static void serve() throws IOException {
        database = TestStore.memoryUrl();
        server = SpiderSenseServer.start(
                Config.parse(new String[] {"--port=0", "--db=" + database, "--retention.hours=24"}));
        new OtlpDecoder(server.store(), server::port).ingest(sample());
        new OtlpDecoder(server.store(), server::port).ingest(Otlp.logs(Otlp.service("orders"),
                "orders.OrderService",
                Otlp.log(NOW + 1, 17, "could not ship order 42", FAILING, "00f067aa0ba902c1")));
        server.store().writer().awaitIdle(5_000);
        base = "http://127.0.0.1:" + server.port();

        Run findings = run(false, "", "findings", "--json", SINCE, UNTIL);
        findingId = Json.parse(findings.out()).asObject().getArray("findings").get(0).asObject()
                .getString("id");

        exported = Files.createTempFile("spider-sense-parity", ".json");
        assertThat(run(false, "", "export", SINCE, UNTIL, "--out=" + exported).exit()).isZero();
    }

    @AfterAll
    static void stop() throws IOException {
        server.stop();
        Files.deleteIfExists(exported);
    }

    static Stream<Case> cases() {
        String mcp = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"findings\","
                        + "\"arguments\":{\"since\":\"" + (NOW - 60_000) + "\",\"until\":\""
                        + (NOW + 30_000) + "\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"trace\","
                        + "\"arguments\":{\"traceId\":\"" + TRACE + "\"}}}") + "\n";
        return Stream.of(
                // Which mode answered, where it listens and since when is what status is for.
                new Case("status", List.of(List.of("status")), "",
                        text -> text.replaceAll("\\| (mode|endpoint|started) \\| .* \\|", "| $1 | |")),
                of("findings", SINCE, UNTIL, "--no-git"),
                of("findings", SINCE, UNTIL, "--no-git", "--full", "--limit=1", "--service=orders"),
                of("findings", SINCE, UNTIL, "--json"),
                of("ack " + findingId, List.of(
                        List.of("ack", findingId, "--note=slow by design"),
                        List.of("findings", SINCE, UNTIL, "--no-git", "--hide-acked"),
                        List.of("unack", findingId))),
                of("unack", "f".repeat(16)),
                new Case("resolve " + findingId, List.of(
                        List.of("resolve", findingId, "--note=fetch join", "--json"),
                        List.of("findings", SINCE, UNTIL, "--no-git"),
                        List.of("unresolve", findingId, "--json")), "",
                        text -> text.replaceAll("\"at\":\\d+", "\"at\":0")),
                of("unresolve", "f".repeat(16)),
                of("trace", TRACE),
                of("trace", TRACE, "--full", "--json"),
                of("trace", TRACE, "--diff=" + FAILING),
                of("trace", "f".repeat(32)),
                of("traces", SINCE, UNTIL),
                of("traces", SINCE, UNTIL, "--status=error", "--q=ship", "--full"),
                of("traces", SINCE, UNTIL, "--min-ms=100", "--limit=1", "--json"),
                of("endpoints", SINCE, UNTIL),
                // An empty answer names where to send telemetry: the file path the configured port,
                // the server the one it bound, which in this test is not the configured one.
                new Case("endpoints --service=nobody",
                        List.of(List.of("endpoints", SINCE, UNTIL, "--service=nobody")), "",
                        text -> text.replaceAll("http://127\\.0\\.0\\.1:\\d+/", "http://127.0.0.1:<port>/")),
                of("queries", SINCE, UNTIL, "--limit=1", "--full"),
                of("errors", SINCE, UNTIL, "--json"),
                of("logs", SINCE, UNTIL),
                of("logs", SINCE, UNTIL, "--severity=WARN", "--q=ship", "--trace=" + FAILING,
                        "--limit=5"),
                new Case("mark", List.of(List.of("mark", "parity", "--note=both ways")), "",
                        text -> text.replaceAll(" at \\S+", " at <now>")),
                of("marks", "--limit=5"),
                of("compare", "--before=" + (NOW - 60_000), "--after=" + (NOW + 50),
                        "--until=" + (NOW + 30_000)),
                of("compare", "--before=" + (NOW - 60_000), "--after=" + (NOW + 50),
                        "--until=" + (NOW + 30_000), "--json", "--full"),
                of("check", SINCE, UNTIL),
                of("check", SINCE, UNTIL, "--max-p95-ms=100", "--max-errors=0", "--json"),
                of("check", SINCE, UNTIL, "--endpoint=GET /nowhere"),
                of("sql", "SELECT service, COUNT(*) AS spans FROM span GROUP BY service"),
                new Case("sql --json",
                        List.of(List.of("sql", "SELECT id FROM span ORDER BY id", "--limit=2", "--json")),
                        "", text -> text.replaceAll("\"elapsedMs\":\\d+", "\"elapsedMs\":0")),
                of("sql", "DELETE FROM span"),
                new Case("export", List.of(List.of("export", SINCE, UNTIL)), "",
                        text -> text.replaceAll("\"exportedAt\":\\d+", "\"exportedAt\":0")),
                of("import", exported.toString()),
                of("import", exported.toString(), "--json"),
                new Case("mcp", List.of(List.of("mcp")), mcp, UnaryOperator.identity()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void bothWaysOfAnsweringPrintTheSameLinesAndExitTheSame(Case parity) {
        List<Run> fromFile = new ArrayList<>();
        for (List<String> step : parity.steps()) {
            fromFile.add(run(false, parity.stdin(), step.toArray(new String[0])));
        }
        List<Run> overHttp = new ArrayList<>();
        for (List<String> step : parity.steps()) {
            overHttp.add(run(true, parity.stdin(), step.toArray(new String[0])));
        }
        for (int i = 0; i < fromFile.size(); i++) {
            Run file = fromFile.get(i);
            Run http = overHttp.get(i);
            String step = String.join(" ", parity.steps().get(i));
            assertThat(http.exit()).as("%s: the exit code over HTTP (stderr %s, from the file %s)",
                    step, http.err(), file.err()).isEqualTo(file.exit());
            assertThat(parity.normalize().apply(http.out()))
                    .as("%s: stdout over HTTP", step)
                    .isEqualTo(parity.normalize().apply(file.out()));
            assertThat(file.out() + file.err()).as("%s: says something", step).isNotEmpty();
            assertThat(http.err()).as("%s: stderr over HTTP", step).isEqualTo(file.err());
        }
    }

    @Test
    void everyCommandIsAskedBothWaysOrSaysWhyItHasOneWay() {
        Set<String> covered = new TreeSet<>(ONE_WAY.keySet());
        cases().map(Case::command).forEach(covered::add);

        assertThat(covered).isEqualTo(new TreeSet<>(Options.commands()));
    }

    /** One command line, over HTTP at the server or from its database with no server asked. */
    private static Run run(boolean overHttp, String stdin, String... args) {
        String[] line = new String[args.length + 1];
        System.arraycopy(args, 0, line, 0, args.length);
        line[args.length] = overHttp ? "--url=" + base : "--db=" + database;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(line, closedUrl(), new ByteArrayInputStream(stdin.getBytes(UTF_8)), toOut,
                    toErr);
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

    /** A slow endpoint whose trace repeats a statement, and a second request that failed. */
    private static io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest sample() {
        Span.Builder root = Otlp.span(TRACE, "00f067aa0ba902b7", "GET /orders/{id}",
                Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                Otlp.attr("http.request.method", "GET"),
                Otlp.attr("http.route", "/orders/{id}"),
                Otlp.attr("http.response.status_code", 200));
        List<Span.Builder> spans = new ArrayList<>();
        spans.add(root);
        for (int i = 0; i < 6; i++) {
            spans.add(Otlp.child(root, "%016x".formatted(100 + i), "SELECT order_line",
                    Span.SpanKind.SPAN_KIND_CLIENT, NOW + i, 2,
                    Otlp.attr("db.system", "h2"),
                    Otlp.attr("db.statement", "select * from order_line where order_id = ?"),
                    Otlp.attr("db.operation", "SELECT"),
                    Otlp.attr("db.sql.table", "order_line")));
        }
        spans.add(Otlp.failing(
                Otlp.span(FAILING, "00f067aa0ba902c1", "POST /orders/{id}/ship",
                        Span.SpanKind.SPAN_KIND_SERVER, NOW + 100, 20,
                        Otlp.attr("http.request.method", "POST"),
                        Otlp.attr("http.route", "/orders/{id}/ship"),
                        Otlp.attr("http.response.status_code", 500)),
                "java.lang.IllegalStateException", "Order 42 is already shipped",
                "java.lang.IllegalStateException: Order 42 is already shipped\n"
                        + "\tat orders.OrderService.ship(OrderService.java:41)"));
        return Otlp.traces(Otlp.service("orders"), spans.toArray(new Span.Builder[0]));
    }
}
