package net.benelog.spidersense.launcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

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
        printBanner(config);
        // Blocks until the server is stopped.
        EmbeddedServer.start(config);
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
            Thread current = Thread.currentThread();
            ClassLoader previous = current.getContextClassLoader();
            try {
                current.setContextClassLoader(loader);
                Class<?> cli = Class.forName(CLI_CLASS, true, loader);
                Method run = cli.getMethod("run", String[].class);
                return (Integer) run.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                System.err.println("spider-sense: " + cause);
                return 2;
            } finally {
                current.setContextClassLoader(previous);
            }
        } catch (Exception | LinkageError e) {
            System.err.println("spider-sense: could not start the command line: " + e);
            return 2;
        }
    }

    static void printBanner(Config config) {
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
                .append("  java -javaagent:spider-sense.jar -Dspidersense.collector=")
                .append(url).append(" -jar app.jar\n");
        System.out.println(banner);
    }

    static void printHelp() {
        System.out.println(HELP_HEAD + Key.helpLines() + HELP_TAIL);
    }

    private static final String HELP_HEAD = """
                Spider Sense — a local-development observability tool: one jar, OpenTelemetry-native.

                  java -javaagent:spider-sense.jar -jar app.jar          instrument an app, UI inside it
                  java -javaagent:spider-sense.jar -Dspidersense.collector=http://127.0.0.1:4000 -jar app.jar
                                                                        instrument an app, UI elsewhere
                  java -jar spider-sense.jar                             the collector and UI alone
                  java -jar spider-sense.jar <command> [options]         ask a running Spider Sense, or the
                                                                        database file, from the terminal;
                                                                        java -jar spider-sense.jar help
                                                                        lists every command

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
