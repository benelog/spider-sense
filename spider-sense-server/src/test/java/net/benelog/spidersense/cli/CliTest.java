package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.api.AgentApi;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Store;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;

/**
 * The command line of cli.adoc: what each command asks for, what it prints, and
 * the exit code it leaves behind.
 *
 * <p>Both ways of answering are exercised against the same commands — a server on
 * a port of its own, and an in-memory database with no server at all — because
 * the promise of the CLI is that the second is as good as the first.
 */
class CliTest {

    private static final long NOW = System.currentTimeMillis() - 5_000;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String FAILING = "4bf92f3577b34da6a3ce929d0e0e4737";

    private record Run(int exit, String out, String err) {
    }

    /** One command, with the streams captured and a default URL of the test's choosing. */
    private static Run runAt(String defaultUrl, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(args, defaultUrl, toOut, toErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    /** A command that must not need the network: the default URL is one nothing listens on. */
    private static Run run(String... args) {
        return runAt(closedUrl(), args);
    }

    /** The same with what adds the suspect changes to a findings text given. */
    private static Run runAnnotating(Function<String, String> annotateFindings, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(args, closedUrl(), java.io.InputStream.nullInputStream(), toOut, toErr,
                    annotateFindings);
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

    /** A server on a port of its own, with the sample already stored. */
    private static void serve(boolean withData, BiConsumer<SpiderSenseServer, String> body) {
        Config config = TestStore.config();
        SpiderSenseServer server = SpiderSenseServer.start(config);
        try {
            if (withData) {
                new OtlpDecoder(server.store(), server::port).ingest(sample());
                server.store().writer().awaitIdle(5_000);
            }
            body.accept(server, "http://127.0.0.1:" + server.port());
        } finally {
            server.stop();
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

    // --- parsing -------------------------------------------------------------

    @Test
    void theDefaultUrlIsWhatTheSpidersensePropertiesImply() {
        java.util.Properties none = new java.util.Properties();
        assertThat(configured(none)).isEqualTo("http://127.0.0.1:4000");

        java.util.Properties port = new java.util.Properties();
        port.setProperty("spidersense.port", "4001");
        assertThat(configured(port)).isEqualTo("http://127.0.0.1:4001");

        java.util.Properties everywhere = new java.util.Properties();
        everywhere.setProperty("spidersense.host", "0.0.0.0");
        everywhere.setProperty("spidersense.port", "4002");
        assertThat(configured(everywhere))
                .as("0.0.0.0 is a bind address, not one to connect to")
                .isEqualTo("http://127.0.0.1:4002");

        java.util.Properties v6 = new java.util.Properties();
        v6.setProperty("spidersense.host", "::1");
        assertThat(configured(v6)).as("an IPv6 address in brackets").isEqualTo("http://[::1]:4000");

        java.util.Properties collector = new java.util.Properties();
        collector.setProperty("spidersense.collector", "http://elsewhere:4000/");
        collector.setProperty("spidersense.port", "4002");
        assertThat(configured(collector))
                .as("an application that forwards sends there, so that is what to ask")
                .isEqualTo("http://elsewhere:4000");
    }

    /**
     * A {@code spidersense.port} that is no port is a warning and the default port, as the
     * application reads it (configuration.adoc), rather than a URL no command can ask.
     */
    @Test
    void aPortThatIsNoPortIsAWarningAndTheDefaultPort() {
        Map<String, String> why = Map.of(
                "abc", "is not a number",
                "4001x", "is not a number",
                "70000", "is not a port from 0 to 65535",
                "-1", "is not a port from 0 to 65535");
        why.forEach((port, reason) -> {
            java.util.Properties typo = new java.util.Properties();
            typo.setProperty("spidersense.port", port);
            List<String> warnings = new ArrayList<>();

            assertThat(Cli.configuredUrl(typo, warnings::add)).as(port).isEqualTo("http://127.0.0.1:4000");
            assertThat(warnings).as(port)
                    .containsExactly("[spider-sense] spidersense.port=" + port + " " + reason + "; using 4000");
        });
    }

    /**
     * The malformed port is read where the default URL is needed and nowhere else: a command that
     * asks no server, and one whose {@code --url} says where to ask, prints no warning, and the file
     * path prints its own once rather than beside the CLI's (cli.adoc#invocation).
     */
    @Test
    void aMalformedPortIsWarnedAboutOnceAndOnlyWhereTheDefaultUrlIsAsked(@TempDir Path project) {
        String before = System.getProperty("spidersense.port");
        System.setProperty("spidersense.port", "abc");
        try {
            assertThat(throughTheEntryPoint("version").err()).isEmpty();
            assertThat(throughTheEntryPoint("help").err()).isEmpty();
            assertThat(throughTheEntryPoint("init", "--dir=" + project, "--jar=/x/spider-sense.jar", "--no-skill")
                    .err()).isEmpty();
            assertThat(throughTheEntryPoint("status", "--url=" + closedUrl()).err())
                    .doesNotContain("spidersense.port");
            assertThat(throughTheEntryPoint("status", "--db=" + TestStore.writtenUrl()).err())
                    .isEqualTo("[spider-sense] spidersense.port=abc is not a number; using 4000\n");
        } finally {
            if (before == null) {
                System.clearProperty("spidersense.port");
            } else {
                System.setProperty("spidersense.port", before);
            }
        }
    }

    /** One command through the launcher's entry point, which reads the properties, with the process's streams captured. */
    private static Run throughTheEntryPoint(String... args) {
        PrintStream platformOut = System.out;
        PrintStream platformErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, UTF_8));
            System.setErr(new PrintStream(err, true, UTF_8));
            exit = Cli.run(args);
        } finally {
            System.setOut(platformOut);
            System.setErr(platformErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    /** The URL the properties imply, which a well-formed set implies without a warning. */
    private static String configured(java.util.Properties properties) {
        List<String> warnings = new ArrayList<>();
        String url = Cli.configuredUrl(properties, warnings::add);
        assertThat(warnings).isEmpty();
        return url;
    }

    /**
     * A usage error is its one line and one line naming the help to read, never the whole table
     * after it, and a near miss is named (cli.adoc#help).
     */
    @Test
    void helpIsTheTableAndAUsageErrorIsOneLineAndWhereToLook() {
        Run help = run("help");
        assertThat(help.exit()).isZero();
        assertThat(help.out()).contains("Commands:").contains("compare --before=");
        assertThat(help.err()).isEmpty();

        Run unknown = run("nonsense");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.out()).isEmpty();
        assertThat(unknown.err()).isEqualTo("""
                spider-sense: unknown command: nonsense
                see: java -jar spider-sense.jar help
                """);

        Run typo = run("findings", "--sinse=5m");
        assertThat(typo.exit()).isEqualTo(2);
        assertThat(typo.err()).isEqualTo("""
                spider-sense: unknown option for findings: --sinse; did you mean --since?
                see: java -jar spider-sense.jar findings --help
                """);
        assertThat(run("fidnings").err()).startsWith("spider-sense: unknown command: fidnings; did you mean findings?\n");
        assertThat(run("findings", "--json", "--bogus=1").err())
                .startsWith("spider-sense: unknown option for findings: --bogus\n");
        assertThat(run("marks", "--limit=many").err()).as("an error found after parsing points the same way")
                .endsWith("\nsee: java -jar spider-sense.jar marks --help\n");
        assertThat(run("trace").err()).startsWith("spider-sense: trace needs a trace id");
        assertThat(run("trace").exit()).isEqualTo(2);
        assertThat(run("mark").err()).startsWith("spider-sense: mark needs a mark name");
        for (String command : List.of("trace", "ack", "unack", "resolve", "unresolve", "mark", "sql", "import")) {
            Run blank = run(command, " ", "--url=" + closedUrl());
            assertThat(blank.exit()).as("an empty %s argument is a missing one", command).isEqualTo(2);
            assertThat(blank.err()).as(command).startsWith("spider-sense: " + command + " needs ")
                    .endsWith("\nsee: java -jar spider-sense.jar " + command + " --help\n");
        }
        assertThat(run("trace", "", "--url=" + closedUrl()).err())
                .isEqualTo("spider-sense: trace needs a trace id\nsee: java -jar spider-sense.jar trace --help\n");
        assertThat(run("compare", "--before=5m").err())
                .startsWith("spider-sense: compare needs both --before and --after");
        assertThat(run("--json").exit()).isEqualTo(2);
        assertThat(run("marks", "--limit=many").exit()).isEqualTo(2);
    }

    /** {@code <command> --help}, {@code -h} and {@code help <command>} print the command's block. */
    @Test
    void aCommandsHelpIsItsBlockHoweverItIsAskedFor() {
        String block = Help.of(Objects.requireNonNull(Command.named("findings"))) + "\n";
        for (List<String> line : List.of(List.of("findings", "--help"), List.of("findings", "-h"),
                List.of("help", "findings"), List.of("findings", "--sinse=5m", "--help"))) {
            Run run = run(line.toArray(new String[0]));
            assertThat(run.exit()).as(line.toString()).isZero();
            assertThat(run.out()).as(line.toString()).isEqualTo(block);
            assertThat(run.err()).as(line.toString()).isEmpty();
        }
        assertThat(run("trace", "--help").out()).as("without the trace id it would need")
                .startsWith("trace <traceId> [--full] [--diff=<traceId>]\n");

        Run unknown = run("help", "trcae");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.err()).startsWith("spider-sense: unknown command: trcae; did you mean trace?\n");
    }

    /** The block lists exactly what the parser accepts for the command. */
    @Test
    void aCommandsBlockListsEveryOptionItsRowTakes() {
        for (Command command : Command.ALL) {
            String block = Help.of(command);
            assertThat(block).as(command.commandName()).startsWith(command.commandName());
            for (String option : command.options()) {
                assertThat(block).as(command.commandName()).containsPattern("--" + java.util.regex.Pattern.quote(option) + "(=|\\s|$)");
            }
        }
    }

    @Test
    void versionIsWhatTheLaunchersVersionFlagPrints() {
        Run run = run("version");
        assertThat(run.exit()).isZero();
        assertThat(run.out()).isEqualTo("Spider Sense " + net.benelog.spidersense.server.Version.CURRENT + "\n");
    }

    /**
     * --service is taken only by a command that narrows by it, so status --service=orders is
     * refused rather than answered for every service; export prints no answer to take --json,
     * and help takes nothing (cli.adoc#options).
     */
    @Test
    void anOptionACommandWouldIgnoreIsRefused() {
        for (List<String> line : List.of(List.of("status"), List.of("marks"), List.of("trace", TRACE),
                List.of("sql", "SELECT 1"), List.of("ack", "error:3d871f14ee3e"), List.of("unack", "error:3d871f14ee3e"),
                List.of("resolve", "error:3d871f14ee3e"), List.of("unresolve", "error:3d871f14ee3e"))) {
            List<String> args = new ArrayList<>(line);
            args.add("--service=orders");
            Run run = run(args.toArray(new String[0]));
            assertThat(run.exit()).as(line.toString()).isEqualTo(2);
            assertThat(run.err()).as(line.toString())
                    .startsWith("spider-sense: unknown option for " + line.get(0) + ": --service\n");
        }
        for (String command : List.of("findings", "traces", "endpoints", "queries", "errors", "logs", "mark",
                "compare", "check", "export", "tail")) {
            assertThat(Objects.requireNonNull(Command.named(command)).options()).as(command).contains("service");
        }
        assertThat(run("export", "--json").err()).startsWith("spider-sense: unknown option for export: --json\n");
        assertThat(run("help", "--db=x").err()).startsWith("spider-sense: unknown option for help: --db\n");
    }

    /**
     * A selector, a mark name or an endpoint the store refuses is a usage error like one the
     * parser refuses: the problem and the help to read, the same over HTTP and from the file
     * (cli.adoc#help).
     */
    @Test
    void aValueTheStoreRefusesIsAUsageErrorThatPointsAtTheHelp() {
        List<List<String>> lines = List.of(List.of("findings", "--since=5min"), List.of("mark", "5m"),
                List.of("compare", "--before=now", "--after=5m"), List.of("check", "--endpoint=nosuch"));
        serve(true, (server, base) -> {
            for (List<String> line : lines) {
                List<String> overHttp = new ArrayList<>(line);
                overHttp.add("--url=" + base);
                Run http = runAt(base, overHttp.toArray(new String[0]));
                assertThat(http.exit()).as(line.toString()).isEqualTo(2);
                assertThat(http.err()).as(line.toString()).startsWith("spider-sense: ")
                        .endsWith("\nsee: java -jar spider-sense.jar " + line.get(0) + " --help\n")
                        .hasLineCount(2);
            }
        });
        Run local = run("findings", "--since=5min", "--db=" + TestStore.writtenUrl());
        assertThat(local.exit()).isEqualTo(2);
        assertThat(local.err()).isEqualTo("spider-sense: Not a duration: 5min (a duration is 30s, 5m, 2h or 1d;"
                + " `marks` lists the mark names)\nsee: java -jar spider-sense.jar findings --help\n");
    }

    /** A word whose would-be option name holds whitespace is the command's word, and -- ends the options. */
    @Test
    void aStatementThatBeginsWithACommentIsTheStatement() {
        assertThat(Options.parse(new String[] {"sql", "-- last hour\nSELECT 1", "--limit=5"}).argument())
                .isEqualTo("-- last hour\nSELECT 1");
        assertThat(Options.parse(new String[] {"sql", "--", "--limit=5"}).argument()).isEqualTo("--limit=5");
        assertThat(Options.parse(new String[] {"sql", "--limit=5", "--", "--help"}).command())
                .as("--help after -- is the statement, not a request for help").isEqualTo("sql");
        assertThat(Options.parse(new String[] {"mark", "before", "--note=the slow one"}).valueOrNull("note"))
                .as("whitespace in a value is still an option").isEqualTo("the slow one");
        Run unexpected = run("findings", "--", "--since=5m");
        assertThat(unexpected.exit()).isEqualTo(2);
        assertThat(unexpected.err()).startsWith("spider-sense: unexpected argument: --since=5m\n");
    }

    @Test
    void aNearMissIsWithinTwoEdits() {
        assertThat(Options.distance("sinse", "since")).isEqualTo(1);
        assertThat(Options.distance("sicne", "since")).isEqualTo(2);
        assertThat(Options.distance("", "abc")).isEqualTo(3);
        assertThat(Options.didYouMean("limt", List.of("since", "limit"), "--")).isEqualTo("; did you mean --limit?");
        assertThat(Options.didYouMean("xyz", List.of("since", "limit"), "--")).isEmpty();
    }

    /**
     * An option that takes a value, named bare, is a usage error: a bare {@code --since} would
     * otherwise be the mark name {@code true} and exit 4. A flag is still named bare.
     */
    @Test
    void anOptionThatTakesAValueNamedBareIsAUsageError() {
        for (String option : List.of("since", "until", "limit", "service", "url", "db")) {
            Run run = run("findings", "--" + option);
            assertThat(run.exit()).as(option).isEqualTo(2);
            assertThat(run.err()).as(option)
                    .startsWith("spider-sense: --" + option + " needs a value: --" + option + "=<value>\n");
        }
        for (String option : List.of("since", "url", "db", "service")) {
            Run run = run("findings", "--" + option + "=");
            assertThat(run.exit()).as("an empty --%s=", option).isEqualTo(2);
            assertThat(run.err()).as(option).isEqualTo("spider-sense: --" + option + " needs a value: --"
                    + option + "=<value>\nsee: java -jar spider-sense.jar findings --help\n");
        }
        assertThat(run("export", "--out= ").err()).startsWith("spider-sense: --out needs a value");
        assertThat(Options.parse(new String[] {"mark", "before", "--note="}).has("note"))
                .as("an empty note is no note, not a usage error").isTrue();
        assertThat(run("check", "--max-p95-ms").err()).startsWith("spider-sense: --max-p95-ms needs a value");
        assertThat(Options.parse(new String[] {"findings", "--json", "--full", "--hide-acked", "--no-git"})
                .flag("hide-acked")).isTrue();
        assertThat(Options.parse(new String[] {"init", "--no-skill", "--mcp"}).flag("mcp")).isTrue();
    }

    /** A flag given a value, even an empty one, is a usage error rather than a flag silently off. */
    @Test
    void aFlagGivenAValueIsAUsageError() {
        for (String word : List.of("--json=", "--full=1", "--hide-acked=yes", "--json=true")) {
            Run run = run("findings", word);
            String key = word.substring(2, word.indexOf('='));
            assertThat(run.exit()).as(word).isEqualTo(2);
            assertThat(run.err()).as(word).isEqualTo("spider-sense: --" + key + " takes no value: " + word
                    + "\nsee: java -jar spider-sense.jar findings --help\n");
        }
        assertThat(run("init", "--mcp=no").err()).startsWith("spider-sense: --mcp takes no value: --mcp=no\n");
    }

    @Test
    void everyCommandAsksTheUrlItsSectionOfApiMdNames() {
        assertThat(path("status")).isEqualTo("/api/status?format=text");
        assertThat(path("findings", "--since=before", "--service=orders", "--limit=5"))
                .isEqualTo("/api/findings?since=before&service=orders&limit=5&format=text");
        assertThat(path("findings", "--json")).isEqualTo("/api/findings?since=15m&limit=20&format=json");
        assertThat(path("findings", "--hide-acked"))
                .isEqualTo("/api/findings?since=15m&limit=20&hideAcked=true&format=text");
        assertThat(path("findings", "--no-git")).as("the suspect change is the CLI's own")
                .isEqualTo("/api/findings?since=15m&limit=20&format=text");
        assertThat(path("ack", "slow-endpoint:1a2b3c4d5e6f", "--note=known"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/ack?format=text");
        assertThat(path("unack", "slow-endpoint:1a2b3c4d5e6f"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/ack?format=text");
        assertThat(path("resolve", "slow-endpoint:1a2b3c4d5e6f", "--note=index"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/resolve?format=text");
        assertThat(path("unresolve", "slow-endpoint:1a2b3c4d5e6f"))
                .isEqualTo("/api/findings/slow-endpoint%3A1a2b3c4d5e6f/resolve?format=text");
        assertThat(path("trace", TRACE, "--full"))
                .isEqualTo("/api/traces/" + TRACE + "?full=true&format=text");
        assertThat(path("traces", "--status=error", "--min-ms=100", "--q=orders"))
                .isEqualTo("/api/traces?since=15m&status=error&minMs=100&q=orders&limit=20&format=text");
        assertThat(path("endpoints", "--until=now")).isEqualTo("/api/endpoints?since=15m&until=now&format=text");
        assertThat(path("queries")).isEqualTo("/api/queries?since=15m&limit=100&format=text");
        assertThat(path("errors")).isEqualTo("/api/errors?since=15m&limit=100&format=text");
        assertThat(path("logs", "--severity=WARN", "--trace=" + TRACE))
                .isEqualTo("/api/logs?since=15m&severity=WARN&traceId=" + TRACE + "&limit=200&format=text");
        assertThat(path("mark", "before", "--note=the slow one")).isEqualTo("/api/marks?format=text");
        assertThat(path("marks", "--limit=3")).isEqualTo("/api/marks?limit=3&format=text");
        assertThat(path("compare", "--before=before", "--after=after", "--until=now"))
                .isEqualTo("/api/compare?before=before&after=after&until=now&format=text");
        assertThat(path("check", "--max-p95-ms=500", "--max-n-plus-one=0", "--endpoint=GET /orders"))
                .isEqualTo("/api/check?since=15m&endpoint=GET%20%2Forders&maxP95Ms=500&maxNPlusOne=0&format=text");
        assertThat(path("check", "--min-apdex=0.9", "--max-error-rate=0.01"))
                .isEqualTo("/api/check?since=15m&maxErrorRate=0.01&minApdex=0.9&format=text");
        assertThat(path("check", "--max-p95-ms=1e20")).as("beyond a long, the limit the user typed")
                .isEqualTo("/api/check?since=15m&maxP95Ms=100000000000000000000&format=text");
        assertThat(path("sql", "SELECT 1")).as("the statement travels in the body")
                .isEqualTo("/api/sql?format=text");
        assertThat(path("sql", "SELECT 1", "--json", "--limit=10"))
                .isEqualTo("/api/sql?format=json");
    }

    private static String path(String... args) {
        return Remote.pathAndQuery(Options.parse(args));
    }

    // --- over HTTP -----------------------------------------------------------

    @Test
    void aRunningSpiderSenseAnswersEveryCommandAndTheCliPrintsItVerbatim() {
        serve(true, (server, base) -> {
            assertThat(runAt(base, "status", "--url=" + base).out()).startsWith("# status");
            assertThat(runAt(base, "findings", "--url=" + base).out()).startsWith("# findings  ");
            assertThat(runAt(base, "findings", "--no-git", "--url=" + base).out())
                    .startsWith("# findings  ");
            assertThat(runAt(base, "traces", "--url=" + base).out()).startsWith("# traces  ");
            assertThat(runAt(base, "endpoints", "--url=" + base).out()).startsWith("# endpoints  ");
            assertThat(runAt(base, "queries", "--url=" + base).out()).startsWith("# queries  ");
            assertThat(runAt(base, "errors", "--url=" + base).out()).startsWith("# errors  ");
            assertThat(runAt(base, "logs", "--url=" + base).out()).startsWith("# logs  ");

            Run trace = runAt(base, "trace", TRACE, "--url=" + base);
            assertThat(trace.exit()).isZero();
            assertThat(trace.out()).startsWith("# trace " + TRACE + "  ");
            assertThat(trace.out()).contains("db SELECT order_line  × 6");

            Run json = runAt(base, "findings", "--json", "--url=" + base);
            assertThat(json.exit()).isZero();
            assertThat(Json.parse(json.out()).asObject().getArray("findings").size()).isPositive();
        });
    }

    @Test
    void theExitCodeOfCheckIsTheVerdict() {
        serve(false, (server, base) -> {
            Run nothing = runAt(base, "check", "--url=" + base);
            assertThat(nothing.exit()).as("no request to judge").isEqualTo(3);
            assertThat(nothing.out()).startsWith("# check  ");
        });
        serve(true, (server, base) -> {
            assertThat(runAt(base, "check", "--url=" + base).exit()).as("the defaults").isEqualTo(1);
            assertThat(runAt(base, "check", "--max-errors=0", "--url=" + base).exit())
                    .as("one request failed").isEqualTo(1);
            Run passed = runAt(base, "check", "--max-p95-ms=100000", "--url=" + base);
            assertThat(passed.exit()).isZero();
            assertThat(passed.out()).startsWith("# check  pass");
        });
    }

    @Test
    void whatIsNotThereIsFourAndWhatIsNotASelectorIsTwo() {
        serve(true, (server, base) -> {
            Run missing = runAt(base, "trace", "f".repeat(32), "--url=" + base);
            assertThat(missing.exit()).isEqualTo(4);
            assertThat(missing.err()).contains("f".repeat(32));
            assertThat(missing.out()).isEmpty();

            Run noMark = runAt(base, "findings", "--since=nowhere", "--url=" + base);
            assertThat(noMark.exit()).isEqualTo(4);
            assertThat(noMark.err()).contains("No mark named nowhere");

            Run bad = runAt(base, "findings", "--since=5 minutes", "--url=" + base);
            assertThat(bad.exit()).isEqualTo(2);
            assertThat(bad.err()).contains("Not a time selector");

            Run typo = runAt(base, "findings", "--since=5min", "--url=" + base);
            assertThat(typo.exit()).as("a mistyped duration is not a missing mark").isEqualTo(2);
            assertThat(typo.err()).contains("Not a duration: 5min");

            Run compound = runAt(base, "findings", "--since=1h30m", "--url=" + base);
            assertThat(compound.exit()).as("nor is a compound duration").isEqualTo(2);
            assertThat(compound.err()).contains("Not a duration: 1h30m");
        });
    }

    /**
     * A word no trace id can be names no trace, and is told so before any server or file is asked:
     * over HTTP it would travel as a path segment the server's own checks refuse (cli.adoc#exit-codes).
     */
    @Test
    void aWordThatIsNoTraceIdIsNoSuchTraceWithoutAskingAnyone() {
        String nobody = closedUrl();
        for (String word : List.of("..", ".", "\\", "a\tb", "a".repeat(33), "trace-1")) {
            Run run = run("trace", word, "--url=" + nobody);
            assertThat(run.exit()).as(word).isEqualTo(4);
            assertThat(run.err()).as(word).isEqualTo("spider-sense: No such trace: " + word + "\n");
            assertThat(run.out()).as(word).isEmpty();
        }
    }

    @Test
    void aMarkIsRecordedOverHttpAndListedAgain() {
        serve(false, (server, base) -> {
            Run mark = runAt(base, "mark", "before", "--note=the slow version", "--url=" + base);
            assertThat(mark.exit()).isZero();
            assertThat(mark.out()).startsWith("mark before at ").contains("the slow version");

            assertThat(runAt(base, "marks", "--url=" + base).out()).contains("before");
            assertThat(runAt(base, "mark", "plain", "--note=", "--url=" + base).out())
                    .as("an empty note is no note, and no dash before nothing").matches("mark plain at \\S+\n");
            assertThat(runAt(base, "mark", "two words", "--url=" + base).exit()).isEqualTo(2);

            Run duration = runAt(base, "mark", "5m", "--url=" + base);
            assertThat(duration.exit()).as("a name --since would read as a duration").isEqualTo(2);
            assertThat(duration.err()).contains("A mark cannot be named 5m");
        });
    }

    /** The same pair over HTTP, where the id travels in the path and has a colon in it. */
    @Test
    void aFindingIsAcknowledgedOverHttpAndWithdrawnAgain() {
        serve(true, (server, base) -> {
            String id = Json.parse(runAt(base, "findings", "--json", "--url=" + base).out())
                    .asObject().getArray("findings").get(0).asObject().getString("id");

            Run acked = runAt(base, "ack", id, "--note=known", "--url=" + base);
            assertThat(acked.exit()).isZero();
            assertThat(acked.out()).isEqualTo("acked " + id + " — known\n");

            assertThat(runAt(base, "findings", "--url=" + base).out()).contains("1 acked)");

            Run withdrawn = runAt(base, "unack", id, "--url=" + base);
            assertThat(withdrawn.exit()).isZero();
            assertThat(withdrawn.out()).isEqualTo("unacked " + id + "\n");

            Run twice = runAt(base, "unack", id, "--url=" + base);
            assertThat(twice.exit()).isEqualTo(4);
            assertThat(twice.err()).contains("No such acknowledgement: " + id);

            // cli.adoc#exit-codes: a word that is no finding id is the parser's usage error, never
            // the server's refusal of a path holding it.
            for (String command : List.of("ack", "unack", "resolve", "unresolve")) {
                Run dots = runAt(base, command, "..", "--url=" + base);
                assertThat(dots.exit()).as(command).isEqualTo(2);
                assertThat(dots.err()).as(command).isEqualTo("spider-sense: " + Reports.notAFindingId("..") + "\n"
                        + "see: java -jar spider-sense.jar " + command + " --help\n");
            }
        });
    }

    /** {@code resolve} and {@code unresolve}, over HTTP as {@code ack} and {@code unack} are. */
    @Test
    void aFindingIsResolvedOverHttpAndWithdrawnAgain() {
        serve(true, (server, base) -> {
            String id = Json.parse(runAt(base, "findings", "--json", "--url=" + base).out())
                    .asObject().getArray("findings").get(0).asObject().getString("id");

            Run resolved = runAt(base, "resolve", id, "--note=fixed", "--url=" + base);
            assertThat(resolved.exit()).isZero();
            assertThat(resolved.out()).isEqualTo("resolved " + id + " — fixed\n");

            assertThat(runAt(base, "findings", "--url=" + base).out()).contains("1 resolved)");

            Run withdrawn = runAt(base, "unresolve", id, "--url=" + base);
            assertThat(withdrawn.exit()).isZero();
            assertThat(withdrawn.out()).isEqualTo("unresolved " + id + "\n");

            Run twice = runAt(base, "unresolve", id, "--url=" + base);
            assertThat(twice.exit()).isEqualTo(4);
            assertThat(twice.err()).contains("No such resolution: " + id);
        });
    }

    @Test
    void anExplicitUrlThatAnswersNothingIsAConnectionErrorAndNeverTheFile() {
        String closed = closedUrl();
        Run run = runAt("http://127.0.0.1:1", "status", "--url=" + closed);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).startsWith("spider-sense: no Spider Sense at " + closed + " (")
                .endsWith("); if the application stopped, read its database with --db=<path>\n");
        assertThat(run.out()).isEmpty();
    }

    /**
     * A URL that answers HTTP but is not a Spider Sense, such as another application's port, is
     * the same connection error as a URL that answers nothing: one line and exit 2, never the
     * exit 4 of a trace or mark that is not there, and never the page it answered with.
     */
    @Test
    void anExplicitUrlThatIsNotASpiderSenseIsAConnectionError() throws IOException {
        com.sun.net.httpserver.HttpServer other = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", exchange -> {
            byte[] page = "<!DOCTYPE HTML>\n<html>\n<head><title>Error response</title></head>\n</html>\n"
                    .getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8");
            exchange.sendResponseHeaders(404, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        other.start();
        try {
            String base = "http://127.0.0.1:" + other.getAddress().getPort();
            Map<String, String> paths = Map.of("status", "/api/status", "findings", "/api/findings",
                    "check", "/api/check", "trace", "/api/traces/" + "f".repeat(32));
            for (String command : List.of("status", "findings", "check", "trace")) {
                String path = paths.get(command);
                List<String> args = new ArrayList<>(List.of(command));
                if ("trace".equals(command)) {
                    args.add("f".repeat(32));
                }
                args.add("--url=" + base);
                Run run = runAt(closedUrl(), args.toArray(new String[0]));
                assertThat(run.exit()).as(command).isEqualTo(2);
                assertThat(run.err()).as(command).isEqualTo("spider-sense: no Spider Sense at " + base + " (HTTP 404 for "
                        + path + "); is that the application's port?\n");
                assertThat(run.out()).as(command).isEmpty();
            }
        } finally {
            other.stop(0);
        }
    }

    /**
     * A URL that answers 200 or 301 but not as a Spider Sense does (a frontend dev server's
     * index.html, a redirect to https, a health page) is the same connection error as a 404 from
     * one: never printed as the answer, never a passed check, never a tail that waits in silence,
     * and never a page written to an MCP host as its JSON-RPC answer (cli.adoc#fallback).
     */
    @Test
    void aUrlThatAnswersButNotAsASpiderSenseIsAConnectionErrorAndNeverAPass() throws IOException {
        record Foreign(int status, String type, String body) {
        }
        for (Foreign foreign : List.of(
                new Foreign(200, "text/html", "<!DOCTYPE html><html><body><div id=app></div></body></html>"),
                new Foreign(301, "text/html", ""),
                new Foreign(200, "application/json", "{\"status\":\"UP\"}"))) {
            com.sun.net.httpserver.HttpServer other = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
            other.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = foreign.body().getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", foreign.type());
                exchange.getResponseHeaders().add("Location", "https://127.0.0.1/");
                exchange.sendResponseHeaders(foreign.status(), body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            other.start();
            try {
                String base = "http://127.0.0.1:" + other.getAddress().getPort();
                Map<String, String> paths = Map.of("status", "/api/status", "findings", "/api/findings",
                        "check", "/api/check", "mark", "/api/marks", "export", "/api/export",
                        "tail", "/api/events");
                for (List<String> line : List.of(List.of("status"), List.of("findings"),
                        List.of("check", "--max-errors=0"), List.of("mark", "before"), List.of("export"),
                        List.of("tail", "--timeout=3s"))) {
                    List<String> args = new ArrayList<>(line);
                    args.add("--url=" + base);
                    Run run = runAt(closedUrl(), args.toArray(new String[0]));
                    String as = foreign.status() + " " + line;
                    assertThat(run.exit()).as(as).isEqualTo(2);
                    assertThat(run.out()).as(as).isEmpty();
                    assertThat(run.err()).as(as).isEqualTo("spider-sense: no Spider Sense at " + base + " (HTTP "
                            + foreign.status() + " for " + paths.get(line.get(0)) + "); is that the application's port?\n");
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                        PrintStream toErr = new PrintStream(new ByteArrayOutputStream(), true, UTF_8)) {
                    Cli.run(new String[] {"mcp", "--url=" + base}, closedUrl(), new java.io.ByteArrayInputStream(
                            ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                                    + "{\"name\":\"status\",\"arguments\":{}}}\n").getBytes(UTF_8)), toOut, toErr);
                }
                JsonObject answer = Json.parse(out.toString(UTF_8)).asObject();
                assertThat(answer.getLong("id")).as(foreign.status() + " mcp: the call is answered").isEqualTo(1);
                assertThat(answer.toJson()).as(foreign.status() + " mcp").contains("no Spider Sense at " + base);
            } finally {
                other.stop(0);
            }
        }
    }

    /**
     * A server that answers every path with the type asked for, a 204 to a DELETE and an error in
     * Spider Sense's own shape is still not one without the {@code X-Spider-Sense} header: its JSON
     * is never printed as a {@code --json} answer (cli.adoc#fallback).
     */
    @Test
    void anAnswerWithoutTheSpiderSenseHeaderIsAnotherServersWhateverItsShape() throws IOException {
        com.sun.net.httpserver.HttpServer other = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            boolean error = path.startsWith("/api/traces/");
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            byte[] body = (error ? "{\"error\":\"No such trace\"}"
                    : query.contains("format=json") ? "{\"findings\":[]}" : "# findings\n").getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    error || query.contains("format=json") ? "application/json" : "text/markdown; charset=utf-8");
            exchange.getResponseHeaders().add(AgentApi.PASS_HEADER, "true");
            exchange.sendResponseHeaders(error ? 404 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        other.start();
        try {
            String base = "http://127.0.0.1:" + other.getAddress().getPort();
            Map<List<String>, String> lines = new java.util.LinkedHashMap<>();
            lines.put(List.of("findings", "--json"), "/api/findings");
            lines.put(List.of("findings"), "/api/findings");
            lines.put(List.of("check"), "/api/check");
            lines.put(List.of("unack", "error:3d871f14ee3e"), "/api/findings/error%3A3d871f14ee3e/ack");
            lines.put(List.of("trace", "f".repeat(32)), "/api/traces/" + "f".repeat(32));
            lines.forEach((line, path) -> {
                List<String> args = new ArrayList<>(line);
                args.add("--url=" + base);
                Run run = runAt(closedUrl(), args.toArray(new String[0]));
                assertThat(run.exit()).as(line.toString()).isEqualTo(2);
                assertThat(run.out()).as(line.toString()).isEmpty();
                assertThat(run.err()).as(line.toString()).isEqualTo("spider-sense: no Spider Sense at " + base
                        + " (HTTP " + ("trace".equals(line.get(0)) ? 404 : "unack".equals(line.get(0)) ? 204 : 200)
                        + " for " + path + "); is that the application's port?\n");
            });
        } finally {
            other.stop(0);
        }
    }

    /** api.adoc#conventions: every answer is marked, an error and the single page included. */
    @Test
    void everyAnswerOfTheServerCarriesTheSpiderSenseHeader() {
        serve(false, (server, base) -> {
            try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
                for (String path : List.of("/api/status", "/api/nothing-here", "/", "/traces", "/favicon.svg",
                        "/api/traces/x%5Cy", "/api/findings?since=nowhere")) {
                    java.net.http.HttpResponse<String> response = client.send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(base + path)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.headers().firstValue(Version.HEADER)).as(path + " " + response.statusCode())
                            .hasValue(Version.CURRENT);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * A --url with a path is a usage error rather than a prefix whose 404 reads as a missing
     * trace, and a Spider Sense's own 404 for a route it does not have is one of another version,
     * not a missing object (cli.adoc#fallback).
     */
    @Test
    void aUrlWithAPathIsAUsageErrorAndAMissingRouteIsAnotherVersion() throws IOException {
        serve(false, (server, base) -> {
            Run withPath = runAt(closedUrl(), "status", "--url=" + base + "/api");
            assertThat(withPath.exit()).isEqualTo(2);
            assertThat(withPath.err()).isEqualTo("spider-sense: --url is a base URL, such as http://127.0.0.1:4000,"
                    + " with no path: " + base + "/api\nsee: java -jar spider-sense.jar status --help\n");
            assertThat(withPath.out()).isEmpty();
        });

        com.sun.net.httpserver.HttpServer older = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        older.createContext("/", exchange -> {
            byte[] body = ("{\"error\":\"Not found: " + exchange.getRequestURI().getPath() + "\"}").getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add(Version.HEADER, "0.1.0");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        older.start();
        try {
            String base = "http://127.0.0.1:" + older.getAddress().getPort();
            Run run = runAt(closedUrl(), "check", "--url=" + base);
            assertThat(run.exit()).as("not the 4 of a missing trace or mark").isEqualTo(2);
            assertThat(run.err()).isEqualTo("spider-sense: no Spider Sense at " + base
                    + " (HTTP 404 for /api/check); is it a Spider Sense of another version?\n");
        } finally {
            older.stop(0);
        }
    }

    /**
     * A Spring application's JSON 404 carries an {@code error} too, but beside a status and a
     * path: it is the application's port, not a missing trace, and a URL typed without its scheme
     * is still that server (cli.adoc#fallback).
     */
    @Test
    void anApplicationsJsonErrorIsNotASpiderSensesAndAUrlNeedsNoScheme() throws IOException {
        com.sun.net.httpserver.HttpServer app = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        app.createContext("/", exchange -> {
            byte[] body = ("{\"timestamp\":\"2026-10-08T00:00:00.000+00:00\",\"status\":404,"
                    + "\"error\":\"Not Found\",\"path\":\"/api/status\"}").getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        app.start();
        try {
            String hostAndPort = "127.0.0.1:" + app.getAddress().getPort();
            Run run = runAt(closedUrl(), "status", "--url=" + hostAndPort);
            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.err()).isEqualTo("spider-sense: no Spider Sense at http://" + hostAndPort
                    + " (HTTP 404 for /api/status); is that the application's port?\n");
            assertThat(run.out()).isEmpty();
        } finally {
            app.stop(0);
        }
    }

    /**
     * A threshold that is not a whole number is the same usage error over HTTP and from the
     * file, rather than a 400 from the server in one mode and a truncated threshold in the other.
     */
    @Test
    void aFractionalMinMsIsOneUsageErrorInBothModes() {
        String expected = "spider-sense: --min-ms is not a whole number: 1.5\n";
        serve(false, (server, base) -> {
            Run remote = runAt(base, "traces", "--min-ms=1.5", "--url=" + base);
            assertThat(remote.exit()).isEqualTo(2);
            assertThat(remote.err()).startsWith(expected);
        });
        Run local = run("traces", "--min-ms=1.5", "--db=" + TestStore.writtenUrl());
        assertThat(local.exit()).isEqualTo(2);
        assertThat(local.err()).startsWith(expected);
        assertThat(local.out()).isEmpty();

        Run tail = run("tail", "--until-traces=2.5", "--url=" + closedUrl());
        assertThat(tail.exit()).isEqualTo(2);
        assertThat(tail.err()).startsWith("spider-sense: --until-traces is not a whole number: 2.5\n");
    }

    /**
     * A threshold that is not a whole number is the same usage error whichever mode would have
     * answered, and one a server answered without is said to have changed nothing, since the
     * thresholds are the file path's settings (cli.adoc#options).
     */
    @Test
    void aThresholdIsCheckedInBothModesAndSaidToChangeNothingWhenAServerAnswers() {
        serve(false, (server, base) -> {
            Run refused = runAt(base, "findings", "--no-git", "--slow.query.ms=abc", "--url=" + base);
            assertThat(refused.exit()).isEqualTo(2);
            assertThat(refused.err()).isEqualTo("""
                    spider-sense: --slow.query.ms is not a whole number: abc
                    see: java -jar spider-sense.jar findings --help
                    """);
            assertThat(refused.out()).isEmpty();

            Run answered = runAt(base, "endpoints", "--slow.request.ms=50", "--app.packages=orders",
                    "--url=" + base);
            assertThat(answered.exit()).isZero();
            assertThat(answered.err()).isEqualTo("(the Spider Sense at " + base + " answered with its own "
                    + "settings; --slow.request.ms and --app.packages apply only when the file is read)\n");
        });
        Run local = run("endpoints", "--slow.request.ms=1x", "--db=" + TestStore.writtenUrl());
        assertThat(local.exit()).isEqualTo(2);
        assertThat(local.err()).isEqualTo("""
                spider-sense: --slow.request.ms is not a whole number: 1x
                see: java -jar spider-sense.jar endpoints --help
                """);
    }

    /**
     * A status or a severity the store does not know is a usage error listing what it takes, in
     * both modes, rather than a filter that narrows nothing; the case of a known one is free.
     */
    @Test
    void aStatusOrASeverityOutsideItsListIsOneUsageErrorInBothModes() {
        String status = "spider-sense: --status is one of error, ok: bad\n";
        String severity = "spider-sense: --severity is one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL: bogus\n";
        serve(false, (server, base) -> {
            assertThat(runAt(base, "traces", "--status=bad", "--url=" + base).err()).startsWith(status);
            assertThat(runAt(base, "logs", "--severity=bogus", "--url=" + base).err()).startsWith(severity);
        });
        String db = "--db=" + TestStore.writtenUrl();
        Run traces = run("traces", "--status=bad", db);
        assertThat(traces.exit()).isEqualTo(2);
        assertThat(traces.err()).startsWith(status);
        Run logs = run("logs", "--severity=bogus", db);
        assertThat(logs.exit()).isEqualTo(2);
        assertThat(logs.err()).startsWith(severity);

        assertThat(path("logs", "--severity=warn")).isEqualTo("/api/logs?since=15m&severity=WARN&limit=200&format=text");
        assertThat(path("traces", "--status=ERROR")).isEqualTo("/api/traces?since=15m&status=error&limit=20&format=text");
    }

    /**
     * A limit below 1 is refused rather than raised to 1, so {@code sql --limit=0} is the error
     * cli.adoc#sql promises; a limit above a list's cap is lowered to it (cli.adoc#options).
     */
    @Test
    void aLimitBelowOneIsAUsageErrorAndOneAboveTheCapIsLowered() {
        String db = "--db=" + TestStore.writtenUrl();
        for (String limit : List.of("0", "-5")) {
            Run run = run("sql", "SELECT 1", "--limit=" + limit, db);
            assertThat(run.exit()).as(limit).isEqualTo(2);
            assertThat(run.err()).as(limit).startsWith("spider-sense: --limit must be at least 1: " + limit + "\n");
            assertThat(run.out()).as(limit).isEmpty();
        }
        assertThat(run("marks", "--limit=1.5", db).err())
                .startsWith("spider-sense: --limit is not a whole number: 1.5\n");

        Options marks = Options.parse(new String[] {"marks", "--limit=1000"});
        assertThat(marks.limit(net.benelog.spidersense.api.Limits.MARKS, net.benelog.spidersense.api.Limits.MARKS_MAX))
                .isEqualTo(500);
        Options huge = Options.parse(new String[] {"logs", "--limit=99999999999"});
        assertThat(huge.limit(net.benelog.spidersense.api.Limits.LOGS, net.benelog.spidersense.api.Limits.LOGS_MAX))
                .as("past an int, still the cap").isEqualTo(5000);
    }

    // --- from the file -------------------------------------------------------

    @Test
    void theDatabaseAnswersEveryCommandWithNoServerRunning() {
        String db = "--db=" + TestStore.writtenUrl();

        Run status = run("status", db);
        assertThat(status.exit()).isZero();
        assertThat(status.out()).startsWith("# status").contains("mode").contains("file");

        Run mark = run("mark", "before", "--note=the slow version", db);
        assertThat(mark.exit()).isZero();
        assertThat(mark.out()).startsWith("mark before at ");

        Run marks = run("marks", db);
        assertThat(marks.exit()).isZero();
        assertThat(marks.out()).startsWith("# marks").contains("before").contains("the slow version");

        assertThat(run("findings", db).out()).startsWith("# findings  ");
        assertThat(run("findings", "--since=before", db).exit()).isZero();
        assertThat(run("endpoints", db).out()).startsWith("# endpoints  ");
        assertThat(run("queries", db).out()).startsWith("# queries  ");
        assertThat(run("errors", db).out()).startsWith("# errors  ");
        assertThat(run("logs", db).out()).startsWith("# logs  ");
        assertThat(run("traces", db).out()).startsWith("# traces  ");
        assertThat(run("compare", "--before=10m", "--after=5m", db).out()).startsWith("# compare  ");

        Run check = run("check", db);
        assertThat(check.exit()).as("nothing was requested").isEqualTo(3);

        Run missing = run("trace", "f".repeat(32), db);
        assertThat(missing.exit()).isEqualTo(4);
        assertThat(missing.err()).contains("No such trace");

        Run json = run("marks", "--json", db);
        assertThat(Json.parse(json.out()).asObject().getArray("marks")).hasSize(1);

        assertThat(run("findings", "--since=nowhere", db).exit()).isEqualTo(4);
        assertThat(run("mark", "two words", db).exit()).isEqualTo(2);
    }

    /**
     * The suspect change is the one addition the CLI makes to an answer, to the findings text as
     * rendered and to nothing else (cli.adoc#suspect-change); no git repository is needed to see it.
     */
    @Test
    void theFindingsTextAndNothingElseGetsTheSuspectChanges() {
        String db = "--db=" + TestStore.writtenUrl();
        String line = "   ↳ changed in abc1234 (2 hours ago): Load orders by id\n";
        List<String> given = new ArrayList<>();
        Function<String, String> annotate = text -> {
            given.add(text);
            return text + line;
        };

        Run annotated = runAnnotating(annotate, "findings", db);
        assertThat(annotated.exit()).isZero();
        assertThat(given).hasSize(1);
        assertThat(given.get(0)).as("the text as rendered").startsWith("# findings  ");
        assertThat(annotated.out()).isEqualTo(given.get(0) + line);

        assertThat(runAnnotating(annotate, "findings", "--no-git", db).out()).doesNotContain(line);
        assertThat(runAnnotating(annotate, "findings", "--json", db).out()).doesNotContain(line);
        assertThat(runAnnotating(annotate, "endpoints", db).out()).doesNotContain(line);
        assertThat(runAnnotating(annotate, "findings", "--since=nowhere", db).exit()).isEqualTo(4);
        assertThat(given).as("a findings text only, and only one that was answered").hasSize(1);
    }

    /**
     * Acknowledging from the file, which is where an agent does it: the loop runs
     * against a crashed application as readily as against a live one (cli.adoc).
     */
    @Test
    void ackAndUnackAreWrittenToTheFileAndShowUpInFindings() {
        String url = TestStore.memoryUrl();
        String db = "--db=" + url;
        try (Store store = new Store(Store.Settings.defaults(url))) {
            new OtlpDecoder(store, () -> 4000).ingest(sample());
            store.writer().awaitIdle(5_000);
        }

        String id = Json.parse(run("findings", "--json", db).out()).asObject()
                .getArray("findings").get(0).asObject().getString("id");

        Run acked = run("ack", id, "--note=slow by design", db);
        assertThat(acked.exit()).isZero();
        assertThat(acked.out()).isEqualTo("acked " + id + " — slow by design\n");

        Run listed = run("findings", db);
        assertThat(listed.exit()).isZero();
        assertThat(listed.out()).contains("1 acked)");
        assertThat(listed.out()).contains("| acked | ");
        assertThat(run("findings", "--hide-acked", db).out()).doesNotContain(id);

        Run withdrawn = run("unack", id, db);
        assertThat(withdrawn.exit()).isZero();
        assertThat(withdrawn.out()).isEqualTo("unacked " + id + "\n");

        Run twice = run("unack", id, db);
        assertThat(twice.exit()).isEqualTo(4);
        assertThat(twice.err()).contains("No such acknowledgement: " + id);
        assertThat(twice.out()).isEmpty();

        assertThat(run("unack", db).exit()).as("the id is the argument").isEqualTo(2);

        Run resolved = run("resolve", id, "--note=fetch join", db);
        assertThat(resolved.exit()).isZero();
        assertThat(resolved.out()).isEqualTo("resolved " + id + " — fetch join\n");
        assertThat(run("findings", db).out()).contains("1 resolved)").contains("| resolved | ");
        assertThat(run("findings", "--hide-acked", db).out()).doesNotContain(id);
        assertThat(run("unresolve", id, db).out()).isEqualTo("unresolved " + id + "\n");
        Run none = run("unresolve", id, db);
        assertThat(none.exit()).isEqualTo(4);
        assertThat(none.err()).contains("No such resolution: " + id);

        // findings.adoc#acknowledgements: an id of another table is a usage error, an id of no
        // finding is not found, and neither is recorded.
        String tableId = id.substring(id.indexOf(':') + 1);
        for (String command : List.of("ack", "resolve")) {
            Run malformed = run(command, tableId, db);
            assertThat(malformed.exit()).as(command).isEqualTo(2);
            assertThat(malformed.err()).contains("Not a finding id: " + tableId + ".")
                    .contains("the ids of errors, queries and endpoints are table ids");
            Run unknown = run(command, "error:000000000000", db);
            assertThat(unknown.exit()).as(command).isEqualTo(4);
            assertThat(unknown.err()).contains("No finding error:000000000000 in the data kept");
        }
        try (Store store = new Store(Store.Settings.defaults(url))) {
            assertThat(store.sql().count("SELECT COUNT(*) FROM ack", List.of())).isZero();
        }
    }

    @Test
    void sqlIsAnsweredOverHttpAndRefusedWhenItIsNotARead() {
        serve(true, (server, base) -> {
            Run rows = runAt(base, "sql",
                    "SELECT service, COUNT(*) AS spans FROM span GROUP BY service", "--url=" + base);
            assertThat(rows.exit()).isZero();
            assertThat(rows.out()).startsWith("# sql  1 row");
            assertThat(rows.out()).contains("| SERVICE | SPANS |").contains("| orders |");

            Run json = runAt(base, "sql", "SELECT COUNT(*) AS spans FROM span", "--json",
                    "--url=" + base);
            assertThat(json.exit()).isZero();
            assertThat(Json.parse(json.out()).asObject().getLong("rowCount")).isEqualTo(1);

            Run capped = runAt(base, "sql", "SELECT id FROM span ORDER BY id", "--limit=2",
                    "--url=" + base);
            assertThat(capped.out()).startsWith("# sql  2 rows (truncated at 2)");

            Run commented = runAt(base, "sql", "-- the spans\nSELECT COUNT(*) AS spans FROM span",
                    "--url=" + base);
            assertThat(commented.exit()).as("a leading SQL comment is the statement's, not an option")
                    .isZero();
            assertThat(commented.out()).startsWith("# sql  1 row");
            assertThat(runAt(base, "sql", "--url=" + base, "--", "--x\nSELECT 1").exit())
                    .as("after --, every word is the statement").isZero();

            Run refused = runAt(base, "sql", "DELETE FROM span", "--url=" + base);
            assertThat(refused.exit()).isEqualTo(2);
            assertThat(refused.out()).isEmpty();
            assertThat(refused.err()).as("the server's own line, not a status code, and the help to read")
                    .isEqualTo("spider-sense: only EXPLAIN, SELECT, SHOW, TABLE, VALUES, WITH"
                            + " may be run here, and this statement starts with DELETE\n"
                            + "see: java -jar spider-sense.jar sql --help\n");
        });
    }

    /**
     * A JDBC URL that names no file has no existence to check, and the CLI creates no database
     * behind it to answer "0 requests" from: it says the database is not Spider Sense's.
     */
    @Test
    void aJdbcUrlWithNoSpiderSenseSchemaBehindItIsRefused() {
        Run run = run("findings", "--since=1h", "--db=" + TestStore.memoryUrl());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("no Spider Sense schema");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void sqlFromTheFileNeedsTheReaderUserAServerCreates() {
        String url = TestStore.writtenUrlWithoutReader();

        Run tooEarly = run("sql", "SELECT 1", "--db=" + url);
        assertThat(tooEarly.exit()).isEqualTo(2);
        assertThat(tooEarly.err())
                .contains("the database has no read-only user yet")
                .contains("start an application or the standalone server with this version first");
        assertThat(tooEarly.out()).isEmpty();

        try (Database opened = Database.open(url, null)) {
            opened.sql().update("INSERT INTO mark (at_ms, name) VALUES (1, 'before')", List.of());

            Run rows = run("sql", "SELECT name, at_ms FROM mark ORDER BY at_ms", "--db=" + url);
            assertThat(rows.exit()).isZero();
            assertThat(rows.out()).startsWith("# sql  1 row");
            assertThat(rows.out()).contains("| NAME | AT_MS |").contains("| before | 1 |");

            Run refused = run("sql", "DROP TABLE mark", "--db=" + url);
            assertThat(refused.exit()).isEqualTo(2);
            assertThat(run("sql", "SELECT COUNT(*) AS marks FROM mark", "--db=" + url).out())
                    .contains("| 1 |");
        }
    }

    /**
     * The one line cli.adoc#fallback prints when it falls back, and the reason it exists:
     * an agent must never mistake a file for a live server.
     */
    @Test
    void nothingListeningAndNoUrlFallsBackToTheFileAndSaysSoOnStderr() {
        String memory = TestStore.writtenUrl();
        System.setProperty("spidersense.db", memory);
        try {
            String closed = closedUrl();
            Run run = runAt(closed, "status");
            assertThat(run.exit()).isZero();
            assertThat(run.err())
                    .isEqualTo("(no Spider Sense at " + closed + "; reading " + memory + " directly)\n");
            assertThat(run.out()).startsWith("# status");
        } finally {
            System.clearProperty("spidersense.db");
        }
    }

    /**
     * The files of each packaged skill, keyed by the skill's directory: what the build's
     * {@code skillIndex} task wrote beside them, read here rather than pinned, so that a
     * new reference page — or a whole new skill under {@code skills/} — does not break
     * this test. The order is the order {@code init} prints, which is by skill name
     * (agent-skill.adoc#init).
     */
    private static Map<String, Long> packagedSkills() throws IOException {
        String index = new String(Objects.requireNonNull(
                CliTest.class.getResourceAsStream("/spider-sense/skills/index.txt"), "skill index")
                .readAllBytes(), UTF_8);
        return index.lines()
                .map(String::trim)
                .filter(line -> line.contains("/"))
                .collect(java.util.stream.Collectors.groupingBy(
                        line -> line.substring(0, line.indexOf('/')),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
    }

    /**
     * {@code init} is the one command that asks nothing and only writes: the block into
     * the project's CLAUDE.md, and the skills beside it (agent-skill.adoc#init).
     */
    @Test
    void initWritesTheBlockAndInstallsEverySkill(@TempDir Path project) throws IOException {
        Run init = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");
        assertThat(init.exit()).as("stderr: %s", init.err()).isZero();

        Map<String, Long> skills = packagedSkills();
        assertThat(skills.keySet())
                .as("every directory under skills/ travels in the jar")
                .contains("spider-sense", "spider-sense-sql-tuning");
        StringBuilder expected = new StringBuilder("wrote CLAUDE.md block (jar: /x/spider-sense.jar)\n");
        skills.forEach((skill, files) -> expected
                .append("installed skill to ").append(project.resolve(".claude/skills").resolve(skill))
                .append(" (").append(files).append(" files)\n"));
        assertThat(init.out()).isEqualTo(expected.toString());

        String claude = Files.readString(project.resolve("CLAUDE.md"), UTF_8);
        assertThat(claude).startsWith("<!-- spider-sense:start -->\n## Spider Sense\n");
        assertThat(claude).endsWith("<!-- spider-sense:end -->\n");
        assertThat(claude)
                .contains("java -javaagent:/x/spider-sense.jar -jar <app jar>")
                .contains("./gradlew bootRun -PspiderSense.jar=/x/spider-sense.jar")
                .contains("JAVA_TOOL_OPTIONS=\"-javaagent:/x/spider-sense.jar\" <command>")
                .contains("`--no-daemon`")
                .doesNotContain("JAVA_TOOL_OPTIONS=\"-javaagent:/x/spider-sense.jar\" ./gradlew")
                .contains("java -jar /x/spider-sense.jar findings --since=start")
                .as("the window to use before an application has left a start mark")
                .contains("java -jar /x/spider-sense.jar findings --since=15m  # the last 15 minutes instead")
                .contains("<http://127.0.0.1:4000>")
                .contains("The loop — start, mark, exercise, findings, fix, compare, check — is in the "
                        + "skill at `.claude/skills/spider-sense/SKILL.md`.")
                .contains("Query tuning — an index to add, a rewrite, a fetch join, a batch — is in the "
                        + "skill at `.claude/skills/spider-sense-sql-tuning/SKILL.md`.");

        assertThat(project.resolve(".claude/skills/spider-sense/SKILL.md")).isRegularFile();
        assertThat(project.resolve(".claude/skills/spider-sense/references/cli.md")).isRegularFile();
        assertThat(project.resolve(".claude/skills/spider-sense-sql-tuning/SKILL.md")).isRegularFile();
        assertThat(Files.readString(project.resolve(".claude/skills/spider-sense/SKILL.md"), UTF_8))
                .as("the repository's skill, copied verbatim").contains("name: spider-sense");
    }

    /**
     * A second {@code init} overwrites the files it wrote before and leaves anything else
     * in those directories alone: an agent may run it whenever it is unsure.
     */
    @Test
    void aSecondInitRewritesTheSkillsAndKeepsWhatIsNotOurs(@TempDir Path project) throws IOException {
        run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");
        Path skill = project.resolve(".claude/skills/spider-sense/SKILL.md");
        Path mine = project.resolve(".claude/skills/spider-sense/references/notes.md");
        Files.writeString(skill, "clobbered\n", UTF_8);
        Files.writeString(mine, "my own\n", UTF_8);

        Run again = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar");

        assertThat(again.exit()).as("stderr: %s", again.err()).isZero();
        assertThat(again.out().lines())
                .as("one line for the block and one for each skill")
                .hasSize(1 + packagedSkills().size());
        assertThat(again.out()).startsWith("updated CLAUDE.md block (jar: /x/spider-sense.jar)\n");
        assertThat(again.out()).contains("installed skill to "
                + project.resolve(".claude/skills/spider-sense-sql-tuning"));
        assertThat(Files.readString(skill, UTF_8))
                .as("the file init owns is written again").contains("name: spider-sense");
        assertThat(Files.readString(mine, UTF_8)).as("not ours to remove").isEqualTo("my own\n");
        assertThat(project.resolve(".claude/skills/spider-sense-sql-tuning/SKILL.md")).isRegularFile();
    }

    /**
     * A second {@code init} is a replacement, not a second copy: everything outside the
     * two markers comes through byte for byte, because the file belongs to the project.
     */
    @Test
    void aSecondInitReplacesTheBlockAndLeavesTheRestOfTheFileAlone(@TempDir Path project) throws IOException {
        Path claude = project.resolve("CLAUDE.md");
        Files.writeString(claude, "# My project\n\nOne rule: no reflection.\n", UTF_8);

        assertThat(run("init", "--dir=" + project, "--jar=/x/spider-sense.jar").out())
                .startsWith("wrote CLAUDE.md block");
        Files.writeString(claude,
                Files.readString(claude, UTF_8) + "\n## Afterwards\n\nKeep me exactly as I am.\n", UTF_8);

        Run again = run("init", "--dir=" + project, "--jar=/y/other/spider-sense.jar");
        assertThat(again.exit()).isZero();
        assertThat(again.out()).startsWith("updated CLAUDE.md block (jar: /y/other/spider-sense.jar)");

        String text = Files.readString(claude, UTF_8);
        assertThat(text).startsWith("# My project\n\nOne rule: no reflection.\n\n<!-- spider-sense:start -->");
        assertThat(text).endsWith("<!-- spider-sense:end -->\n\n## Afterwards\n\nKeep me exactly as I am.\n");
        assertThat(text).contains("/y/other/spider-sense.jar").doesNotContain("/x/spider-sense.jar");
        assertThat(text.split(java.util.regex.Pattern.quote(Init.START), -1))
                .as("one block, not two").hasSize(2);
    }

    @Test
    void initWithoutTheSkillsWritesNoClaudeDirectory(@TempDir Path project) throws IOException {
        Run init = run("init", "--dir=" + project, "--jar=/x/spider-sense.jar", "--no-skill");
        assertThat(init.exit()).isZero();
        assertThat(init.out()).endsWith("skipped skills (--no-skill)\n");
        assertThat(project.resolve(".claude")).doesNotExist();
        assertThat(Files.readString(project.resolve("CLAUDE.md"), UTF_8))
                .as("the block points at the repository instead")
                .contains("<https://github.com/benelog/spider-sense>")
                .contains("`skills/spider-sense-sql-tuning/` of the same repository")
                .doesNotContain("`.claude/skills/spider-sense/SKILL.md`")
                .doesNotContain("`.claude/skills/spider-sense-sql-tuning/SKILL.md`");
    }

    /** Exploded classes and no {@code --jar}: nobody knows the path, and inventing one would be worse. */
    @Test
    void initWithNoJarToNameIsAUsageError(@TempDir Path project) {
        String remembered = System.getProperty(Init.JAR_PROPERTY);
        System.clearProperty(Init.JAR_PROPERTY);
        try {
            Run init = run("init", "--dir=" + project);
            assertThat(init.exit()).isEqualTo(2);
            assertThat(init.err()).contains("--jar=<path>");
            assertThat(init.out()).isEmpty();
            assertThat(project.resolve("CLAUDE.md")).doesNotExist();
        } finally {
            if (remembered != null) {
                System.setProperty(Init.JAR_PROPERTY, remembered);
            }
        }
    }
}
