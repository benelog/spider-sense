package net.benelog.spidersense.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the plugin against a scratch project with TestKit.
 *
 * <p>The scratch project registers a {@code bootRun} of its own and takes
 * {@code run} from the {@code application} plugin, so nothing here downloads
 * Spring Boot to find out what the plugin does to a {@code JavaExec} task.
 *
 * <p>Two kinds of assertion: a {@code probe} task prints the arguments the
 * plugin contributed, which is the fastest way to see what a forked JVM would
 * get; and the two Spider Sense tasks are really run, against a stub jar that
 * carries a {@code SpiderSenseMain} printing its environment, arguments and JVM
 * options, because {@code SPIDERSENSE_URL} is set on the forked process and a
 * probe would not see it.
 */
class SpiderSensePluginTest {

    @TempDir
    static Path stubDir;

    static Path stubJar;

    @TempDir
    Path projectDir;

    @BeforeAll
    static void stubJar() throws IOException {
        Path source = stubDir.resolve("SpiderSenseMain.java");
        Files.writeString(source, """
                package net.benelog.spidersense.launcher;

                public class SpiderSenseMain {
                    public static void main(String[] args) {
                        System.out.println("SPIDERSENSE_URL=" + System.getenv("SPIDERSENSE_URL"));
                        System.out.println("args=" + java.util.Arrays.toString(args));
                        System.out.println("jvmArgs="
                                + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                        // The exit code the real CLI would give a verdict, so the check
                        // task's four outcomes can be run without a Spider Sense.
                        int exit = Integer.getInteger("stub.exit", 0);
                        if (exit != 0) {
                            System.exit(exit);
                        }
                    }
                }
                """);
        Path classes = Files.createDirectories(stubDir.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests run on a JDK").isNotNull();
        assertThat(compiler.run(null, null, null, "-d", classes.toString(), source.toString())).isZero();

        String entry = "net/benelog/spidersense/launcher/SpiderSenseMain.class";
        stubJar = stubDir.resolve("spider-sense-stub.jar");
        try (OutputStream file = Files.newOutputStream(stubJar); JarOutputStream jar = new JarOutputStream(file)) {
            jar.putNextEntry(new JarEntry(entry));
            Files.copy(classes.resolve(entry), jar);
            jar.closeEntry();
        }
    }

    @BeforeEach
    void scratchProject() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'scratch'\n");
        Files.writeString(projectDir.resolve("other.jar"), "not really a jar\n");
        buildFile("jar = file('" + stubJar.toAbsolutePath() + "')");
    }

    /** The scratch build, with the body of the {@code spiderSense} block filled in. */
    private void buildFile(String block) throws IOException {
        buildFile(block, "");
    }

    /** The same, with something more after the block: a task of the build's own. */
    private void buildFile(String block, String extra) throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'net.benelog.spidersense'
                    id 'application'
                }
                repositories {
                    mavenCentral()
                }
                application {
                    mainClass = 'scratch.Main'
                }
                tasks.register('bootRun', JavaExec)
                tasks.register('other', JavaExec)

                spiderSense {
                %BLOCK%
                }

                def contributed = { String name ->
                    tasks.named(name, JavaExec).get().jvmArgumentProviders.collectMany { it.asArguments() }
                }
                tasks.register('probe') {
                    def lines = [
                            "bootRun=" + contributed('bootRun'),
                            "run=" + contributed('run'),
                            "other=" + contributed('other'),
                            "check=" + tasks.named('spiderSenseCheck', JavaExec).get()
                                    .argumentProviders.collectMany { it.asArguments() },
                            "checkGroup=" + tasks.named('spiderSenseCheck').get().group,
                            "dependencies=" + configurations.spiderSense.allDependencies
                                    .collect { "${it.group}:${it.name}:${it.version}" },
                    ]
                    doLast {
                        lines.each { println it }
                    }
                }
                %EXTRA%
                """.replace("%BLOCK%", block.indent(4).stripTrailing()).replace("%EXTRA%", extra));
    }

    /** The project directory as Gradle reports it: a temporary directory may be reached through a symlink. */
    private String realProjectDir() {
        try {
            return projectDir.toRealPath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GradleRunner gradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private String probe(String... extra) {
        String[] arguments = new String[extra.length + 2];
        arguments[0] = "probe";
        arguments[1] = "-q";
        System.arraycopy(extra, 0, arguments, 2, extra.length);
        return gradle(arguments).build().getOutput();
    }

    @Test
    void attachesTheAgentAndNamesTheServiceAfterTheProject() {
        String output = probe();

        assertThat(output).contains("bootRun=[-javaagent:" + stubJar.toAbsolutePath()
                + ", -Dspidersense.service=scratch]");
        assertThat(output).contains("run=[-javaagent:" + stubJar.toAbsolutePath()
                + ", -Dspidersense.service=scratch]");
    }

    @Test
    void everyPropertyOfTheBlockBecomesASystemProperty() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                configFile = file('conf/sense.properties')
                service = 'orders'
                port = 4001
                host = '0.0.0.0'
                collector = 'http://127.0.0.1:4000'
                db = '/tmp/sense'
                retentionHours = 6
                slowRequestMs = 250
                slowQueryMs = 50
                open = true
                appPackages = ['com.acme.orders', 'com.acme.shared']
                ignoreEndpoints = ['/actuator/**', '/ping']
                retentionSpans = 500000
                maxSpansPerSecond = 2000
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        String output = probe();

        assertThat(output).contains("-Dspidersense.config="
                + projectDir.resolve("conf/sense.properties").toAbsolutePath());
        assertThat(output).contains("-Dspidersense.service=orders");
        assertThat(output).contains("-Dspidersense.port=4001");
        assertThat(output).contains("-Dspidersense.host=0.0.0.0");
        assertThat(output).contains("-Dspidersense.collector=http://127.0.0.1:4000");
        assertThat(output).contains("-Dspidersense.db=/tmp/sense");
        assertThat(output).contains("-Dspidersense.retention.hours=6");
        assertThat(output).contains("-Dspidersense.slow.request.ms=250");
        assertThat(output).contains("-Dspidersense.slow.query.ms=50");
        assertThat(output).contains("-Dspidersense.open=true");
        assertThat(output).contains("-Dspidersense.app.packages=com.acme.orders,com.acme.shared");
        assertThat(output).contains("-Dspidersense.ignore.endpoints=/actuator/**,/ping");
        assertThat(output).contains("-Dspidersense.retention.spans=500000");
        assertThat(output).contains("-Dspidersense.ingest.max-spans-per-second=2000");
    }

    @Test
    void theCapsAreUnsetUntilTheBlockSetsThem() {
        String output = probe();

        assertThat(output).doesNotContain("-Dspidersense.retention.spans");
        assertThat(output).doesNotContain("-Dspidersense.ingest.max-spans-per-second");
    }

    @Test
    void anIgnoreListLeftAloneSaysNothingAndAnEmptyOnePassesAnEmptyValue() throws IOException {
        assertThat(probe()).doesNotContain("-Dspidersense.ignore.endpoints");

        buildFile("""
                jar = file('%JAR%')
                ignoreEndpoints = []
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("-Dspidersense.ignore.endpoints=]");
    }

    @Test
    void aTaskThatIsNotInAttachToGetsNothing() {
        assertThat(probe()).contains("other=[]");
    }

    @Test
    void attachToAddsATask() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                attachTo.add('other')
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("other=[-javaagent:" + stubJar.toAbsolutePath()
                + ", -Dspidersense.service=scratch]");
    }

    @Test
    void theProjectPropertySwitchesItOff() {
        String output = probe("-PspiderSense.enabled=false");

        assertThat(output).contains("bootRun=[]");
        assertThat(output).contains("run=[]");
    }

    @Test
    void theProjectPropertySwitchesOnABlockThatIsOff() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                enabled = false
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("bootRun=[]");
        assertThat(probe("-PspiderSense.enabled=true"))
                .contains("bootRun=[-javaagent:" + stubJar.toAbsolutePath() + ", -Dspidersense.service=scratch]");
    }

    @Test
    void theProjectPropertyJarWinsOverTheBlock() {
        String output = probe("-PspiderSense.jar=other.jar");

        assertThat(output).contains("bootRun=[-javaagent:" + realProjectDir() + "/other.jar,");
    }

    @Test
    void theConfigurationDefaultsToTheJarOfTheSameVersion() throws IOException {
        String version = rootVersion();
        Path repository = mavenRepository(version);
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'net.benelog.spidersense'
                }
                repositories {
                    maven { url = uri('%REPOSITORY%') }
                }
                tasks.register('bootRun', JavaExec)

                def contributed = { String name ->
                    tasks.named(name, JavaExec).get().jvmArgumentProviders.collectMany { it.asArguments() }
                }
                tasks.register('probe') {
                    def lines = [
                            "bootRun=" + contributed('bootRun'),
                            "dependencies=" + configurations.spiderSense.allDependencies
                                    .collect { "${it.group}:${it.name}:${it.version}" },
                    ]
                    doLast {
                        lines.each { println it }
                    }
                }
                """.replace("%REPOSITORY%", repository.toUri().toString()));

        String output = probe();

        assertThat(output).contains("dependencies=[net.benelog.spidersense:spider-sense:" + version + "]");
        assertThat(output).contains("-javaagent:" + repository.resolve(
                "net/benelog/spidersense/spider-sense/" + version + "/spider-sense-" + version + ".jar"));
    }

    /** The version in the root build's gradle.properties, the one place a release version is written. */
    private static String rootVersion() throws IOException {
        Properties root = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("../gradle.properties"))) {
            root.load(in);
        }
        return root.getProperty("version");
    }

    /** A file repository holding the jar the configuration's default dependency names, so nothing reaches the network. */
    private Path mavenRepository(String version) throws IOException {
        Path module = Files.createDirectories(projectDir.resolve(
                "repository/net/benelog/spidersense/spider-sense/" + version));
        Files.copy(stubJar, module.resolve("spider-sense-" + version + ".jar"));
        Files.writeString(module.resolve("spider-sense-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>net.benelog.spidersense</groupId>
                  <artifactId>spider-sense</artifactId>
                  <version>%VERSION%</version>
                  <packaging>jar</packaging>
                </project>
                """.replace("%VERSION%", version));
        return projectDir.resolve("repository");
    }

    @Test
    void theSpiderSenseTaskRunsTheJarAndLeavesTheUrlToTheCliWhenTheBlockNamesNone() {
        String output = gradle("spiderSense", "-q").build().getOutput();

        assertThat(output)
                .as("the CLI's own default applies, which honours a properties file")
                .contains("SPIDERSENSE_URL=null");
        assertThat(output).contains("args=[]");
        assertThat(output).contains("-Dspidersense.service=scratch");
    }

    @Test
    void theSpiderSenseTaskPassesTheConfigFileAndLetsTheCliReadThePortFromIt() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                configFile = file('sense.properties')
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        String output = gradle("spiderSense", "-q", "--args=status").build().getOutput();

        assertThat(output).contains("-Dspidersense.config=" + projectDir.resolve("sense.properties").toAbsolutePath());
        assertThat(output).contains("SPIDERSENSE_URL=null");
    }

    @Test
    void aPortInTheBlockStillNamesTheUrlBesideAConfigFile() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                configFile = file('sense.properties')
                host = '0.0.0.0'
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(gradle("spiderSense", "-q").build().getOutput())
                .contains("SPIDERSENSE_URL=http://127.0.0.1:4000");
    }

    @Test
    void theSpiderSenseTaskFollowsThePort() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                port = 4001
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        String output = gradle("spiderSense", "-q", "--args=status").build().getOutput();

        assertThat(output).contains("SPIDERSENSE_URL=http://127.0.0.1:4001");
        assertThat(output).contains("args=[status]");
    }

    @Test
    void theSpiderSenseTaskFollowsTheCollector() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                port = 4001
                collector = 'http://collector.example:4321'
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(gradle("spiderSense", "-q").build().getOutput())
                .contains("SPIDERSENSE_URL=http://collector.example:4321");
    }

    @Test
    void theInitTaskNamesTheProjectDirectoryAndTheJar() {
        String output = gradle("spiderSenseInit", "-q").build().getOutput();

        assertThat(output).contains("args=[init, --dir=" + realProjectDir()
                + ", --jar=" + stubJar.toAbsolutePath() + "]");
    }

    @Test
    void theCheckTaskIsInTheGroupAndJudgesTheWindowSinceTheApplicationStarted() {
        String output = probe();

        assertThat(output).contains("checkGroup=spider sense");
        assertThat(output).contains("check=[check, --since=start, --service=scratch]");
    }

    @Test
    void everyRuleOfTheCheckBlockBecomesAnArgument() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                check {
                    maxP95Ms = 300
                    maxNPlusOne = 0
                    minApdex = 0.9
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("check=[check, --since=start, --service=scratch,"
                + " --max-p95-ms=300, --max-n-plus-one=0, --min-apdex=0.9]");
    }

    @Test
    void theWholeCheckBlockIsPassedInTheOrderOfTheTable() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                service = 'orders'
                check {
                    since = 'before'
                    until = 'after'
                    endpoint = 'GET /orders/{id}'
                    maxP95Ms = 300
                    maxErrors = 0
                    maxErrorRate = 0.01
                    maxQueriesPerRequest = 5.5
                    maxSlowQueries = 3
                    maxNPlusOne = 0
                    maxLogErrors = 2
                    minApdex = 0.9
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("check=[check, --since=before, --until=after, --service=orders,"
                + " --endpoint=GET /orders/{id}, --max-p95-ms=300, --max-errors=0, --max-error-rate=0.01,"
                + " --max-queries-per-request=5.5, --max-slow-queries=3, --max-n-plus-one=0,"
                + " --max-log-errors=2, --min-apdex=0.9]");
    }

    @Test
    void theCheckServiceFollowsTheBlockAndCanBeOverridden() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                service = 'orders'
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("check=[check, --since=start, --service=orders]");

        buildFile("""
                jar = file('%JAR%')
                service = 'orders'
                check {
                    service = 'bookstore'
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("check=[check, --since=start, --service=bookstore]");
    }

    @Test
    void theProjectPropertySinceWinsOverTheCheckBlock() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                check {
                    since = '15m'
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        assertThat(probe()).contains("check=[check, --since=15m, --service=scratch]");
        assertThat(probe("-PspiderSense.check.since=before"))
                .contains("check=[check, --since=before, --service=scratch]");
    }

    @Test
    void theCheckTaskRunsTheJarAndPointsItAtTheSpiderSenseTheBlockImplies() throws IOException {
        buildFile("""
                jar = file('%JAR%')
                port = 4001
                check {
                    maxErrors = 0
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString()));

        String output = gradle("spiderSenseCheck", "-q").build().getOutput();

        assertThat(output).contains("SPIDERSENSE_URL=http://127.0.0.1:4001");
        assertThat(output).contains("args=[check, --since=start, --service=scratch, --max-errors=0]");
    }

    @Test
    void aFailedCheckFailsTheBuildAndTheCliKeepsItsOutput() throws IOException {
        String output = checkExiting(1, "").buildAndFail().getOutput();

        assertThat(output).contains("Spider Sense check failed");
        assertThat(output).contains("args=[check, --since=start, --service=scratch]");
    }

    @Test
    void aWindowWithNoRequestFailsTheBuildUnlessTheBlockSaysOtherwise() throws IOException {
        assertThat(checkExiting(3, "").buildAndFail().getOutput())
                .contains("Spider Sense check had no request to judge");

        assertThat(checkExiting(3, "failOnNoRequests = false").build().getTasks()).isNotEmpty();
    }

    @Test
    void aCheckThatCouldNotRunFailsTheBuildWithItsExitCode() throws IOException {
        assertThat(checkExiting(2, "").buildAndFail().getOutput())
                .contains("Spider Sense check could not run (exit 2)");

        assertThat(checkExiting(4, "").buildAndFail().getOutput())
                .contains("Spider Sense check could not run (exit 4)");
    }

    /**
     * The check task against a CLI that exits with the given code: the four
     * verdicts without a Spider Sense to produce them, the stub jar standing in
     * for the real one.
     */
    private GradleRunner checkExiting(int exit, String rules) throws IOException {
        buildFile("""
                jar = file('%JAR%')
                check {
                %RULES%
                }
                """.replace("%JAR%", stubJar.toAbsolutePath().toString())
                        .replace("%RULES%", rules.indent(4).stripTrailing()),
                "tasks.named('spiderSenseCheck') { jvmArgs '-Dstub.exit=" + exit + "' }");
        return gradle("spiderSenseCheck", "-q");
    }

    @Test
    void theConfigurationCacheIsReused() {
        gradle("probe", "--configuration-cache").build();

        BuildResult second = gradle("probe", "--configuration-cache").build();

        assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    }

    /** The check task stores too: its arguments are providers and its verdict action holds no project. */
    @Test
    void theConfigurationCacheIsReusedByTheCheckTask() {
        gradle("spiderSenseCheck", "--configuration-cache").build();

        BuildResult second = gradle("spiderSenseCheck", "--configuration-cache").build();

        assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    }
}
