package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

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

/**
 * {@code java -jar spider-sense.jar init}: the few lines a project's {@code CLAUDE.md}
 * needs about Spider Sense, and a copy of the skill beside them (agent.md, "init").
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
     * way it can name the jar its user typed (design.md).
     */
    static final String JAR_PROPERTY = "spidersense.jar";

    /** The skill inside the jar, and the index the build writes because a class loader cannot list. */
    static final String SKILL_PREFIX = "spider-sense/skill/";
    static final String SKILL_INDEX = SKILL_PREFIX + "index.txt";

    /** Where the skill is installed inside the project, as Claude Code looks for it. */
    static final String SKILL_TARGET = ".claude/skills/spider-sense";

    /** The placeholder the jar path replaces; the block is written once, here. */
    private static final String JAR = "${jar}";

    private static final String BODY = """
            ## Spider Sense

            Spider Sense is a local-development APM for this project, and the jar is at `${jar}`.
            Start the application under it with `java -javaagent:${jar} -jar <app jar>`, or, when the start command is not yours to change, with `JAVA_TOOL_OPTIONS="-javaagent:${jar}" ./gradlew bootRun` (or `./gradlew run`).
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
            + "check — is in the skill at `.claude/skills/spider-sense/SKILL.md`.\n";

    private static final String SKILL_ELSEWHERE = "The loop — start, mark, exercise, findings, fix, compare, "
            + "check — is in the skill under `skills/spider-sense/` of the Spider Sense repository, "
            + "<https://github.com/benelog/spider-sense>.\n";

    private Init() {
    }

    /** The block exactly as agent.md prints it, with the jar path filled in. */
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
            if (!withSkill) {
                out.println("skipped skill (--no-skill)");
                return Cli.OK;
            }
            Path target = dir.resolve(Paths.get(SKILL_TARGET));
            out.println("installed skill to " + target + " (" + installSkill(target) + " files)");
            return Cli.OK;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
     * Copies the packaged skill into the project, overwriting the files it owns and
     * leaving anything else in that directory alone.
     *
     * @return how many files were written
     */
    private static int installSkill(Path target) throws IOException {
        int written = 0;
        for (String name : index()) {
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
                written++;
            }
        }
        return written;
    }

    /** The relative paths of the packaged skill, as the build's {@code skillIndex} task listed them. */
    private static List<String> index() throws IOException {
        try (InputStream in = Init.class.getClassLoader().getResourceAsStream(SKILL_INDEX)) {
            if (in == null) {
                throw new IllegalStateException("this build carries no skill: " + SKILL_INDEX
                        + " is missing; --no-skill writes the CLAUDE.md block alone");
            }
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), UTF_8).split("\n")) {
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
    private static String jar(Options options) {
        String named = options.value("jar", null);
        String path = named == null || named.isBlank() ? System.getProperty(JAR_PROPERTY) : named;
        if (path == null || path.isBlank()) {
            return null;
        }
        return Paths.get(path.trim()).toAbsolutePath().normalize().toString();
    }
}
