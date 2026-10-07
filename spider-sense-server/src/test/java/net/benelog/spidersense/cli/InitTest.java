package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.json.Json;

/**
 * {@code init --mcp}: the project's {@code .mcp.json}, for a host that has no
 * shell and launches its tools as a process (agent-skill.adoc#init).
 *
 * <p>The skills and the idempotence are covered by {@link CliTest}; what is here
 * is the third line and the file it names, and the lines of the block that the
 * project's build and the options {@code --gradle} and {@code --url} change.
 */
class InitTest {

    private static final String JAR = "/x/spider-sense.jar";

    private record Run(int exit, String out, String err) {
    }

    private static Run init(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] all = new String[args.length + 1];
        all[0] = "init";
        System.arraycopy(args, 0, all, 1, args.length);
        int exit;
        try (PrintStream toOut = new PrintStream(out, true, UTF_8);
                PrintStream toErr = new PrintStream(err, true, UTF_8)) {
            exit = Cli.run(all, "http://127.0.0.1:1", toOut, toErr);
        }
        return new Run(exit, out.toString(UTF_8), err.toString(UTF_8));
    }

    private static Json.JsonObject read(Path file) throws IOException {
        return Json.parse(Files.readString(file, UTF_8)).asObject();
    }

    private static Json.JsonObject spiderSense(Json.JsonObject root) {
        return root.getObject("mcpServers").getObject("spider-sense");
    }

    @Test
    void mcpWritesTheStdioServerAsAThirdLine(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(run.out().lines()).hasSize(3);
        assertThat(run.out()).endsWith("wrote .mcp.json (spider-sense over stdio)\n");

        Json.JsonObject entry = spiderSense(read(project.resolve(".mcp.json")));
        assertThat(entry.getString("command")).isEqualTo("java");
        assertThat(entry.getArray("args").values().stream().map(Json.JsonValue::asString))
                .containsExactly("-jar", JAR, "mcp");

        assertThat(Files.readString(project.resolve(".mcp.json"), UTF_8))
                .as("a file people open and edit").contains("\n  \"mcpServers\": {");
    }

    @Test
    void withoutMcpNothingIsWrittenAndNothingIsSaidAboutIt(@TempDir Path project) {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill");

        assertThat(run.out().lines()).hasSize(2);
        assertThat(run.out()).doesNotContain(".mcp.json");
        assertThat(project.resolve(".mcp.json")).doesNotExist();
    }

    /** {@code --no-skill} skips every skill, not only the first one (agent-skill.adoc#init). */
    @Test
    void noSkillInstallsNeitherSkill(@TempDir Path project) {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(run.out()).endsWith("skipped skills (--no-skill)\n");
        assertThat(project.resolve(".claude/skills/spider-sense")).doesNotExist();
        assertThat(project.resolve(".claude/skills/spider-sense-sql-tuning")).doesNotExist();
    }

    @Test
    void aSecondRunUpdatesTheEntryAndKeepsEveryOtherOne(@TempDir Path project) throws IOException {
        Path file = project.resolve(".mcp.json");
        Files.writeString(file, """
                {
                  "mcpServers": {
                    "other": { "command": "node", "args": ["server.js"] },
                    "spider-sense": { "command": "java", "args": ["-jar", "/old.jar", "mcp"] }
                  },
                  "somethingElse": { "keep": true }
                }
                """, UTF_8);

        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).endsWith("updated .mcp.json (spider-sense over stdio)\n");

        Json.JsonObject root = read(file);
        assertThat(root.getObject("somethingElse").getBoolean("keep"))
                .as("a key beside mcpServers").isTrue();
        assertThat(root.getObject("mcpServers").keys())
                .as("in the order they were in").containsExactly("other", "spider-sense");
        assertThat(root.getObject("mcpServers").getObject("other").getString("command"))
                .isEqualTo("node");
        assertThat(spiderSense(root).getArray("args").get(1).asString()).isEqualTo(JAR);
        assertThat(Files.readString(file, UTF_8)).doesNotContain("/old.jar");
    }

    @Test
    void aFileThatIsNotAJsonObjectIsLeftAloneAndRefused(@TempDir Path project) throws IOException {
        Path file = project.resolve(".mcp.json");
        Files.writeString(file, "[\"not an object\"]\n", UTF_8);

        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains(".mcp.json").contains("not a JSON object");
        assertThat(Files.readString(file, UTF_8)).isEqualTo("[\"not an object\"]\n");
        assertThat(run.out()).doesNotContain(".mcp.json");
    }

    private static String block(Path project) throws IOException {
        return Files.readString(project.resolve("CLAUDE.md"), UTF_8);
    }

    /** The manual prints the block of a Gradle project as it is, so the two are one text (agent-skill.adoc#block). */
    @Test
    void theManualPrintsTheBlockOfAGradleProject() throws IOException {
        String page = Files.readString(Path.of("../manual/modules/ROOT/pages/agent-skill.adoc"), UTF_8);
        int start = page.indexOf("\n" + Init.START + "\n") + 1;
        int end = page.indexOf(Init.END, start) + Init.END.length();
        assertThat(start).as("the page shows the block").isPositive();
        assertThat(page.substring(start, end)).isEqualTo(Init.block(new Init.Setup(
                "/home/me/tools/spider-sense.jar", true, null, false, Init.Build.GRADLE)));
    }

    /** A Gradle build gets the Gradle sentence and not the Maven one (agent-skill.adoc#block-variants). */
    @Test
    void aGradleProjectGetsTheGradleSentenceAlone(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("build.gradle.kts"), "", UTF_8);

        assertThat(init("--dir=" + project, "--jar=" + JAR, "--no-skill").exit()).isZero();

        assertThat(block(project))
                .contains("./gradlew bootRun -PspiderSense.jar=" + JAR)
                .contains("`--no-daemon`")
                .doesNotContain("mvn spring-boot:run");
    }

    /** A Maven build gets the Spring Boot plugin's agents parameter, and nothing about Gradle. */
    @Test
    void aMavenProjectGetsTheMavenSentenceAlone(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>\n", UTF_8);

        assertThat(init("--dir=" + project, "--jar=" + JAR, "--no-skill").exit()).isZero();

        assertThat(block(project))
                .contains("Under Maven, run `mvn spring-boot:run -Dspring-boot.run.agents=" + JAR + "`")
                .contains("`JAVA_TOOL_OPTIONS=\"-javaagent:" + JAR + "\" <command>` attaches it.\n")
                .doesNotContain("gradlew")
                .contains("java -jar " + JAR + " findings --since=start");
    }

    /** A directory that names no build, or both, gets both sentences rather than a guess. */
    @Test
    void aDirectoryWithNoBuildFileGetsBothSentences(@TempDir Path project) throws IOException {
        assertThat(init("--dir=" + project, "--jar=" + JAR, "--no-skill").exit()).isZero();

        String text = block(project);
        assertThat(text).contains("./gradlew bootRun -PspiderSense.jar=" + JAR)
                .contains("mvn spring-boot:run -Dspring-boot.run.agents=" + JAR);
        assertThat(text.indexOf("./gradlew bootRun")).isLessThan(text.indexOf("mvn spring-boot:run"));
    }

    /**
     * Under the plugin, the plugin's own tasks start the application and ask the Spider Sense
     * the build names: no jar property to pass and no port to repeat.
     */
    @Test
    void gradleWritesThePluginsTasks(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>\n", UTF_8);

        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--gradle",
                "--url=http://127.0.0.1:4106/");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(block(project))
                .contains("`./gradlew bootRun` (or `run`) starts it under Spider Sense")
                .doesNotContain("-PspiderSense.jar=")
                .doesNotContain("mvn spring-boot:run")
                .contains("./gradlew -q spiderSense --args=\"findings --since=start\"  # ranked:")
                .contains("./gradlew -q spiderSense --args=\"check --max-p95-ms=300 --max-n-plus-one=0\"\n")
                .doesNotContain("java -jar " + JAR)
                .doesNotContain("--url=")
                .contains("The UI is then at <http://127.0.0.1:4106>.\n");
    }

    /** Without the plugin, a named URL goes on every line that asks, so none asks port 4000. */
    @Test
    void urlGoesOnEveryLineThatAsks(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--url=http://127.0.0.1:4106");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        String text = block(project);
        assertThat(text)
                .contains("java -jar " + JAR + " findings --since=start --url=http://127.0.0.1:4106  # ranked")
                .contains("java -jar " + JAR + " compare --before=before --after=after --url=http://127.0.0.1:4106\n")
                .contains("java -jar " + JAR + " help  # every command")
                .contains("The UI is then at <http://127.0.0.1:4106>.\n")
                .doesNotContain("unless the port was changed");
        assertThat(text.lines().filter(line -> line.startsWith("java -jar ")
                && !line.contains("--url=http://127.0.0.1:4106")))
                .as("help asks nothing")
                .containsExactly("java -jar " + JAR + " help  # every command and every option");
    }

    @Test
    void aFileThatIsNotJsonAtAllIsRefusedTheSameWay(@TempDir Path project) throws IOException {
        Path file = project.resolve(".mcp.json");
        Files.writeString(file, "# notes, not JSON\n", UTF_8);

        assertThat(init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp").exit())
                .isEqualTo(2);
        assertThat(Files.readString(file, UTF_8)).isEqualTo("# notes, not JSON\n");
    }
}
