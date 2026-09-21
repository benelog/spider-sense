package net.benelog.spidersense.cli;

import java.io.InputStream;
import java.io.PrintStream;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.server.Config;
import org.jspecify.annotations.Nullable;

/**
 * {@code java -jar spider-sense.jar findings --since=start}: the agent interface
 * for everything with a shell.
 *
 * <p>The launcher finds this class in the nested server jar and calls
 * {@link #run(String[])} reflectively, exiting with what it returns, so the whole
 * command line lives on this side of the class loader and the launcher keeps its
 * one rule of having no dependencies (design.md).
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
    static final String DEFAULT_URL = "http://127.0.0.1:4000";

    private Cli() {
    }

    /** The entry point the launcher invokes. */
    public static int run(String[] args) {
        return run(args, defaultUrl(), System.in, System.out, System.err);
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
        quietLoggingUnlessTold();
        Options options;
        try {
            options = Options.parse(args);
        } catch (Options.Usage e) {
            return usage(e, err);
        }
        if (Options.HELP.equals(options.command())) {
            out.println(Help.TEXT);
            return OK;
        }
        try {
            return dispatch(options, defaultUrl, in, out, err);
        } catch (Options.Usage e) {
            return usage(e, err);
        } catch (Reports.NoSuchTrace | Selectors.UnknownMark e) {
            err.println("spider-sense: " + e.getMessage());
            return NOT_FOUND;
        } catch (Selectors.BadSelector | IllegalArgumentException | IllegalStateException e) {
            // A bad selector, a bad mark name, a missing or foreign database file: the
            // message says what to do, the class name would not.
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
     * writes (agent.md). {@code mcp} comes before it too, because it is a session
     * of messages rather than one question, and it makes the same choice again for
     * each tool call it is asked to answer.
     */
    private static int dispatch(Options options, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err) {
        if (Options.INIT.equals(options.command())) {
            return Init.run(options, out, err);
        }
        if (Options.MCP.equals(options.command())) {
            return Mcp.run(options, defaultUrl, in, out, err);
        }
        if (Options.TAIL.equals(options.command())) {
            return Tail.run(options, defaultUrl, out, err);
        }
        if (options.has("db")) {
            return Local.run(options, out, err);
        }
        String named = options.valueOrNull("url");
        String base = named == null ? defaultUrl : named;
        try {
            return Remote.run(options, base, out, err);
        } catch (Remote.Unreachable e) {
            if (named != null) {
                err.println("spider-sense: no Spider Sense at " + base + " (" + e.getMessage() + ")");
                return USAGE;
            }
            Config config = Local.config(options);
            err.println("(no Spider Sense at " + base + "; reading " + Local.describe(config)
                    + " directly)");
            return Local.run(options, config, out, err);
        }
    }

    private static int usage(Options.Usage e, PrintStream err) {
        err.println("spider-sense: " + e.getMessage());
        err.println(Help.TEXT);
        return USAGE;
    }

    static String defaultUrl() {
        String named = System.getenv("SPIDERSENSE_URL");
        if (named != null && !named.isBlank()) {
            return named.trim();
        }
        return configuredUrl(System.getProperties());
    }

    /**
     * The Spider Sense the {@code spidersense.*} properties imply: the collector when one is named,
     * else the host and port, with the defaults filled in. The launcher fills these properties in
     * from the properties file before it runs a command, so a command run where the application
     * runs asks the Spider Sense the application sends to.
     */
    static String configuredUrl(java.util.Properties properties) {
        String collector = value(properties, "spidersense.collector");
        if (collector != null) {
            while (collector.endsWith("/")) {
                collector = collector.substring(0, collector.length() - 1);
            }
            return collector;
        }
        String host = value(properties, "spidersense.host");
        String port = value(properties, "spidersense.port");
        if (host == null && port == null) {
            return DEFAULT_URL;
        }
        if (host == null || host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + (port == null ? "4000" : port);
    }

    private static @Nullable String value(java.util.Properties properties, String key) {
        String v = properties.getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * Stdout is the report and nothing else.
     *
     * <p>slf4j-simple talks at info level by default and H2 and the pool would fill
     * the terminal with it, so the CLI is quiet for the same reason the embedded
     * server is (SpiderSenseServer.quietLoggingUnlessTold), unless its user said
     * otherwise.
     */
    private static void quietLoggingUnlessTold() {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }
}
