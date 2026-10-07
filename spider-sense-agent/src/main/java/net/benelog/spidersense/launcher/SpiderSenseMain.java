package net.benelog.spidersense.launcher;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * The {@code Main-Class} of the distributable jar: {@code java -jar spider-sense.jar}.
 *
 * <p>Standalone mode is the collector + UI on its own, with nothing instrumented in this JVM.
 * Anything that speaks OTLP/HTTP sends to it, including other JVMs running the same jar as an agent
 * with {@code -Dspidersense.collector}.
 */
public final class SpiderSenseMain {

    private SpiderSenseMain() {
    }

    /** The CLI entry point inside the nested server jar; see {@code cli.adoc}. */
    static final String CLI_CLASS = "net.benelog.spidersense.cli.Cli";

    /**
     * Where the CLI reads this jar's own path. It runs out of the nested server jar,
     * extracted to a temporary directory, so only the launcher can tell it where the
     * distributable it was started from actually is; {@code init} writes that path into
     * a project's {@code CLAUDE.md} ({@code agent-skill.adoc#init}).
     */
    static final String JAR_PROPERTY = "spidersense.jar";

    public static void main(String[] args) throws Exception {
        if (isCommand(args)) {
            System.exit(runCommand(args));
        }
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
            if ("--version".equals(arg) || "-V".equals(arg)) {
                System.out.println("Spider Sense " + NestedJar.version());
                return;
            }
        }
        Path file = ConfigFile.apply();
        Config.Parsed parsed;
        try {
            parsed = Config.fromArgs(args);
        } catch (IllegalArgumentException e) {
            // A usage error, as the CLI answers one: one line and exit code 2, no stack trace.
            System.err.println("spider-sense: " + e.getMessage());
            System.exit(2);
            return;
        }
        Config.printWarnings(parsed);
        // The server's own keys: it runs in this JVM and reads them as spidersense.* properties.
        parsed.serverProperties().forEach(System::setProperty);
        Config config = parsed.config().withMode(Config.STANDALONE);
        if (file != null) {
            System.out.println("Configuration: " + file.toAbsolutePath());
        }
        OptionalInt bound;
        try {
            bound = EmbeddedServer.start(config);
        } catch (Exception e) {
            // One line and exit 2, as for a bad argument, rather than a stack trace after a
            // banner that already announced a UI at that port.
            System.err.println("spider-sense: " + startFailure(config, e));
            System.exit(2);
            return;
        }
        // After the bind, so the banner names the port that is serving: --port=0 picks any free one.
        printBanner(config.withPort(bound.orElse(config.port())));
        // Returning is not stopping: Jetty's threads are not daemons in standalone mode, and they
        // keep this JVM serving until it is stopped.
    }

    /** What a server that did not start says: a held port names the way out, anything else its cause. */
    static String startFailure(Config config, Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException) {
                return "port " + config.port() + " is in use; --port= picks another (" + t + ")";
            }
        }
        Throwable cause = failure.getCause();
        return "the server did not start: " + failure + (cause != null ? " (" + cause + ")" : "");
    }

    /**
     * A first argument that does not start with {@code -} is a CLI command ({@code findings},
     * {@code trace}, {@code mark}, ...); flags alone mean the standalone server.
     */
    static boolean isCommand(String[] args) {
        return args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()
                && !args[0].startsWith("-");
    }

    /**
     * Runs {@code Cli.run(String[])} from the nested server jar in its own {@link SenseClassLoader}
     * and returns its exit code, so the launcher itself stays free of every dependency. Errors of
     * the command are the CLI's to print; only the failure to load it at all is reported here.
     *
     * <p>It also leaves this jar's own path in {@value #JAR_PROPERTY}, the one thing the CLI cannot
     * find out for itself, and applies the properties file, so that a command run from the
     * project's directory asks the Spider Sense that directory's file points at.
     */
    static int runCommand(String[] args) {
        try {
            ConfigFile.apply();
            Path own = NestedJar.ownJar();
            if (own != null) {
                System.setProperty(JAR_PROPERTY, own.toAbsolutePath().toString());
            }
            SenseClassLoader loader = new SenseClassLoader(NestedJar.serverJar());
            try {
                Object exit = loader.invokeStatic(CLI_CLASS, "run", args);
                return exit instanceof Integer code ? code : 2;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                System.err.println("spider-sense: " + cause);
                return 2;
            }
        } catch (Exception | LinkageError e) {
            System.err.println("spider-sense: could not start the command line: " + e);
            return 2;
        }
    }

    static void printBanner(Config config) {
        System.out.println(banner(config, NestedJar.commandPath()));
    }

    /** The standalone banner, every command in it naming {@code jar}, this jar's own path. */
    static String banner(Config config, String jar) {
        String url = config.baseUrl();
        StringBuilder banner = new StringBuilder();
        banner.append("Spider Sense ").append(NestedJar.version())
                .append(" — a local-development observability tool, standalone.\n")
                .append("The UI is at ").append(url)
                .append(" and it is also the OTLP/HTTP endpoint: ").append(url).append("/v1/traces")
                .append(", /v1/metrics and /v1/logs.\n")
                .append("It reads and writes ").append(config.databaseDescription())
                .append(", shared with every other Spider Sense on this machine.\n")
                .append("To send from another JVM or another language, set\n")
                .append("  OTEL_EXPORTER_OTLP_ENDPOINT=").append(url).append('\n')
                .append("  OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf\n")
                .append("or attach this very jar to it:\n")
                .append("  java -javaagent:").append(jar).append(" -Dspidersense.collector=")
                .append(url).append(" -jar app.jar\n")
                .append("Ask it from a terminal (help lists the commands):\n")
                .append("  ").append(SpiderSenseAgent.cliLine(jar, url)).append('\n');
        return banner.toString();
    }

    static void printHelp() {
        System.out.println(help(NestedJar.commandPath()));
    }

    /** {@code --help}, every command in it naming {@code jar}, this jar's own path. */
    static String help(String jar) {
        return HELP_HEAD.replace("spider-sense.jar", jar) + Key.helpLines() + HELP_TAIL;
    }

    private static final String HELP_HEAD = """
                Spider Sense — a local-development observability tool: one jar, OpenTelemetry-native.

                Instrument an app, with the UI inside it:
                  java -javaagent:spider-sense.jar -jar app.jar
                Instrument an app, with the UI elsewhere:
                  java -javaagent:spider-sense.jar -Dspidersense.collector=http://127.0.0.1:4000 -jar app.jar
                The collector and UI alone:
                  java -jar spider-sense.jar
                Ask a running Spider Sense, or the database file, from the terminal (help lists
                every command):
                  java -jar spider-sense.jar <command> [options]
                  java -jar spider-sense.jar help

                Options (as --key=value here, as -Dspidersense.key=value under -javaagent, or as
                spidersense.key=value lines in spider-sense.properties in the working directory,
                or in the file -Dspidersense.config names; the command line wins over the file):

                """;

    private static final String HELP_TAIL = """
                  --help, --version

                An option not in this list is a usage error.

                Every otel.* property still works as the OpenTelemetry agent documents it;
                Spider Sense only fills in defaults.""";
}
