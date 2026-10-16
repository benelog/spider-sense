package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What the launcher prints at start-up: one line under {@code -javaagent}, the banner standalone. */
class StartLineTest {

    private static final String JAR = "/opt/tools/spider-sense-0.1.0.jar";

    @Test
    void theAgentsLineNamesTheUiTheServiceAndTheCliLine() {
        assertThat(SpiderSenseAgent.startLine("http://127.0.0.1:4000", "orders", JAR))
                .isEqualTo("UI: http://127.0.0.1:4000  service: orders  CLI: java -jar " + JAR
                        + " findings --since=start");
    }

    @Test
    void aUiElsewhereThanTheDefaultIsTheCliLinesUrl() {
        assertThat(SpiderSenseAgent.startLine("http://127.0.0.1:4105", "orders", JAR))
                .endsWith("CLI: java -jar " + JAR + " findings --since=start --url=http://127.0.0.1:4105");
    }

    @Test
    void anUnknownServiceIsLeftOut() {
        assertThat(SpiderSenseAgent.startLine("http://127.0.0.1:4000", null, JAR))
                .isEqualTo("UI: http://127.0.0.1:4000  CLI: java -jar " + JAR + " findings --since=start");
    }

    @Test
    void aPortHeldByAnotherSpiderSenseSaysTheTelemetryGoesThere() {
        assertThat(SpiderSenseAgent.heldBy(4000, SpiderSenseAgent.startLine("http://127.0.0.1:4000", "orders", JAR)))
                .isEqualTo("port 4000 is held by another Spider Sense; the telemetry is exported to it."
                        + " UI: http://127.0.0.1:4000  service: orders  CLI: java -jar " + JAR + " findings --since=start");
    }

    /** Below the server's floor no UI starts, and a Spider Sense at the port is where the telemetry goes. */
    @Test
    void anOlderJavaExportsToTheSpiderSenseAtThePort() {
        assertThat(SpiderSenseAgent.exportedFromOlderJava("21.0.8", 4000,
                SpiderSenseAgent.startLine("http://127.0.0.1:4000", "orders", JAR)))
                .isEqualTo("the embedded UI needs Java 25 or later, and this JVM is Java 21.0.8; the telemetry is exported"
                        + " to the Spider Sense on port 4000. UI: http://127.0.0.1:4000  service: orders  CLI: java -jar "
                        + JAR + " findings --since=start");
    }

    /** With nothing at the port, nothing is exported, and the one line says how to forward. */
    @Test
    void anOlderJavaWithNothingAtThePortSaysHowToForward() {
        assertThat(SpiderSenseAgent.noEmbeddedUi("21.0.8", "http://127.0.0.1:4000", 4000, JAR))
                .isEqualTo("the embedded UI needs Java 25 or later, and this JVM is Java 21.0.8, so nothing is exported;"
                        + " forward to a Spider Sense on Java 25 with -Dspidersense.collector=http://127.0.0.1:4000,"
                        + " started by: java -jar " + JAR);
        assertThat(SpiderSenseAgent.noEmbeddedUi("17.0.16", "http://127.0.0.1:4105", 4105, JAR))
                .endsWith("-Dspidersense.collector=http://127.0.0.1:4105, started by: java -jar " + JAR + " --port=4105");
    }

    /** {@code spidersense.open} opens the UI the telemetry goes to, the collector's when forwarding. */
    @Test
    void theBrowserOpensTheUiTheTelemetryGoesTo() {
        Config open = Config.parse(new String[] {"--port=4105", "--open=true"}, key -> null, key -> null).config();
        Config forwarding = Config.parse(new String[] {"--collector=http://127.0.0.1:4000/", "--open=true"},
                key -> null, key -> null).config();

        assertThat(SpiderSenseAgent.browserUrl(open, true)).isEqualTo("http://127.0.0.1:4105");
        assertThat(SpiderSenseAgent.browserUrl(open, false)).as("nothing serves at the port").isNull();
        assertThat(SpiderSenseAgent.browserUrl(forwarding, false)).isEqualTo("http://127.0.0.1:4000");
        assertThat(SpiderSenseAgent.browserUrl(Config.defaults(), true)).as("not asked to").isNull();
    }

    @Test
    void aCollectorNothingAnswersAtSaysHowToStartIt() throws Exception {
        assertThat(SpiderSenseAgent.nothingAnswers("http://127.0.0.1:4199", JAR))
                .isEqualTo("nothing answers at http://127.0.0.1:4199 yet; start it with: java -jar " + JAR
                        + " --port=4199 (exports are retried)");
        assertThat(SpiderSenseAgent.nothingAnswers("http://localhost:4000", JAR))
                .isEqualTo("nothing answers at http://localhost:4000 yet; start it with: java -jar " + JAR
                        + " (exports are retried)");
        assertThat(SpiderSenseAgent.nothingAnswers("http://collector.internal:4318", JAR))
                .as("a collector elsewhere is not this jar's to start")
                .isEqualTo("nothing answers at http://collector.internal:4318 yet; exports are retried");

        try (java.net.ServerSocket listening = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            int port = listening.getLocalPort();
            assertThat(SpiderSenseAgent.answers("http://127.0.0.1:" + port)).isTrue();
            listening.close();
            assertThat(SpiderSenseAgent.answers("http://127.0.0.1:" + port)).isFalse();
        }
    }

    @Test
    void theJarIsItsAbsolutePathQuotedWhenItHoldsASpace() {
        assertThat(NestedJar.commandPath(Path.of("/opt/tools/spider-sense-0.1.0.jar")))
                .isEqualTo("/opt/tools/spider-sense-0.1.0.jar");
        assertThat(NestedJar.commandPath(Path.of("/opt/my tools/spider-sense-0.1.0.jar")))
                .isEqualTo("\"/opt/my tools/spider-sense-0.1.0.jar\"");
        assertThat(NestedJar.commandPath(null)).as("exploded classes have no jar").isEqualTo("spider-sense.jar");
    }

    @Test
    void theBannerAndTheHelpNameTheJarThatRuns() {
        String banner = SpiderSenseMain.banner(Config.defaults().withPort(4105), JAR);
        assertThat(banner)
                .contains("java -javaagent:" + JAR + " -Dspidersense.collector=http://127.0.0.1:4105 -jar app.jar")
                .contains("java -jar " + JAR + " findings --since=start --url=http://127.0.0.1:4105")
                .doesNotContain("spider-sense.jar ");

        String ipv6 = SpiderSenseMain.banner(
                Config.parse(new String[] {"--host=::1", "--port=4105"}, key -> null, key -> null).config(), JAR);
        assertThat(ipv6).as("zsh reads an unquoted IPv6 URL as a pattern")
                .contains(" -Dspidersense.collector='http://[::1]:4105' -jar app.jar")
                .contains(" findings --since=start --url='http://[::1]:4105'")
                .as("an assignment is no pattern").contains("  OTEL_EXPORTER_OTLP_ENDPOINT=http://[::1]:4105\n");

        String help = SpiderSenseMain.help(JAR);
        assertThat(help)
                .contains("java -javaagent:" + JAR + " -jar app.jar")
                .contains("java -jar " + JAR + " help")
                .doesNotContain(" spider-sense.jar");
    }
}
