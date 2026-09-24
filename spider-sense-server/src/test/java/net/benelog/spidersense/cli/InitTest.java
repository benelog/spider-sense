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
 * <p>Everything else {@code init} writes is covered by {@link CliTest}; what is
 * here is the third line and the file it names.
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

    @Test
    void aFileThatIsNotJsonAtAllIsRefusedTheSameWay(@TempDir Path project) throws IOException {
        Path file = project.resolve(".mcp.json");
        Files.writeString(file, "# notes, not JSON\n", UTF_8);

        assertThat(init("--dir=" + project, "--jar=" + JAR, "--no-skill", "--mcp").exit())
                .isEqualTo(2);
        assertThat(Files.readString(file, UTF_8)).isEqualTo("# notes, not JSON\n");
    }
}
