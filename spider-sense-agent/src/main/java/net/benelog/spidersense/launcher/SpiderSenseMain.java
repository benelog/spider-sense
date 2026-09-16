package net.benelog.spidersense.launcher;

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

    public static void main(String[] args) throws Exception {
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
        Config config = Config.fromArgs(args).withMode(Config.STANDALONE);
        printBanner(config);
        // Blocks until the server is stopped.
        EmbeddedServer.start(config);
    }

    static void printBanner(Config config) {
        String url = config.baseUrl();
        StringBuilder b = new StringBuilder();
        b.append("Spider Sense ").append(NestedJar.version())
                .append(" — a local-development APM, standalone.\n")
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
        System.out.println(b);
    }

    static void printHelp() {
        System.out.println("""
                Spider Sense — a local-development APM: one jar, OpenTelemetry-native.

                  java -javaagent:spider-sense.jar -jar app.jar          instrument an app, UI inside it
                  java -javaagent:spider-sense.jar -Dspidersense.collector=http://127.0.0.1:4000 -jar app.jar
                                                                        instrument an app, UI elsewhere
                  java -jar spider-sense.jar                             the collector and UI alone

                Options (as --key=value here, as -Dspidersense.key=value under -javaagent):

                  --port=4000                     UI and OTLP/HTTP port
                  --host=127.0.0.1                bind address; 0.0.0.0 to reach it from elsewhere
                  --collector=<url>               agent mode: forward instead of starting the UI
                  --service=<name>                agent mode: sets otel.service.name
                  --db=~/db/spider-sense/sense    H2 database path or jdbc:h2: URL
                  --retention.hours=24            rows older than this are swept
                  --slow.request.ms=500           a server span slower than this is a tingle
                  --slow.query.ms=100             a DB span slower than this is a tingle
                  --open=false                    agent mode: open the browser at startup
                  --help, --version

                Every otel.* property still works as the OpenTelemetry agent documents it;
                Spider Sense only fills in defaults.""");
    }
}
