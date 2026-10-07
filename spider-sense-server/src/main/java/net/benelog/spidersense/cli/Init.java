package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * {@code java -jar spider-sense.jar init}: the few lines a project's {@code CLAUDE.md} or
 * {@code AGENTS.md} needs about Spider Sense, and a copy of the agent skills beside them
 * (agent-skill.adoc#init).
 *
 * <p>The one command that reads nothing: no HTTP, no database, no running Spider
 * Sense, so {@link Cli} answers it before it ever decides between {@link Remote} and
 * {@link Local}.
 *
 * <p>It is idempotent by construction. The block is delimited by two HTML comments,
 * a second run replaces what is between them, and everything outside them is copied
 * through byte for byte: an agent may run {@code init} whenever it is unsure, and a
 * hand-written {@code CLAUDE.md} survives it.
 */
final class Init {

    /** The markers that make a second {@code init} a replacement rather than a copy. */
    static final String START = "<!-- spider-sense:start -->";
    static final String END = "<!-- spider-sense:end -->";

    /**
     * Where the launcher leaves the distributable jar's absolute path. The CLI runs out
     * of the nested server jar, extracted to a temporary directory, so this is the only
     * way it can name the jar its user typed (design.adoc#premain).
     */
    static final String JAR_PROPERTY = "spidersense.jar";

    /** The skills inside the jar, and the index the build writes because a class loader cannot list. */
    static final String SKILL_PREFIX = "spider-sense/skills/";
    static final String SKILL_INDEX = SKILL_PREFIX + "index.txt";

    /** Where the skills are installed inside the project, as Claude Code looks for them. */
    static final String SKILL_TARGET = ".claude/skills";

    /** The placeholder the jar path replaces; the block is written once, here. */
    private static final String JAR_PLACEHOLDER = "${jar}";

    /** One CLI line of the block: the command, and the comment beside it when it has one. */
    private record Line(String command, @Nullable String comment) {
    }

    /** The CLI lines, which {@link #ask} writes as {@code java -jar} or as the Gradle task. */
    private static final List<Line> COMMANDS = List.of(
            new Line("findings --since=start", "ranked: N+1, slow queries, slow endpoints, errors, exhausted pools"),
            new Line("findings --since=15m",
                    "the last 15 minutes instead; start exists once the application has reported"),
            new Line("trace <id>", "one request as a tree"),
            new Line("mark before", "name a moment, exercise, then compare"),
            new Line("compare --before=before --after=after", null),
            new Line("check --max-p95-ms=300 --max-n-plus-one=0", null),
            new Line("help", "every command and every option"));

    /** The files that make {@code --dir} a Gradle build. */
    private static final List<String> GRADLE_FILES =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /** The file that makes {@code --dir} a Maven build. */
    private static final String MAVEN_FILE = "pom.xml";

    /** Which build tool's start-up sentence the block carries, read from the files in {@code --dir}. */
    enum Build {
        GRADLE, MAVEN, UNKNOWN;

        /**
         * Gradle when a Gradle build or settings file is there and no {@code pom.xml}, Maven the other
         * way round, and unknown when there are both or neither: a directory that does not say is not
         * guessed at, and the block then carries both sentences.
         */
        static Build of(Path dir) {
            boolean gradle = GRADLE_FILES.stream().anyMatch(name -> Files.isRegularFile(dir.resolve(name)));
            boolean maven = Files.isRegularFile(dir.resolve(MAVEN_FILE));
            if (gradle == maven) {
                return UNKNOWN;
            }
            return gradle ? GRADLE : MAVEN;
        }
    }

    /**
     * What the block is written for (agent-skill.adoc#block).
     *
     * @param jar            the jar's absolute path
     * @param skillInstalled whether the skills were copied into the project
     * @param url            the Spider Sense the application sends to ({@code --url}), or null for the default
     * @param gradlePlugin   the project applies the Gradle plugin ({@code --gradle}), so its tasks
     *                       start the application and run the CLI
     * @param build          the build tool the project directory shows
     * @param mcp            the host asks through the MCP server ({@code --mcp}), so the block
     *                       names its tools instead of the CLI's lines and the skills
     */
    record Setup(String jar, boolean skillInstalled, @Nullable String url, boolean gradlePlugin, Build build,
            boolean mcp) {
    }

    /** The files the block goes into, as Claude Code and as Codex and the others read them. */
    private static final String CLAUDE_FILE = "CLAUDE.md";
    private static final String AGENTS_FILE = "AGENTS.md";

    /** Where {@code --mcp} writes the stdio server, which is where a host that reads it looks. */
    private static final String MCP_FILE = ".mcp.json";

    /** What stands in place of the CLI lines and the skill lines under {@code --mcp}. */
    private static final String ASK_MCP = "Ask it through the tools of the `spider-sense` MCP server in "
            + "`.mcp.json`: `status` first, to confirm the application is collecting, then `mark`, "
            + "`findings`, `trace`, `compare`, `check` and `resolve`; the server's instructions carry "
            + "the loop.\n";

    private static final String SKILL_HERE = "The loop — start, mark, exercise, findings, fix, compare, "
            + "check — is in the skill at `.claude/skills/spider-sense/SKILL.md`.\n"
            + "Query tuning — an index to add, a rewrite, a fetch join, a batch — is in the skill at "
            + "`.claude/skills/spider-sense-sql-tuning/SKILL.md`.\n";

    private static final String SKILL_ELSEWHERE = "The loop — start, mark, exercise, findings, fix, compare, "
            + "check — is in the skill under `skills/spider-sense/` of the Spider Sense repository, "
            + "<https://github.com/benelog/spider-sense>.\n"
            + "Query tuning — an index to add, a rewrite, a fetch join, a batch — is in the skill under "
            + "`skills/spider-sense-sql-tuning/` of the same repository.\n";

    private Init() {
    }

    /**
     * The block exactly as agent-skill.adoc#block prints it, with the jar path filled in: the
     * start-up sentences of the project's build tool, the CLI lines in the form that reaches the
     * project's Spider Sense, and the skill lines last.
     */
    static String block(Setup setup) {
        boolean plugin = setup.gradlePlugin();
        boolean gradle = plugin || setup.build() != Build.MAVEN;
        StringBuilder body = new StringBuilder("## Spider Sense\n\n")
                .append("Spider Sense is a local-development observability tool for this project, "
                        + "and the jar is at `${jar}`.\n")
                .append("Start the application under it with `java -javaagent:${jar} -jar <app jar>`.\n");
        if (plugin) {
            body.append("This project applies the `net.benelog.spidersense` Gradle plugin, so "
                    + "`./gradlew bootRun` (or `run`) starts it under Spider Sense: the plugin puts the "
                    + "agent on the application's JVM and not on the Gradle daemon.\n");
        } else if (gradle) {
            body.append("Under Gradle, apply the `net.benelog.spidersense` plugin and run "
                    + "`./gradlew bootRun -PspiderSense.jar=${jar}` (or `run`): it puts the agent on the "
                    + "application's JVM and not on the Gradle daemon.\n");
        }
        if (!plugin && setup.build() != Build.GRADLE) {
            body.append("Under Maven, run `mvn spring-boot:run -Dspring-boot.run.agents=${jar}`: the "
                    + "Spring Boot plugin puts the agent on the JVM it forks for the application.\n");
        }
        body.append("When the start command is not yours to change, "
                + "`JAVA_TOOL_OPTIONS=\"-javaagent:${jar}\" <command>` attaches it")
                .append(gradle
                        ? "; a `./gradlew` command then needs `--no-daemon`, or the daemon hosts a "
                                + "Spider Sense of its own on port 4000.\n"
                        : ".\n");
        String url = setup.url();
        body.append(url == null
                ? "The UI is then at <http://127.0.0.1:4000> unless the port was changed.\n"
                : "The UI is then at <" + url + ">.\n");
        if (setup.mcp()) {
            body.append('\n').append(ASK_MCP);
        } else {
            body.append("\nAsk it from the terminal; every answer is Markdown made for an agent:\n\n")
                    .append(ask(setup))
                    .append('\n')
                    .append(setup.skillInstalled() ? SKILL_HERE : SKILL_ELSEWHERE);
        }
        return (START + "\n" + body + END).replace(JAR_PLACEHOLDER, setup.jar());
    }

    /**
     * The fenced CLI lines: {@code ./gradlew -q spiderSense --args="…"} under the plugin, whose
     * task already asks the Spider Sense the build names, and {@code java -jar} otherwise, with
     * {@code --url=} on every line that asks a Spider Sense when one was named.
     */
    private static String ask(Setup setup) {
        String url = setup.url();
        StringBuilder lines = new StringBuilder("```bash\n");
        for (Line line : COMMANDS) {
            String command = line.command();
            if (setup.gradlePlugin()) {
                lines.append("./gradlew -q spiderSense --args=\"").append(command).append('"');
            } else {
                lines.append("java -jar ${jar} ").append(command);
                if (url != null && !"help".equals(command)) {
                    lines.append(" --url=").append(url);
                }
            }
            String comment = line.comment();
            if (comment != null) {
                lines.append("  # ").append(comment);
            }
            lines.append('\n');
        }
        return lines.append("```\n").toString();
    }

    static int run(Options options, PrintStream out, PrintStream err) {
        Path dir = directory(options);
        String jar = jar(options);
        if (jar == null) {
            err.println("spider-sense: init cannot tell where the jar is; run it as "
                    + "java -jar <path>/spider-sense.jar init, or name the jar with --jar=<path>");
            return Cli.USAGE;
        }
        // --mcp hands the host the MCP server instead of the CLI, never beside it: two tools for
        // the same answers make the model choose between them (agent-loop.adoc#choosing-an-interface).
        boolean mcp = options.flag("mcp");
        boolean withSkill = !mcp && !options.flag("no-skill");
        String url = options.valueOrNull("url");
        Setup setup = new Setup(jar, withSkill, url == null || url.isBlank() ? null : Remote.trimSlash(url),
                options.flag("gradle"), Build.of(dir), mcp);
        try {
            Path mcpFile = dir.resolve(MCP_FILE);
            // Read before anything is written, so a file that is not ours to rewrite stops init
            // before the block names a server it could not configure.
            Json.JsonObject mcpConfig = mcp ? mcpConfig(mcpFile) : null;
            if (mcp && mcpConfig == null) {
                err.println("spider-sense: " + mcpFile + " is not a JSON object; "
                        + "--mcp left it as it was, and wrote nothing else");
                return Cli.USAGE;
            }
            String block = block(setup);
            for (String name : blockFiles(dir)) {
                out.println(writeBlock(dir.resolve(name), block) + " " + name + " block (jar: " + jar + ")");
            }
            if (withSkill) {
                Path target = dir.resolve(Paths.get(SKILL_TARGET));
                installSkills(target).forEach((skill, files) ->
                        out.println("installed skill to " + target.resolve(skill) + " (" + files + " files)"));
            } else {
                out.println(mcp ? "skipped skills (--mcp)" : "skipped skills (--no-skill)");
            }
            // Without --mcp nothing is written and nothing is said: a host with a shell
            // is meant to use the CLI.
            if (mcpConfig != null) {
                writeMcpServer(mcpFile, mcpConfig, jar, out);
            }
            return Cli.OK;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The files the block goes into: {@code AGENTS.md} when the project has one and no
     * {@code CLAUDE.md}, both when it has both, and {@code CLAUDE.md} otherwise, created
     * when it is not there (agent-skill.adoc#init).
     */
    private static List<String> blockFiles(Path dir) {
        boolean claude = Files.exists(dir.resolve(CLAUDE_FILE));
        boolean agents = Files.exists(dir.resolve(AGENTS_FILE));
        if (agents) {
            return claude ? List.of(CLAUDE_FILE, AGENTS_FILE) : List.of(AGENTS_FILE);
        }
        return List.of(CLAUDE_FILE);
    }

    /**
     * The project's {@code .mcp.json} as an object to add to, an empty one when there is no
     * file, or null when the file is not a JSON object.
     *
     * <p>A file that is not a JSON object is left exactly as it is: it is not ours
     * to guess at, and overwriting a host's configuration would be worse than
     * refusing.
     */
    private static Json.@Nullable JsonObject mcpConfig(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Json.obj();
        }
        try {
            return Json.parse(Files.readString(file, UTF_8)) instanceof Json.JsonObject object ? object : null;
        } catch (RuntimeException e) {
            // Not JSON at all, which is answered as any other file that is not an object.
            return null;
        }
    }

    /**
     * {@code mcpServers.spider-sense} in the project's {@code .mcp.json}, for a host
     * that launches its tools as a process (mcp.adoc).
     *
     * <p>Every other entry survives, at both levels, because the file is the
     * project's and Spider Sense is one server in it. It is rewritten in this
     * server's own JSON rather than in whatever formatting it had, which is the one
     * thing that is not preserved and the one thing a JSON parser does not carry.
     */
    private static void writeMcpServer(Path file, Json.JsonObject root, String jar, PrintStream out)
            throws IOException {
        boolean existed = Files.exists(file);
        Json.JsonObject servers = root.has("mcpServers")
                && root.get("mcpServers") instanceof Json.JsonObject existing ? existing : Json.obj();
        servers.put(net.benelog.spidersense.mcp.McpServer.NAME, Json.obj()
                .put("command", "java")
                .put("args", Json.arr().add("-jar").add(jar).add(Options.MCP)));
        root.put("mcpServers", servers);
        Files.writeString(file, pretty(root) + "\n", UTF_8);
        out.println((existed ? "updated" : "wrote") + " .mcp.json (spider-sense over stdio)");
    }

    /**
     * The same JSON, laid out two spaces at a time.
     *
     * <p>Every value is still written by {@code Json} itself — the escaping of a
     * Windows path in a string is not something to write twice — and this only
     * decides where the newlines go, because {@code .mcp.json} is a file people
     * open and edit.
     */
    private static String pretty(Json.JsonValue value) {
        StringBuilder out = new StringBuilder();
        writePretty(out, value, 0);
        return out.toString();
    }

    private static void writePretty(StringBuilder out, Json.JsonValue value, int depth) {
        if (value instanceof Json.JsonObject object) {
            if (object.size() == 0) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            List<String> keys = object.keys();
            for (int i = 0; i < keys.size(); i++) {
                indent(out, depth + 1);
                writeKey(out, keys.get(i));
                out.append(": ");
                writePretty(out, object.get(keys.get(i)), depth + 1);
                out.append(i < keys.size() - 1 ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof Json.JsonArray array) {
            if (array.size() == 0) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < array.size(); i++) {
                indent(out, depth + 1);
                writePretty(out, array.get(i), depth + 1);
                out.append(i < array.size() - 1 ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else {
            value.write(out);
        }
    }

    /** A key is a JSON string, so one is made and asked to write itself. */
    private static void writeKey(StringBuilder out, String key) {
        Json.arr().add(key).get(0).write(out);
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    /**
     * Writes the block into {@code CLAUDE.md} or {@code AGENTS.md} and says which of the two
     * things it did: {@code wrote} or {@code updated}.
     *
     * <p>Only the span between the markers is ever rewritten. The file is not parsed,
     * not reformatted and not re-encoded beyond UTF-8 in and UTF-8 out, because it is
     * the project's file and Spider Sense is a guest in it.
     */
    private static String writeBlock(Path file, String block) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, block + "\n", UTF_8);
            return "wrote";
        }
        String existing = Files.readString(file, UTF_8);
        int start = existing.indexOf(START);
        int end = existing.indexOf(END, start + START.length());
        if (start >= 0 && end > start) {
            Files.writeString(file,
                    existing.substring(0, start) + block + existing.substring(end + END.length()), UTF_8);
            return "updated";
        }
        String separator = existing.isEmpty() ? "" : existing.endsWith("\n") ? "\n" : "\n\n";
        Files.writeString(file, existing + separator + block + "\n", UTF_8);
        return "wrote";
    }

    /**
     * Copies every packaged skill into the project, one directory each, overwriting the
     * files it owns and leaving anything else in those directories alone.
     *
     * <p>A skill is a top-level directory of the index, so a new one in the repository's
     * {@code skills/} is installed by this without a line of code changing here.
     *
     * @return how many files were written, per skill, in the skills' name order
     */
    private static Map<String, Integer> installSkills(Path target) throws IOException {
        Map<String, Integer> written = new TreeMap<>();
        for (String name : index()) {
            int slash = name.indexOf('/');
            if (slash <= 0) {
                // A file directly under skills/ belongs to no skill; there is nowhere to put it.
                continue;
            }
            Path file = target.resolve(name).normalize();
            if (!file.startsWith(target)) {
                continue;
            }
            try (InputStream in = Init.class.getClassLoader().getResourceAsStream(SKILL_PREFIX + name)) {
                if (in == null) {
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                written.merge(name.substring(0, slash), 1, Integer::sum);
            }
        }
        return written;
    }

    /** The packaged paths relative to {@code skills/}, as the build's {@code skillIndex} task listed them. */
    private static List<String> index() throws IOException {
        try (InputStream in = Init.class.getClassLoader().getResourceAsStream(SKILL_INDEX)) {
            if (in == null) {
                throw new IllegalStateException("this build carries no skill: " + SKILL_INDEX
                        + " is missing; --no-skill writes the CLAUDE.md block alone");
            }
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), UTF_8).split("\n", -1)) {
                String name = line.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    private static Path directory(Options options) {
        Path dir = Paths.get(options.value("dir", ".")).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new Options.Usage("--dir is not a directory: " + dir);
        }
        return dir;
    }

    /** {@code --jar} when it was given, else the jar the launcher started us from, else nothing. */
    private static @Nullable String jar(Options options) {
        String named = options.valueOrNull("jar");
        String path = named == null || named.isBlank() ? System.getProperty(JAR_PROPERTY) : named;
        if (path == null || path.isBlank()) {
            return null;
        }
        return Paths.get(path.trim()).toAbsolutePath().normalize().toString();
    }
}
