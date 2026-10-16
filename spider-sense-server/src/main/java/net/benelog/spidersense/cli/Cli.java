package net.benelog.spidersense.cli;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.source.SourceRoots;
import net.benelog.spidersense.store.Importer;
import org.jspecify.annotations.Nullable;

/**
 * {@code java -jar spider-sense.jar findings --since=start}: the agent interface
 * for everything with a shell.
 *
 * <p>The launcher finds this class in the nested server jar and calls
 * {@link #run(String[])} reflectively, exiting with what it returns, so the whole
 * command line lives on this side of the class loader and the launcher keeps its
 * one rule of having no dependencies (design.adoc#jar).
 *
 * <p>There are two ways to answer and one set of answers: {@link Remote} asks a
 * running Spider Sense over HTTP, {@link Local} opens the H2 file in process when
 * nothing answers. The fallback is silent about nothing — it says on stderr which
 * file it read, because an agent must never mistake yesterday's database for a
 * live one.
 */
public final class Cli {

    static final int OK = 0;
    static final int CHECK_FAILED = 1;
    static final int USAGE = 2;
    static final int NO_REQUESTS = 3;
    static final int NOT_FOUND = 4;

    /**
     * Where a Spider Sense is when nobody said; {@code SPIDERSENSE_URL} overrides it, and so do
     * the {@code spidersense.*} properties, see {@link #configuredUrl}.
     */
    static final String DEFAULT_URL = "http://" + Config.DEFAULT_HOST + ":" + Config.DEFAULT_PORT;

    /**
     * What a named {@code --url} that nothing answers adds: the way to the data that outlived the
     * application (cli.adoc#fallback).
     */
    static final String STOPPED = "; if the application stopped, read its database with --db=<path>";

    /** What a stored trace id can be: hex digits, 32 of them as OTLP writes one (api.adoc#conventions). */
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{1,32}");

    private Cli() {
    }

    /**
     * The entry point the launcher invokes.
     *
     * <p>Every answer is UTF-8 by contract, as the {@code text/markdown; charset=utf-8} of a server
     * and the JSON-RPC of {@code mcp} are, so it goes into the bytes of the process's streams as
     * UTF-8 rather than in the charset the platform gave them: a JVM started without {@code LANG},
     * or one on Windows whose stdout is a pipe, would print every {@code →}, {@code —} and
     * {@code …} as {@code ?} (cli.adoc#text-rendering).
     */
    public static int run(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            return run(args, defaultUrl(err::println), System.in, out, err);
        } finally {
            out.flush();
            err.flush();
        }
    }

    /**
     * The same command with the streams and the default URL named, which is how a
     * test can point the CLI at a port nothing listens on without setting an
     * environment variable.
     */
    static int run(String[] args, String defaultUrl, PrintStream out, PrintStream err) {
        return run(args, defaultUrl, InputStream.nullInputStream(), out, err);
    }

    /** With stdin too, which only {@code mcp} reads. */
    static int run(String[] args, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err) {
        return run(args, defaultUrl, in, out, err, findings -> withSuspectChanges(findings, err));
    }

    /**
     * With what adds the suspect changes to a {@code findings} text named too, which is how a test
     * checks the addition without a git repository around it.
     *
     * @param annotateFindings the {@code findings} text as printed, from the text as rendered; in
     *                         production {@link #withSuspectChanges}
     */
    static int run(String[] args, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err, Function<String, String> annotateFindings) {
        quietLoggingUnlessTold();
        Options options;
        try {
            options = Options.parse(args);
        } catch (Options.Usage e) {
            return usage(e, e.command(), err);
        }
        if (Options.HELP.equals(options.command())) {
            String about = options.argument();
            out.println(about == null ? Help.TEXT : Help.of(Objects.requireNonNull(Command.named(about),
                    "help was parsed with a command that is none")));
            return OK;
        }
        if (Options.VERSION.equals(options.command())) {
            // What the launcher's --version prints, from the same version (design.adoc#jar).
            out.println(Version.NAME + " " + Version.CURRENT);
            return OK;
        }
        try {
            return dispatch(options, defaultUrl, in, out, err, annotateFindings);
        } catch (Options.Usage e) {
            return usage(e, options.command(), err);
        } catch (Reports.NoSuchTrace | Reports.NoSuchFinding | Selectors.UnknownMark e) {
            err.println("spider-sense: " + e.getMessage());
            return NOT_FOUND;
        } catch (Importer.WrongSchema | Importer.BadDocument | Sessions.Undecodable | UncheckedIOException e) {
            // A document import refuses, a file that cannot be read or written: the server's 400
            // says the sentence alone, and so does the file path.
            err.println("spider-sense: " + e.getMessage());
            return USAGE;
        } catch (Selectors.BadSelector | IllegalArgumentException e) {
            // A bad selector, a bad mark name, a statement sql refuses: usage errors the store
            // finds rather than the parser, which the server answers with a 400, so both modes
            // print the message and the help to read (cli.adoc#help).
            err.println("spider-sense: " + e.getMessage());
            err.println(pointer(options.command()));
            return USAGE;
        } catch (IllegalStateException e) {
            // A missing or foreign database file: the message says what to do, the class name
            // would not, and no help would.
            err.println("spider-sense: " + e.getMessage());
            return USAGE;
        } catch (RuntimeException e) {
            err.println("spider-sense: " + e);
            return USAGE;
        }
    }

    /**
     * HTTP first, the file second — unless {@code --db} named a file, which is a
     * statement about where to read and leaves no question to ask a server, or
     * unless {@code --url} named a server, which is a statement that there is one:
     * falling back then would answer a different question from the one asked.
     *
     * <p>{@code init} comes before all of it: it asks nothing and nobody, it only
     * writes (agent-skill.adoc#init). {@code mcp} comes before it too, because it is a session
     * of messages rather than one question, and it makes the same choice again for
     * each tool call it is asked to answer.
     */
    private static int dispatch(Options options, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err, Function<String, String> annotateFindings) {
        if (Options.INIT.equals(options.command())) {
            return Init.run(options, out, err);
        }
        if (Options.MCP.equals(options.command())) {
            return Mcp.run(options, defaultUrl, in, out, err);
        }
        if (Options.TAIL.equals(options.command())) {
            return Tail.run(options, defaultUrl, out, err);
        }
        if (Options.FINDINGS.equals(options.command()) && !options.has("json")
                && !options.has("no-git")) {
            return findingsWithSuspects(options, defaultUrl, out, err, annotateFindings);
        }
        return answer(options, defaultUrl, out, err);
    }

    /**
     * {@code findings} as the renderer produced it, with the suspect change under each code
     * frame (cli.adoc#suspect-change): the one addition the CLI makes to an answer, because
     * the repository is where the CLI runs and not necessarily where the server does.
     */
    private static int findingsWithSuspects(Options options, String defaultUrl, PrintStream out,
            PrintStream err, Function<String, String> annotateFindings) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int code;
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            code = answer(options, defaultUrl, capture, err);
        }
        String text = captured.toString(StandardCharsets.UTF_8);
        out.print(code == OK ? annotateFindings.apply(text) : text);
        out.flush();
        return code;
    }

    /**
     * The findings text with the suspect change under each code frame, from the repository the
     * working directory is in, as of now; the text as it is when there is no repository or no
     * {@code git} to ask. When not one frame resolved, the hint naming
     * {@code spidersense.source.dirs} goes to {@code err} (cli.adoc#suspect-change).
     */
    static String withSuspectChanges(String findings, PrintStream err) {
        SuspectChange suspects = SuspectChange.forWorkingDirectory(Path.of(""), SourceRoots.fromSystemProperties(),
                System.currentTimeMillis());
        if (suspects == null) {
            return findings;
        }
        String annotated = suspects.annotate(findings);
        String hint = suspects.hint();
        if (hint != null) {
            err.println(hint);
        }
        return annotated;
    }

    /** HTTP first, the file second, as {@link #dispatch} describes. */
    private static int answer(Options options, String defaultUrl, PrintStream out, PrintStream err) {
        if (Options.TRACE.equals(options.command()) && !TRACE_ID.matcher(options.requiredArgument()).matches()) {
            // A trace id travels as a path segment, which the server checks before any handler runs:
            // a word no trace id can be, such as "..", would be refused there in other words and with
            // exit 2. It names no trace, so it is told so before either mode is asked, in the words and
            // the exit code both give a trace that is not there (cli.adoc#exit-codes).
            throw new Reports.NoSuchTrace(options.requiredArgument());
        }
        if (options.has("db")) {
            return Local.run(options, out, err);
        }
        String named = options.valueOrNull("url");
        String base = Remote.base(named == null ? defaultUrl : named);
        try {
            int code = Remote.run(options, base, out, err);
            unappliedSettings(options, base, err);
            return code;
        } catch (Remote.Busy e) {
            err.println("spider-sense: " + e.getMessage());
            return USAGE;
        } catch (Remote.Unreachable e) {
            if (named != null) {
                // Nothing there at all is most often an application that stopped, and its
                // database outlives it; a server that answered gets the question about its port.
                err.println("spider-sense: " + e.line(base) + (e.answered() ? "" : STOPPED));
                return USAGE;
            }
            Config config = Local.config(options);
            err.println(Local.fallbackNotice(base, config));
            return Local.run(options, config, out, err);
        }
    }

    /**
     * The line saying that the thresholds and packages the command line set changed nothing: they are
     * settings of the file path, where no server is there to ask, and the server that answered uses
     * its own (cli.adoc#options).
     */
    private static void unappliedSettings(Options options, String base, PrintStream err) {
        List<String> given = Options.SETTINGS.stream()
                .filter(key -> !"db".equals(key) && options.has(key))
                .map(key -> "--" + key)
                .toList();
        if (given.isEmpty()) {
            return;
        }
        String named = given.size() == 1 ? given.get(0)
                : String.join(", ", given.subList(0, given.size() - 1)) + " and " + given.get(given.size() - 1);
        err.println("(the Spider Sense at " + base + " answered with its own settings; " + named
                + (given.size() == 1 ? " applies" : " apply") + " only when the file is read)");
    }

    /**
     * The usage error and one line naming the help to read: the command's own block when the line
     * named a command, else the table (cli.adoc#help). The whole table after every typo buried the
     * one line that said what was wrong.
     */
    private static int usage(Options.Usage e, @Nullable String command, PrintStream err) {
        err.println("spider-sense: " + e.getMessage());
        err.println(pointer(command));
        return USAGE;
    }

    /** The second line of a usage error. */
    static String pointer(@Nullable String command) {
        return command == null || Command.named(command) == null
                ? "see: java -jar spider-sense.jar help"
                : "see: java -jar spider-sense.jar " + command + " --help";
    }

    /** @param warn where a malformed setting is reported, which is stderr */
    static String defaultUrl(Consumer<String> warn) {
        String named = System.getenv("SPIDERSENSE_URL");
        if (named != null && !named.isBlank()) {
            return named.trim();
        }
        java.util.Properties settings = new java.util.Properties();
        for (String key : List.of("spidersense.collector", "spidersense.host", "spidersense.port")) {
            String value = Config.propertyOrEnv(key);
            if (value != null) {
                settings.setProperty(key, value);
            }
        }
        return configuredUrl(settings, warn);
    }

    /**
     * The Spider Sense the {@code spidersense.*} properties imply: the collector when one is named,
     * else the host and port, with the defaults filled in. The launcher fills these properties in
     * from the properties file before it runs a command, so a command run where the application
     * runs asks the Spider Sense the application sends to.
     *
     * <p>A port that is no port is read as the application reads it: a warning, and the default
     * (configuration.adoc), rather than a URL that no command could ask.
     *
     * @param warn where a malformed port is reported
     */
    static String configuredUrl(java.util.Properties properties, Consumer<String> warn) {
        String collector = nonBlank(properties, "spidersense.collector");
        if (collector != null) {
            return Remote.trimSlash(collector);
        }
        String host = nonBlank(properties, "spidersense.host");
        String port = nonBlank(properties, "spidersense.port");
        if (host == null && port == null) {
            return DEFAULT_URL;
        }
        return "http://" + Config.callableHost(host == null ? "" : host) + ":" + port(port, warn);
    }

    /** A port a URL can carry, 0 to 65535, or the default with a warning. */
    private static int port(@Nullable String value, Consumer<String> warn) {
        if (value == null) {
            return Config.DEFAULT_PORT;
        }
        String why;
        try {
            long port = Long.parseLong(value);
            if (port >= 0 && port <= 65_535) {
                return (int) port;
            }
            why = "is not a port from 0 to 65535";
        } catch (NumberFormatException e) {
            why = "is not a number";
        }
        warn.accept("[spider-sense] spidersense.port=" + value + " " + why + "; using " + Config.DEFAULT_PORT);
        return Config.DEFAULT_PORT;
    }

    private static @Nullable String nonBlank(java.util.Properties properties, String key) {
        String v = properties.getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * Stdout is the report and nothing else.
     *
     * <p>slf4j-simple talks at info level by default and H2 and the pool would fill
     * the terminal with it, so the CLI is quiet, unless its user said otherwise.
     * The CLI's JVM is its own, so a system property is fine here; the server keeps
     * its level in its jar's {@code simplelogger.properties} instead, since in agent
     * mode the JVM is the monitored application's.
     */
    private static void quietLoggingUnlessTold() {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }
}
