package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.benelog.spidersense.source.SourceRoots;
import org.jspecify.annotations.Nullable;

/**
 * The suspect change under each code frame of {@code findings}: whether the line a
 * finding points at is in the change the agent just made (cli.adoc#suspect-change).
 *
 * <p>This is the one piece of a findings answer the CLI adds itself rather than
 * printing what the renderer produced, and it has to be: the server may run in
 * another directory, or not at all, while the CLI runs in the project, where the
 * repository is. It reads nothing of the store; it takes the rendered text, finds
 * the code frame lines in it, resolves each under {@code spidersense.source.dirs}
 * as the server does, and asks {@code git} about the file and the line.
 *
 * <p>Everything here is best effort. A directory that is not a repository, a
 * {@code git} that is not installed or does not answer, a frame that does not
 * resolve: each leaves the text as it was.
 */
final class SuspectChange {

    /** A code frame line of the findings text: three spaces, then the frame, then nothing. */
    private static final Pattern FRAME_LINE = Pattern.compile("   (\\S+\\(\\S+:\\d+\\))");

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    /** How long stdout may take to drain once {@code git} has exited, however late that was. */
    private static final long DRAIN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final SourceRoots roots;
    private final Path repository;
    private final Set<String> uncommitted;
    private final long now;
    private final Map<String, @Nullable String> blamed = new HashMap<>();

    private SuspectChange(SourceRoots roots, Path repository, Set<String> uncommitted, long now) {
        this.roots = roots;
        this.repository = repository;
        this.uncommitted = uncommitted;
        this.now = now;
    }

    /**
     * The annotator for a working directory, or null when it is not inside a repository or
     * {@code git} cannot be run there.
     */
    static @Nullable SuspectChange in(Path workingDir, SourceRoots roots, long now) {
        List<String> top = git(workingDir, "rev-parse", "--show-toplevel");
        if (top == null || top.isEmpty() || top.get(0).isBlank()) {
            return null;
        }
        Path repository;
        try {
            repository = Path.of(top.get(0).trim()).toRealPath();
        } catch (IOException e) {
            return null;
        }
        Set<String> uncommitted = new HashSet<>();
        for (List<String> args : List.of(
                List.of("diff", "--name-only"),
                List.of("diff", "--cached", "--name-only"),
                List.of("ls-files", "--others", "--exclude-standard"))) {
            List<String> names = git(repository, args.toArray(String[]::new));
            if (names == null) {
                return null;
            }
            for (String name : names) {
                if (!name.isBlank()) {
                    uncommitted.add(name.trim());
                }
            }
        }
        return new SuspectChange(roots, repository, uncommitted, now);
    }

    /** The text with one line under each code frame that resolves to a file of the repository. */
    String annotate(String text) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        int from = 0;
        while (from < text.length()) {
            int end = text.indexOf('\n', from);
            String line = end < 0 ? text.substring(from) : text.substring(from, end);
            out.append(line);
            if (end >= 0) {
                out.append('\n');
            }
            Matcher m = FRAME_LINE.matcher(line);
            if (m.matches()) {
                String note = noteFor(m.group(1));
                if (note != null) {
                    if (end < 0) {
                        out.append('\n');
                    }
                    out.append("     ").append(note).append('\n');
                }
            }
            from = end < 0 ? text.length() : end + 1;
        }
        return out.toString();
    }

    /** {@code uncommitted}, or {@code changed in <hash> (<age>): <subject>}; null when git says nothing. */
    @Nullable String noteFor(String frame) {
        if (blamed.containsKey(frame)) {
            return blamed.get(frame);
        }
        String note = null;
        SourceRoots.Location location = roots.resolve(frame);
        if (location != null && location.file().startsWith(repository)) {
            String relative = repository.relativize(location.file()).toString()
                    .replace(java.io.File.separatorChar, '/');
            note = uncommitted.contains(relative) ? "uncommitted" : blame(relative, location.line());
        }
        blamed.put(frame, note);
        return note;
    }

    private @Nullable String blame(String relative, int line) {
        List<String> porcelain = git(repository, "blame", "-L", line + "," + line, "--porcelain",
                "--", relative);
        if (porcelain == null || porcelain.isEmpty()) {
            return null;
        }
        String first = porcelain.get(0);
        int space = first.indexOf(' ');
        String hash = space < 0 ? first : first.substring(0, space);
        if (hash.length() < 7) {
            return null;
        }
        if (hash.chars().allMatch(c -> c == '0')) {
            // The line differs from every commit: an edit git diff would also have named.
            return "uncommitted";
        }
        Long authorTime = null;
        String summary = "";
        for (String each : porcelain) {
            if (each.startsWith("author-time ")) {
                try {
                    authorTime = Long.parseLong(each.substring("author-time ".length()).trim());
                } catch (NumberFormatException e) {
                    authorTime = null;
                }
            } else if (each.startsWith("summary ")) {
                summary = each.substring("summary ".length()).trim();
            }
        }
        StringBuilder note = new StringBuilder("changed in ").append(hash, 0, 7);
        if (authorTime != null) {
            note.append(" (").append(age(now - authorTime * 1000)).append(')');
        }
        return note.append(": ").append(summary).toString();
    }

    /**
     * {@code 2 hours ago}, in the manner of {@code git log --format=%ar}: seconds under a
     * minute, minutes under an hour, hours under a day, days under two weeks, weeks under two
     * months, months under a year, then years; the plural when the number is not one.
     */
    static String age(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long[] limits = {60, 60 * 60, 24 * 60 * 60, 14L * 24 * 60 * 60, 60L * 24 * 60 * 60,
                365L * 24 * 60 * 60};
        String[] units = {"second", "minute", "hour", "day", "week", "month", "year"};
        long[] sizes = {1, 60, 60 * 60, 24 * 60 * 60, 7L * 24 * 60 * 60, 30L * 24 * 60 * 60,
                365L * 24 * 60 * 60};
        int unit = 0;
        while (unit < limits.length && seconds >= limits[unit]) {
            unit++;
        }
        long n = seconds / sizes[unit];
        return n + " " + units[unit] + (n == 1 ? "" : "s") + " ago";
    }

    /** One {@code git} command's stdout as lines; null when it could not run or did not succeed. */
    private static @Nullable List<String> git(Path dir, String... args) {
        return git(GIT_TIMEOUT, dir, args);
    }

    /**
     * {@link #git(Path, String...)} with the time it may take: null, and the
     * process and everything it started killed, once that has passed.
     *
     * <p>Stdout is read on a thread of its own, so the wait is what has the
     * deadline. Read here, it would be the read that waited, for as long as a
     * {@code git blame} on a stale mount takes, and the deadline would come only
     * after it.
     */
    static @Nullable List<String> git(Duration timeout, Path dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        // git quotes a path with non-ASCII letters ("src/\303\251t\303\251.java") unless told
        // not to, and a quoted name never equals the frame's file.
        command.add("-c");
        command.add("core.quotePath=false");
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        FutureTask<byte[]> stdout = new FutureTask<>(() -> {
            try (InputStream in = process.getInputStream()) {
                return in.readAllBytes();
            }
        });
        Thread reader = new Thread(stdout, "spider-sense-git");
        // A child git left behind can hold stdout open after the deadline; it must
        // not keep the CLI from exiting.
        reader.setDaemon(true);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process.getOutputStream().close();
            reader.start();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                destroyTree(process);
                return null;
            }
            // git has exited, so what is left in the pipe drains at once, unless a
            // child of git still holds it open.
            long left = Math.max(deadline - System.nanoTime(), DRAIN_NANOS);
            byte[] bytes = stdout.get(left, TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException | ExecutionException | TimeoutException e) {
            destroyTree(process);
            return null;
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Kills {@code git} and what it started: {@code git blame} through an alias or a
     * textconv filter runs a shell, and killing only {@code git} would leave that
     * running. The descendants are listed first, while they are still its.
     */
    private static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }
}
