package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A stack trace read as the chain of exceptions it prints, innermost first.
 *
 * <p>{@code Throwable.printStackTrace} writes the outer exception, its frames,
 * then a {@code Caused by:} section per cause, each cut at the frames it shares
 * with the one before ({@code ... 42 more}). The line that went wrong is in the
 * last section, the root cause, so the chain is kept innermost first: the error
 * group key (design.adoc#errors), a finding's {@code code} (findings.adoc#code) and the error page
 * (pages.adoc#stack-traces) all read it in that order.
 *
 * <p>{@code Suppressed:} blocks are not causes and are skipped, with everything
 * indented under them. A trace with no header at all, such as the
 * {@code code.stacktrace} the extension writes, is one section with an empty
 * type.
 *
 * @param causes innermost first: the root cause, then each exception that wraps it
 */
public record ExceptionChain(List<Cause> causes) {

    /**
     * What is framework rather than application when no allowlist is given (findings.adoc#code).
     *
     * <p>A JDBC driver, a data-access library, a JSON mapper or a client throws from frames of its
     * own, so each must be here: otherwise its throwing frame is the innermost "application" frame
     * of every failure that passes through it, and one group swallows them all (design.adoc#errors).
     */
    public static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta.", "org.springframework.",
            "org.hibernate.", "org.eclipse.jetty.", "org.apache.", "io.opentelemetry.", "com.zaxxer.",
            "org.h2.", "net.benelog.spidersilk.", "kotlin.", "scala.", "reactor.", "io.netty.",
            "ch.qos.logback.", "org.slf4j.", "org.junit.", "gg.jte.",
            "org.postgresql.", "com.mysql.", "org.mariadb.", "oracle.", "com.microsoft.sqlserver.",
            "org.sqlite.", "org.hsqldb.", "org.jooq.", "org.mybatis.", "com.mongodb.", "io.lettuce.",
            "redis.clients.", "com.fasterxml.", "com.google.", "kotlinx.", "okhttp3.", "feign.",
            "io.grpc.", "org.jboss.");

    /**
     * What a frame of a generated class holds, whatever its package (findings.adoc#code): a
     * Hibernate proxy, a Spring CGLIB proxy, a ByteBuddy class, or a method whose position the JVM
     * writes as {@code <generated>}. Such a class sits in the application's own package, but its
     * frame is never the line that issued a statement or threw.
     */
    public static final List<String> GENERATED_MARKERS = List.of(
            "$HibernateProxy", "$$SpringCGLIB$$", "$$EnhancerBySpringCGLIB$$", "$$FastClassBySpringCGLIB$$",
            "$ByteBuddy$", "(<generated>)");

    /**
     * One exception of the chain.
     *
     * @param type    the exception class, empty when the section had no header
     * @param message the message, empty when there was none
     * @param frames  its frames as printed, top first, each {@code package.Class.method(File.java:41)}
     * @param more    the frames it shares with the exception that wraps it, left out as {@code ... n more}
     */
    public record Cause(String type, String message, List<String> frames, int more) {
    }

    private static final ExceptionChain EMPTY = new ExceptionChain(List.of());
    private static final String CAUSED_BY = "Caused by:";
    private static final String SUPPRESSED = "Suppressed:";
    private static final Pattern MORE = Pattern.compile("^\\.\\.\\. (\\d+) (?:more|common frames omitted)");
    /** The {@code /0x…} a JVM writes after the name of a hidden class, such as a lambda's. */
    private static final Pattern HIDDEN_CLASS_ADDRESS = Pattern.compile("/0x[0-9a-fA-F]+(?=\\.)");

    public ExceptionChain {
        causes = List.copyOf(causes);
    }

    /** The chain of a stack trace; empty for {@code null} or blank. */
    public static ExceptionChain parse(@Nullable String stacktrace) {
        if (stacktrace == null || stacktrace.isBlank()) {
            return EMPTY;
        }
        List<Section> sections = new ArrayList<>();
        Section current = null;
        int suppressedIndent = -1;
        for (String raw : stacktrace.split("\\R", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            int indent = indent(raw);
            if (suppressedIndent >= 0) {
                if (indent > suppressedIndent) {
                    continue;
                }
                suppressedIndent = -1;
            }
            String line = raw.trim();
            if (line.startsWith(SUPPRESSED)) {
                suppressedIndent = indent;
            } else if (line.startsWith(CAUSED_BY)) {
                current = new Section(line.substring(CAUSED_BY.length()).trim());
                sections.add(current);
            } else if (line.startsWith("at ")) {
                if (current == null) {
                    current = new Section("");
                    sections.add(current);
                }
                current.frames.add(frame(line.substring(3).trim()));
            } else if (current == null) {
                current = new Section(line);
                sections.add(current);
            } else {
                Matcher more = MORE.matcher(line);
                if (more.find()) {
                    current.more = Integer.parseInt(more.group(1));
                } else if (current.frames.isEmpty()) {
                    // A message that spans lines: everything before the first frame.
                    current.header.append('\n').append(line);
                }
            }
        }
        List<Cause> causes = new ArrayList<>(sections.size());
        for (Section section : sections) {
            causes.add(section.cause());
        }
        Collections.reverse(causes);
        return new ExceptionChain(causes);
    }

    /** The root cause's type, else {@code null} when the chain is empty or has no header. */
    public @Nullable String rootType() {
        if (causes.isEmpty() || causes.get(0).type().isEmpty()) {
            return null;
        }
        return causes.get(0).type();
    }

    /**
     * The frames of the chain in the order a reader wants them: the root cause's,
     * then the exception wrapping it, and so on out to the outer exception, each
     * cause's top first.
     */
    public List<String> framesInnermostFirst() {
        List<String> frames = new ArrayList<>();
        for (Cause cause : causes) {
            frames.addAll(cause.frames());
        }
        return frames;
    }

    /** The first frame {@link #framesInnermostFirst()} yields that passes {@code application}. */
    public @Nullable String innermost(Predicate<String> application) {
        for (String frame : framesInnermostFirst()) {
            if (application.test(frame)) {
                return frame;
            }
        }
        return null;
    }

    /** Whether a frame is none of the {@link #FRAMEWORK_PREFIXES} and no {@link #generated} class's. */
    public static boolean notFramework(String frame) {
        if (generated(frame)) {
            return false;
        }
        String lower = frame.toLowerCase(Locale.ROOT);
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a frame is a generated class's: it holds one of the {@link #GENERATED_MARKERS}. */
    public static boolean generated(String frame) {
        for (String marker : GENERATED_MARKERS) {
            if (frame.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A frame as {@code package.Class.method(File.java:41)}.
     *
     * <p>A JVM writes the module or class loader in front of the class
     * ({@code java.base/java.util.List.of(...)}, {@code app//com.acme.Orders.load(...)}),
     * and a logging framework adds {@code ~[jar:version]} behind it. Neither is part
     * of the location a person opens. A hidden class, such as a lambda's, keeps its name and
     * loses the address behind it: {@code com.acme.Foo$$Lambda/0x00007f0a1c0f3b28.run(Unknown Source)}
     * is {@code com.acme.Foo$$Lambda.run(Unknown Source)}, which a framework prefix still matches.
     */
    public static String frame(String frame) {
        String value = frame;
        int marker = value.indexOf('~');
        if (marker > 0) {
            value = value.substring(0, marker).trim();
        }
        int parenthesis = value.indexOf('(');
        int limit = parenthesis < 0 ? value.length() : parenthesis;
        String name = HIDDEN_CLASS_ADDRESS.matcher(value.substring(0, limit)).replaceAll("");
        int slash = name.lastIndexOf('/');
        return (slash >= 0 ? name.substring(slash + 1) : name) + value.substring(limit);
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static final class Section {
        final StringBuilder header;
        final List<String> frames = new ArrayList<>();
        int more;

        Section(String header) {
            this.header = new StringBuilder(header);
        }

        Cause cause() {
            String text = header.toString();
            int colon = text.indexOf(": ");
            String type = colon < 0 ? text : text.substring(0, colon);
            String message = colon < 0 ? "" : text.substring(colon + 2);
            if (type.isEmpty() || type.chars().anyMatch(Character::isWhitespace)) {
                // Not a class name: a header this reader does not know, kept as the message.
                return new Cause("", text, frames, more);
            }
            return new Cause(type, message, frames, more);
        }
    }
}
