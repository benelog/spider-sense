package net.benelog.spidersense.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.benelog.spidersense.server.Config;
import org.jspecify.annotations.Nullable;

/**
 * Where a code frame's source file is on this machine, and the lines around its line.
 *
 * <p>Spider Sense runs where the source is, so a frame
 * {@code orders.OrderService.load(OrderService.java:41)} is a file read away from
 * the line it names: the package as directories, the file name the frame carries,
 * under the first root of {@code spidersense.source.dirs} where that file exists
 * (configuration.adoc#source-dirs; findings.adoc#code).
 *
 * <p>The frame arrives in a query parameter, so it is a path a stranger chose.
 * Three things keep it inside the roots: the frame must parse as Java identifiers
 * separated by dots and a file name with a source extension, so no {@code ..} and
 * no {@code /} survive the parse; the resolved path must still start with its root
 * after {@link Path#normalize()}; and its real path, symbolic links followed, must
 * start with the root's real path.
 */
public final class SourceRoots {

    /** The lines either side of the frame's line: five lines in all. */
    public static final int CONTEXT = 2;

    /** Longer than any frame a stack trace carries, and short enough to refuse at once. */
    private static final int MAX_FRAME = 1000;

    /**
     * A character that may start a Java identifier: any letter, a letter-like number,
     * {@code _} or {@code $}, so {@code 주문Service} and {@code Ünïcode} are identifiers too.
     */
    private static final String START = "[\\p{L}\\p{Nl}_$]";

    /**
     * A character that may continue one: those, a digit, a combining mark or a connector.
     * Not {@code \w}, which is ASCII in a Java pattern, and not
     * {@code \p{javaJavaIdentifierPart}}, which admits control characters no path should hold.
     */
    private static final String PART = "[\\p{L}\\p{Nl}\\p{Nd}\\p{Mn}\\p{Mc}\\p{Pc}$]";

    /** A character of a file name: those and {@code -}, never a dot or a slash. */
    private static final String FILE_PART = "[\\p{L}\\p{Nl}\\p{Nd}\\p{Mn}\\p{Mc}\\p{Pc}$-]";

    /**
     * {@code package.Class.method(File.ext:line)}: the class a dotted run of identifiers
     * ({@code $} for a nested class), the method anything but a dot or a parenthesis
     * ({@code <init>}, {@code lambda$load$0}), the file a plain name with a source extension.
     */
    private static final Pattern FRAME = Pattern.compile(
            "(" + START + PART + "*(?:\\." + START + PART + "*)*)\\.[^.()\\s]+"
                    + "\\((" + FILE_PART + "+\\.(?:java|kt|groovy|scala)):([0-9]{1,9})\\)");

    private final List<Path> roots;

    private SourceRoots(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /**
     * The roots {@code spidersense.source.dirs} names, relative ones against {@code workingDir},
     * or the default when it is null: {@code src/main/java} and {@code src/main/kotlin} of the
     * working directory and of each of its immediate subdirectories. An empty value names no
     * root, which turns source lines and editor links off. A root that is not a directory is
     * left out.
     */
    public static SourceRoots of(@Nullable String configured, Path workingDir) {
        Path base = workingDir.toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        if (configured == null) {
            candidates.addAll(conventional(base));
            for (Path child : children(base)) {
                candidates.addAll(conventional(child));
            }
        } else {
            for (String each : configured.split(",", -1)) {
                String dir = each.trim();
                if (!dir.isEmpty()) {
                    candidates.add(base.resolve(Config.expandHome(dir)));
                }
            }
        }
        List<Path> roots = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                if (Files.isDirectory(candidate)) {
                    Path real = candidate.toRealPath();
                    if (!roots.contains(real)) {
                        roots.add(real);
                    }
                }
            } catch (IOException e) {
                // A root that cannot be read is no root; the others still answer.
            }
        }
        return new SourceRoots(roots);
    }

    /** {@code spidersense.source.dirs} of this JVM against its working directory. */
    public static SourceRoots fromSystemProperties() {
        return of(Config.setting("spidersense.source.dirs"), Path.of(""));
    }

    /** The roots that exist, in the order they are tried. */
    public List<Path> roots() {
        return roots;
    }

    /** A frame's path under a root and its line, as parsed; null when it is not a frame with a file. */
    public static @Nullable Frame parse(@Nullable String frame) {
        if (frame == null || frame.length() > MAX_FRAME) {
            return null;
        }
        Matcher m = FRAME.matcher(frame.trim());
        if (!m.matches()) {
            return null;
        }
        String type = m.group(1);
        int dot = type.lastIndexOf('.');
        String packagePath = dot < 0 ? "" : type.substring(0, dot).replace('.', '/') + "/";
        int line;
        try {
            line = Integer.parseInt(m.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
        if (line < 1) {
            return null;
        }
        return new Frame(packagePath + m.group(2), line);
    }

    /** The file a frame names, in the first root where it exists; null when none has it. */
    public @Nullable Location resolve(@Nullable String frame) {
        Frame parsed = parse(frame);
        if (parsed == null) {
            return null;
        }
        for (Path root : roots) {
            Path candidate = root.resolve(parsed.path()).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                Path real = candidate.toRealPath();
                if (real.startsWith(root)) {
                    return new Location(real, parsed.line());
                }
            } catch (IOException e) {
                // Gone between the check and the read, or unreadable: try the next root.
            }
        }
        return null;
    }

    /**
     * The five lines around a frame's line, read now and kept nowhere; null when the frame
     * does not resolve. Lines outside the file are left out, so a file edited since the frame
     * was recorded may answer fewer lines, or none.
     */
    public @Nullable Snippet read(@Nullable String frame) {
        Location location = resolve(frame);
        if (location == null) {
            return null;
        }
        int first = Math.max(1, location.line() - CONTEXT);
        int last = location.line() + CONTEXT;
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(location.file(), StandardCharsets.UTF_8)) {
            int n = 0;
            String text;
            while ((text = reader.readLine()) != null && n < last) {
                n++;
                if (n >= first) {
                    lines.add(text);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // Not UTF-8 or not readable: the location still answers, without lines.
            lines.clear();
        }
        return new Snippet(location.file(), location.line(), first, List.copyOf(lines));
    }

    private static List<Path> conventional(Path dir) {
        return List.of(dir.resolve("src/main/java"), dir.resolve("src/main/kotlin"));
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** A frame's file relative to a root ({@code orders/OrderService.java}) and its line. */
    public record Frame(String path, int line) {
    }

    /** The absolute path of a resolved frame's file and the frame's line. */
    public record Location(Path file, int line) {
    }

    /** A resolved frame's file, its line, and the lines around it; {@code start} is the first one's number. */
    public record Snippet(Path file, int line, int start, List<String> lines) {
    }
}
