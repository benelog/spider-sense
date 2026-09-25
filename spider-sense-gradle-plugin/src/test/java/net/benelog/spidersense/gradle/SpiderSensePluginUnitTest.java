package net.benelog.spidersense.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plugin's decisions as functions of the block, on a project built in
 * memory: argument order, number formatting, the URL, and the verdict. TestKit
 * in {@link SpiderSensePluginTest} is for what needs a real build: the task
 * wiring and the configuration cache.
 */
class SpiderSensePluginUnitTest {

    /** The rule table the check block transcribes, from this included build's directory. */
    private static final Path CHECK_PAGE = Path.of("..", "manual", "modules", "ROOT", "pages", "check.adoc");

    private Project project;
    private SpiderSenseExtension block;

    @BeforeEach
    void applyThePlugin() {
        project = ProjectBuilder.builder().withName("orders").build();
        project.getPluginManager().apply(SpiderSensePlugin.class);
        block = project.getExtensions().getByType(SpiderSenseExtension.class);
    }

    @Test
    void anEmptyBlockPassesOnlyTheService() {
        assertThat(systemProperties()).containsExactly("-Dspidersense.service=orders");
    }

    @Test
    void theOptionsFollowTheOrderOfTheTable() {
        block.getSlowQueryMs().set(50L);
        block.getPort().set(4001);
        block.getAppPackages().set(List.of("com.acme", "org.acme"));
        block.getIgnoreEndpoints().set(List.of());

        assertThat(systemProperties()).containsExactly(
                "-Dspidersense.service=orders",
                "-Dspidersense.port=4001",
                "-Dspidersense.slow.query.ms=50",
                "-Dspidersense.app.packages=com.acme,org.acme",
                "-Dspidersense.ignore.endpoints=");
    }

    @Test
    void theCheckLineIsTheWindowThenTheRulesSetWrittenPlainly() {
        SpiderSenseCheckExtension check = block.getCheck();
        check.getMaxErrors().set(0L);
        check.setMaxErrorRate(0.0001);
        check.setMinApdex(0.9);

        assertThat(SpiderSensePlugin.checkArguments(project.getObjects(), project.getProviders(), check).get())
                .containsExactly("check", "--since=start", "--service=orders", "--max-errors=0",
                        "--max-error-rate=0.0001", "--min-apdex=0.9");
    }

    @Test
    void aNumberIsWrittenAsTheCliReadsIt() {
        assertThat(SpiderSensePlugin.plainly(0.0001)).isEqualTo("0.0001");
        assertThat(SpiderSensePlugin.plainly(1.0)).isEqualTo("1");
        assertThat(SpiderSensePlugin.plainly(300L)).isEqualTo("300");
    }

    @Test
    void theUrlIsAbsentUntilTheBlockNamesWhereSpiderSenseIs() {
        assertThat(SpiderSensePlugin.baseUrl(block).isPresent()).isFalse();

        block.getHost().set("0.0.0.0");
        assertThat(SpiderSensePlugin.baseUrl(block).get()).isEqualTo("http://127.0.0.1:4000");

        block.getPort().set(4001);
        assertThat(SpiderSensePlugin.baseUrl(block).get()).isEqualTo("http://127.0.0.1:4001");

        block.getCollector().set("http://box:4000");
        assertThat(SpiderSensePlugin.baseUrl(block).get()).as("the collector wins").isEqualTo("http://box:4000");
    }

    @Test
    void theExitCodeDecidesTheBuild() {
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(0, true)).isNull();
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(1, false)).isEqualTo("Spider Sense check failed");
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(3, true))
                .isEqualTo("Spider Sense check had no request to judge");
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(3, false)).as("the block said so").isNull();
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(2, false))
                .isEqualTo("Spider Sense check could not run (exit 2)");
    }

    /** A rule added to the CLI and its manual page and not to the block fails here. */
    @Test
    void theCheckBlockHasEveryRuleOfTheManualInItsOrder() throws IOException {
        String page = Files.readString(CHECK_PAGE, StandardCharsets.UTF_8);
        int start = page.indexOf("| Rule\n");
        assertThat(start).as("the rule table in " + CHECK_PAGE).isNotNegative();
        String table = page.substring(start, page.indexOf("|===", start));
        Matcher flags = Pattern.compile("(?m)^\\| `--([a-z0-9-]+)`$").matcher(table);
        List<String> documented = flags.results().map(match -> match.group(1)).toList();

        assertThat(documented).isNotEmpty();
        assertThat(List.copyOf(block.getCheck().rules().keySet())).isEqualTo(documented);
    }

    private List<String> systemProperties() {
        return SpiderSensePlugin.systemProperties(project.getObjects(), block).get();
    }
}
