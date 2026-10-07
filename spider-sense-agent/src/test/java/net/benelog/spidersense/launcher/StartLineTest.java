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

        String help = SpiderSenseMain.help(JAR);
        assertThat(help)
                .contains("java -javaagent:" + JAR + " -jar app.jar")
                .contains("java -jar " + JAR + " help")
                .doesNotContain(" spider-sense.jar");
    }
}
