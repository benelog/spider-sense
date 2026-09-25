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
 * {@code java -jar spider-sense.jar init}: the few lines a project's {@code CLAUDE.md}
 * needs about Spider Sense, and a copy of the agent skills beside them (agent-skill.adoc#init).
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
    private static final String JAR = "${jar}";

    private static final String BODY = """
            ## Spider Sense

            Spider Sense is a local-development observability tool for this project, and the jar is at `${jar}`.
            Start the application under it with `java -javaagent:${jar} -jar <app jar>`.
            Under Gradle, apply the `net.benelog.spidersense` plugin and run `./gradlew bootRun -PspiderSense.jar=${jar}` (or `run`): it puts the agent on the application's JVM and not on the Gradle daemon.
            When the start command is not yours to change, `JAVA_TOOL_OPTIONS="-javaagent:${jar}" <command>` attaches it; a `./gradlew` command then needs `--no-daemon`, or the daemon hosts a Spider Sense of its own on port 4000.
            The UI is then at <http://127.0.0.1:4000> unless the port was changed.

            Ask it from the terminal; every answer is Markdown made for an agent:

            ```bash
            java -jar ${jar} findings --since=start  # ranked: N+1, slow queries, slow endpoints, errors, exhausted pools
            java -jar ${jar} trace <id>  # one request as a tree
            java -jar ${jar} mark before  # name a moment, exercise, then compare
            java -jar ${jar} compare --before=before --after=after
            java -jar ${jar} check --max-p95-ms=300 --max-n-plus-one=0
            java -jar ${jar} help  # every command and every option
            ```

            """;

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

    /** The block exactly as agent-skill.adoc#block prints it, with the jar path filled in. */
    static String block(String jar, boolean skillInstalled) {
        String body = BODY + (skillInstalled ? SKILL_HERE : SKILL_ELSEWHERE);
        return (START + "\n" + body + END).replace(JAR, jar);
    }

    static int run(Options options, PrintStream out, PrintStream err) {
        Path dir = directory(options);
        String jar = jar(options);
        if (jar == null) {
            err.println("spider-sense: init cannot tell where the jar is; run it as "
                    + "java -jar <path>/spider-sense.jar init, or name the jar with --jar=<path>");
            return Cli.USAGE;
        }
        boolean withSkill = !options.flag("no-skill");
        try {
            out.println(writeBlock(dir, block(jar, withSkill)) + " CLAUDE.md block (jar: " + jar + ")");
            if (withSkill) {
                Path target = dir.resolve(Paths.get(SKILL_TARGET));
                installSkills(target).forEach((skill, files) ->
                        out.println("installed skill to " + target.resolve(skill) + " (" + files + " files)"));
            } else {
                out.println("skipped skills (--no-skill)");
            }
            // Without --mcp nothing is written and nothing is said: a host with a shell
            // is meant to use the CLI, and init should not hand it a second tool for
            // the same answers (agent-loop.adoc#choosing-an-interface).
            return options.flag("mcp") ? writeMcpServer(dir, jar, out, err) : Cli.OK;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
     *
     * <p>A file that is not a JSON object is left exactly as it is: it is not ours
     * to guess at, and overwriting a host's configuration would be worse than
     * refusing.
     */
    private static int writeMcpServer(Path dir, String jar, PrintStream out, PrintStream err)
            throws IOException {
        Path file = dir.resolve(".mcp.json");
        boolean existed = Files.exists(file);
        Json.JsonObject root = Json.obj();
        if (existed) {
            Json.JsonValue parsed = null;
            try {
                parsed = Json.parse(Files.readString(file, UTF_8));
            } catch (RuntimeException e) {
                // Not JSON at all; the message below says the same thing either way.
            }
            if (!(parsed instanceof Json.JsonObject object)) {
                err.println("spider-sense: " + file + " is not a JSON object; "
                        + "--mcp left it as it was");
                return Cli.USAGE;
            }
            root = object;
        }
        Json.JsonObject servers = root.has("mcpServers")
                && root.get("mcpServers") instanceof Json.JsonObject existing ? existing : Json.obj();
        servers.put(net.benelog.spidersense.mcp.McpServer.NAME, Json.obj()
                .put("command", "java")
                .put("args", Json.arr().add("-jar").add(jar).add(Options.MCP)));
        root.put("mcpServers", servers);
        Files.writeString(file, pretty(root) + "\n", UTF_8);
        out.println((existed ? "updated" : "wrote") + " .mcp.json (spider-sense over stdio)");
        return Cli.OK;
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
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Json.JsonValue value, int depth) {
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
                write(out, object.get(keys.get(i)), depth + 1);
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
                write(out, array.get(i), depth + 1);
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
     * Writes the block into {@code <dir>/CLAUDE.md} and says which of the two things it
     * did: {@code wrote} or {@code updated}.
     *
     * <p>Only the span between the markers is ever rewritten. The file is not parsed,
     * not reformatted and not re-encoded beyond UTF-8 in and UTF-8 out, because it is
     * the project's file and Spider Sense is a guest in it.
     */
    private static String writeBlock(Path dir, String block) throws IOException {
        Path file = dir.resolve("CLAUDE.md");
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
