package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonValue;
import net.benelog.silkjson.JsonObject;

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

    private static JsonObject read(Path file) throws IOException {
        return Json.parse(Files.readString(file, UTF_8)).asObject();
    }

    private static JsonObject spiderSense(JsonObject root) {
        return root.getObject("mcpServers").getObject("spider-sense");
    }

    @Test
    void mcpWritesTheStdioServerAsAThirdLine(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(run.out().lines()).hasSize(3);
        assertThat(run.out()).endsWith("wrote .mcp.json (spider-sense over stdio)\n");

        JsonObject entry = spiderSense(read(project.resolve(".mcp.json")));
        assertThat(entry.getString("command")).isEqualTo("java");
        assertThat(entry.getArray("args").values().stream().map(JsonValue::asString))
                .containsExactly("-jar", JAR, "mcp");

        assertThat(Files.readString(project.resolve(".mcp.json"), UTF_8))
                .as("a file people open and edit").contains("\n  \"mcpServers\": {");
    }

    /** With {@code --mcp} the host asks through the server alone, so a named URL goes into its arguments. */
    @Test
    void mcpPassesTheUrlToTheServer(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--mcp", "--url=http://127.0.0.1:4106/");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(spiderSense(read(project.resolve(".mcp.json"))).getArray("args").values().stream()
                .map(JsonValue::asString))
                .containsExactly("-jar", JAR, "mcp", "--url=http://127.0.0.1:4106");
        assertThat(block(project)).contains("The UI is then at <http://127.0.0.1:4106>.\n");
    }

    /**
     * The MCP server instead of the CLI, never beside it: the block names the tools, not the
     * CLI's lines, and no skill teaching the CLI is installed (agent-skill.adoc#init-output).
     */
    @Test
    void mcpWritesABlockThatNamesTheToolsAndInstallsNoSkill(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--mcp");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(run.out().lines().toList()).containsExactly(
                "wrote CLAUDE.md block (jar: " + JAR + ")",
                "skipped skills (--mcp)",
                "wrote .mcp.json (spider-sense over stdio)");
        assertThat(project.resolve(".claude")).doesNotExist();
        assertThat(block(project))
                .contains("java -javaagent:" + JAR + " -jar <app jar>")
                .contains("the `spider-sense` MCP server in `.mcp.json`: `status` first")
                .doesNotContain("java -jar " + JAR)
                .doesNotContain("Ask it from the terminal")
                .doesNotContain("SKILL.md")
                .endsWith("the loop.\n" + Init.END + "\n");
    }

    /** Codex and the others read AGENTS.md, so a project that keeps only that file gets the block there. */
    @Test
    void anAgentsFileAloneGetsTheBlock(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("AGENTS.md"), "# Rules\n", UTF_8);

        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill");

        assertThat(run.out()).startsWith("wrote AGENTS.md block (jar: " + JAR + ")\n");
        assertThat(project.resolve("CLAUDE.md")).doesNotExist();
        assertThat(Files.readString(project.resolve("AGENTS.md"), UTF_8))
                .startsWith("# Rules\n\n" + Init.START);
    }

    /** A project that keeps both files gets the block in both, one line each, CLAUDE.md first. */
    @Test
    void bothFilesGetTheBlock(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("AGENTS.md"), "# Rules\n", UTF_8);
        Files.writeString(project.resolve("CLAUDE.md"), "# Notes\n", UTF_8);

        init("--dir=" + project, "--jar=" + JAR, "--no-skill");
        Run again = init("--dir=" + project, "--jar=" + JAR, "--no-skill");

        assertThat(again.out().lines().toList()).containsExactly(
                "updated CLAUDE.md block (jar: " + JAR + ")",
                "updated AGENTS.md block (jar: " + JAR + ")",
                "skipped skills (--no-skill)");
        assertThat(Files.readString(project.resolve("CLAUDE.md"), UTF_8)).contains(Init.START);
        assertThat(Files.readString(project.resolve("AGENTS.md"), UTF_8)).contains(Init.START);
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

        JsonObject root = read(file);
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
        assertThat(run.out()).as("nothing is written before the file is known to be ours").isEmpty();
        assertThat(project.resolve("CLAUDE.md")).doesNotExist();
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
                "/home/me/tools/spider-sense.jar", true, null, false, Init.Build.GRADLE, false)));
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

    /** A URL typed without a scheme is the one the CLI reads, so the UI sentence is a link (cli.adoc#fallback). */
    @Test
    void aUrlWithoutASchemeIsWrittenAsTheCliReadsIt(@TempDir Path project) throws IOException {
        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--url=127.0.0.1:4106/");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(block(project))
                .contains("The UI is then at <http://127.0.0.1:4106>.\n")
                .contains("java -jar " + JAR + " findings --since=start --url=http://127.0.0.1:4106  # ranked")
                .doesNotContain("<127.0.0.1");
    }

    /**
     * A jar path with a space is quoted in every command the agent copies into a shell, and in
     * single quotes inside {@code JAVA_TOOL_OPTIONS}, whose quotes the JVM reads (agent-skill.adoc#block).
     */
    @Test
    void aJarPathWithASpaceIsQuotedInEveryCommand(@TempDir Path project) throws IOException {
        String jar = "/opt/my tools/spider-sense.jar";
        Run run = init("--dir=" + project, "--jar=" + jar, "--no-skill");

        assertThat(run.exit()).as("stderr: %s", run.err()).isZero();
        assertThat(block(project))
                .contains("and the jar is at `" + jar + "`.")
                .contains("`java -javaagent:'" + jar + "' -jar <app jar>`")
                .contains("`./gradlew bootRun -PspiderSense.jar='" + jar + "'`")
                .contains("`mvn spring-boot:run -Dspring-boot.run.agents='" + jar + "'`")
                .contains("`JAVA_TOOL_OPTIONS=\"-javaagent:'" + jar + "'\" <command>`")
                .contains("java -jar '" + jar + "' findings --since=start  # ranked")
                .doesNotContain("-javaagent:" + jar)
                .doesNotContain("java -jar " + jar);
    }

    /**
     * A jar path with an apostrophe or a character a shell expands is quoted so that the shell
     * passes it on as it is, and inside {@code JAVA_TOOL_OPTIONS} with the quote the JVM can read it
     * in (agent-skill.adoc#block).
     */
    @Test
    void aJarPathWithAnApostropheOrADollarIsQuotedForTheShellAndTheJvm() {
        assertThat(blockFor("/opt/O'Brien tools/spider-sense.jar"))
                .contains("`java -javaagent:'/opt/O'\\''Brien tools/spider-sense.jar' -jar <app jar>`")
                .contains("`JAVA_TOOL_OPTIONS=\"-javaagent:\\\"/opt/O'Brien tools/spider-sense.jar\\\"\" <command>`");
        assertThat(blockFor("/opt/tools$v2/spider-sense.jar"))
                .contains("`java -javaagent:'/opt/tools$v2/spider-sense.jar' -jar <app jar>`")
                .contains("`JAVA_TOOL_OPTIONS=\"-javaagent:'/opt/tools\\$v2/spider-sense.jar'\" <command>`");
    }

    /** Every quoted form is the path again once bash has read it, and once the JVM has read the variable. */
    @Test
    void theQuotedJarPathIsThePathToBashAndToTheJvm() throws IOException, InterruptedException {
        Path bash = Path.of("/bin/bash");
        assumeTrue(Files.isExecutable(bash), "bash runs the block's commands");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (String jar : List.of("/opt/my tools/spider-sense.jar", "/opt/O'Brien tools/spider-sense.jar",
                "/opt/tools$v2/spider-sense.jar", "/opt/back`tick` dir/spider-sense.jar",
                "/opt/say \"hi\"/spider-sense.jar", "/opt/it's \"both\" here/spider-sense.jar",
                "/opt/C:\\Tools\\$x\\\\/spider-sense.jar")) {
            String block = blockFor(jar);
            String word = between(block, "`java -javaagent:", " -jar <app jar>`");
            assertThat(run(bash.toString(), "-c", "printf '%s' " + word)).as(jar).isEqualTo(jar);

            // The variable as the block sets it, with a property in the agent's place, quoted alike.
            String variable = between(block, "`JAVA_TOOL_OPTIONS=", " <command>`")
                    .replace("-javaagent:", "-Dquoted.jar=");
            String settings = run(bash.toString(), "-c",
                    "JAVA_TOOL_OPTIONS=" + variable + " exec \"$0\" -XshowSettings:properties -version", java);
            assertThat(settings).as(jar).contains("    quoted.jar = " + jar + "\n");
        }
    }

    private static String blockFor(String jar) {
        return Init.block(new Init.Setup(jar, false, null, false, Init.Build.UNKNOWN, false));
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertThat(from).as("%s in the block", start).isNotNegative();
        from += start.length();
        return text.substring(from, text.indexOf(end, from));
    }

    /** A command's stdout and stderr together. */
    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        process.waitFor();
        return output;
    }

    /**
     * A start marker whose end was edited away stops init before it writes anything: a second
     * block appended after it would have the next run delete the text between the two.
     */
    @Test
    void aStartMarkerWithoutAnEndIsRefused(@TempDir Path project) throws IOException {
        String text = "# Notes\n" + Init.START + "\nold stuff\n";
        Files.writeString(project.resolve("CLAUDE.md"), text, UTF_8);

        Run run = init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("CLAUDE.md has `" + Init.START + "` at line 2 without an end marker");
        assertThat(run.out()).isEmpty();
        assertThat(Files.readString(project.resolve("CLAUDE.md"), UTF_8)).isEqualTo(text);
        assertThat(project.resolve(".mcp.json")).doesNotExist();
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
