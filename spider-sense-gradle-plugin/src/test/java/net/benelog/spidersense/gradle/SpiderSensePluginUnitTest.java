package net.benelog.spidersense.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.testfixtures.ProjectBuilder;
import org.jspecify.annotations.Nullable;
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
    void theTestServiceIsTheServiceWithTestAfterItUntilTheBlockSetsIt() {
        assertThat(block.getTestService().get()).isEqualTo("orders-test");

        block.getService().set("shop");
        assertThat(block.getTestService().get()).isEqualTo("shop-test");

        block.getTestService().set("shop-it");
        assertThat(block.getTestService().get()).isEqualTo("shop-it");
        assertThat(block.getCheck().getService().get()).as("the check judges the application").isEqualTo("shop");
    }

    /** The agent goes onto a task by the Java it runs on, which its launcher says (gradle-plugin.adoc#java-versions). */
    @Test
    void aTaskOnAJavaTheAgentDoesNotRunOnGetsNothing() throws IOException {
        File jar = Files.createTempFile("spider-sense", ".jar").toFile();
        jar.deleteOnExit();
        block.getJar().set(jar);
        JavaExec onJava11 = project.getTasks().register("bootRun", JavaExec.class).get();
        JavaExec onJava21 = project.getTasks().register("run", JavaExec.class).get();
        JavaExec onJava25 = project.getTasks().register("bootTestRun", JavaExec.class).get();
        onJava11.getJavaLauncher().set(launcherOf(11));
        onJava21.getJavaLauncher().set(launcherOf(21));
        onJava25.getJavaLauncher().set(launcherOf(25));

        assertThat(agentArguments(onJava11)).as("Java 11").isEmpty();
        assertThat(agentArguments(onJava21)).as("Java 21 forwards")
                .containsExactly("-javaagent:" + jar.getAbsolutePath(), "-Dspidersense.service=orders");
        assertThat(agentArguments(onJava25)).as("Java 25")
                .containsExactly("-javaagent:" + jar.getAbsolutePath(), "-Dspidersense.service=orders");
    }

    @Test
    void aTaskOnAnOlderJavaIsWarnedAboutUnlessItForwards() {
        assertThat(SpiderSensePlugin.launcherWarning("bootRun", 11, false))
                .isEqualTo("Spider Sense is not attached to bootRun: it runs on Java 11, and Spider Sense needs Java 17"
                        + " or later.");
        assertThat(SpiderSensePlugin.launcherWarning("bootRun", 11, true)).as("forwarding needs 17 too").isNotNull();
        assertThat(SpiderSensePlugin.launcherWarning("run", 21, false))
                .isEqualTo("Spider Sense starts no UI in run: it runs on Java 21, and the embedded UI needs Java 25 or"
                        + " later. Start one on Java 25 with ./gradlew spiderSense, and set collector in the spiderSense"
                        + " block to forward to it.");
        assertThat(SpiderSensePlugin.launcherWarning("run", 21, true)).as("it forwards").isNull();
        assertThat(SpiderSensePlugin.launcherWarning("run", 25, false)).isNull();
        assertThat(SpiderSensePlugin.launcherWarning("run", SpiderSensePlugin.UNKNOWN_JAVA, false))
                .as("a launcher that does not say").isNull();
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
    void anEmptySourceDirsPassesAnEmptyValueAndAnUnsetOneNothing() {
        assertThat(systemProperties()).noneMatch(option -> option.startsWith("-Dspidersense.source.dirs"));

        block.getSourceDirs().set(List.of());

        assertThat(systemProperties()).containsExactly(
                "-Dspidersense.service=orders",
                "-Dspidersense.source.dirs=");
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
    void theStandaloneListensOnTheCollectorsPortWhenTheCollectorIsHereAndTheBlockNamesNoPort() {
        assertThat(SpiderSensePlugin.standalonePort(block).isPresent()).isFalse();

        block.getCollector().set("http://127.0.0.1:4100");
        assertThat(SpiderSensePlugin.standalonePort(block).get()).isEqualTo(4100);

        block.getCollector().set("http://localhost:4101/");
        assertThat(SpiderSensePlugin.standalonePort(block).get()).isEqualTo(4101);

        block.getCollector().set("http://[::1]:4102");
        assertThat(SpiderSensePlugin.standalonePort(block).get()).isEqualTo(4102);

        block.getCollector().set("http://box:4100");
        assertThat(SpiderSensePlugin.standalonePort(block).isPresent()).as("another machine").isFalse();

        block.getCollector().set("localhost:4103");
        assertThat(SpiderSensePlugin.standalonePort(block).get()).as("no scheme is http://").isEqualTo(4103);

        block.getCollector().set("http://127.0.0.1");
        assertThat(SpiderSensePlugin.standalonePort(block).isPresent()).as("no port written").isFalse();

        block.getCollector().set("http://127.0.0.1:4100");
        block.getPort().set(4200);
        assertThat(SpiderSensePlugin.standalonePort(block).get()).as("the block's port wins").isEqualTo(4200);
    }

    @Test
    void theExitCodeDecidesTheBuild() {
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(0, false, true)).isNull();
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(1, true, false)).isEqualTo("Spider Sense check failed");
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(3, false, true))
                .isEqualTo("Spider Sense check had no request to judge");
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(3, false, false)).as("the block said so").isNull();
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(2, false, false))
                .isEqualTo("Spider Sense check could not run (exit 2)");
    }

    /** Exit code 1 is also a JVM that could not load the CLI, or an error nothing caught. */
    @Test
    void anExitCodeOneWithoutAFailingVerdictIsACheckThatCouldNotRun() {
        assertThat(SpiderSensePlugin.FailOnVerdict.failure(1, false, true))
                .isEqualTo("Spider Sense check could not run (exit 1)");
    }

    @Test
    void theVerdictIsTheHeadingOfALineAndTheOutputPassesThroughUnchanged() throws IOException {
        String failing = """
                # check  fail  2026-09-17T08:19:28.122+09:00 → 08:19:45  (17s, all services, 33 requests)

                | rule | limit | actual | verdict | detail |
                """;
        assertThat(watched(failing, 7).sawFailingVerdict()).isTrue();
        assertThat(watched(failing, 1).sawFailingVerdict()).as("one byte at a time").isTrue();
        assertThat(watched("# check  pass  …\n", 64).sawFailingVerdict()).isFalse();
        assertThat(watched("Error: LinkageError occurred while loading main class\n", 64).sawFailingVerdict())
                .isFalse();
        assertThat(watched("note: # check  fail\n", 64).sawFailingVerdict()).as("not at the start of a line")
                .isFalse();
        assertThat(watched("first line\n# check  fail  …\n", 5).sawFailingVerdict()).as("on a later line").isTrue();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpiderSensePlugin.VerdictWatch watch = new SpiderSensePlugin.VerdictWatch(out)) {
            watch.write(failing.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(failing);
    }

    /** The output written through a watch in chunks of {@code chunk} bytes. */
    private static SpiderSensePlugin.VerdictWatch watched(String output, int chunk) throws IOException {
        SpiderSensePlugin.VerdictWatch watch = new SpiderSensePlugin.VerdictWatch(OutputStream.nullOutputStream());
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        for (int at = 0; at < bytes.length; at += chunk) {
            int length = Math.min(chunk, bytes.length - at);
            if (length == 1) {
                watch.write(bytes[at]);
            } else {
                watch.write(bytes, at, length);
            }
        }
        return watch;
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
        return SpiderSensePlugin.systemProperties(project.getObjects(), block, block.getService(), block.getPort())
                .get();
    }

    /** What the plugin adds to a task's JVM arguments, its own provider's share only. */
    private static List<String> agentArguments(JavaExec task) {
        List<String> arguments = new ArrayList<>();
        task.getJvmArgumentProviders().forEach(provider -> provider.asArguments().forEach(arguments::add));
        return arguments;
    }

    /** A launcher that says it is Java {@code version} and nothing else, which is all the plugin reads of it. */
    private static JavaLauncher launcherOf(int version) {
        JavaInstallationMetadata metadata = proxy(JavaInstallationMetadata.class,
                method -> method.equals("getLanguageVersion") ? JavaLanguageVersion.of(version) : null);
        return proxy(JavaLauncher.class, method -> method.equals("getMetadata") ? metadata : null);
    }

    private static <T> T proxy(Class<T> type, Function<String, @Nullable Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, arguments) -> answer.apply(method.getName())));
    }
}
