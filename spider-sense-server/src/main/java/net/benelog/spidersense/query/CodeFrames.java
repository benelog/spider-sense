package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.benelog.spidersense.store.ExceptionChain;
import org.jspecify.annotations.Nullable;

/**
 * Where a finding's code is, as far as the stock OpenTelemetry agent knows.
 *
 * <p>The agent does not record where a span was started from, so a finding's
 * {@code code} comes from the two places it does record: the
 * {@code exception.stacktrace} of an error, and the {@code code.function} /
 * {@code code.namespace} attributes of the few instrumentations that set them
 * (findings.adoc#code). A database span slower than {@code slow.query.ms}, and the fifth
 * repeat of a statement within a trace, have a third and better one,
 * {@code code.stacktrace}, which Spider Sense's own OpenTelemetry extension
 * captures on the thread that ended the span (design.adoc#stack-capture).
 *
 * <p>A stack trace is mostly framework, and the frame an agent wants to open is
 * the application's. Two ways to find it: by default everything that is not one of
 * the known framework prefixes counts as application code, which needs no
 * configuration and is right more often than not; {@code spidersense.app.packages}
 * replaces that heuristic with an allowlist, which is exact.
 */
public final class CodeFrames {

    /** Innermost first, and never more than this: a finding is a line to open, not a dump. */
    public static final int MAX_FRAMES = 5;

    /** What is framework rather than application when no allowlist is given. */
    public static final List<String> FRAMEWORK_PREFIXES = ExceptionChain.FRAMEWORK_PREFIXES;

    private final List<String> appPackages;

    /** @param appPackages the comma-separated {@code spidersense.app.packages}; empty means unset */
    public CodeFrames(@Nullable String appPackages) {
        List<String> prefixes = new ArrayList<>();
        if (appPackages != null) {
            for (String each : appPackages.split(",", -1)) {
                String prefix = each.trim();
                if (!prefix.isEmpty()) {
                    prefixes.add(prefix.endsWith(".") ? prefix : prefix + ".");
                }
            }
        }
        this.appPackages = List.copyOf(prefixes);
    }

    /**
     * The allowlist, each prefix ending in a dot; empty when the framework prefixes decide.
     * {@code /api/status.codeFrames} carries it so the UI folds a stack trace by these rules.
     */
    public List<String> appPackages() {
        return appPackages;
    }

    /**
     * The application frames of a stack trace, at most {@value #MAX_FRAMES}: the
     * root cause's first, then those of each exception wrapping it out to the outer
     * one, each cause's top first ({@link ExceptionChain#framesInnermostFirst()}).
     */
    public List<String> ofStacktrace(@Nullable String stacktrace) {
        Set<String> frames = new LinkedHashSet<>();
        for (String frame : ExceptionChain.parse(stacktrace).framesInnermostFirst()) {
            if (isApplication(frame)) {
                frames.add(frame);
                if (frames.size() >= MAX_FRAMES) {
                    break;
                }
            }
        }
        return List.copyOf(frames);
    }

    /**
     * The frames a span's own attributes name.
     *
     * <p>Two kinds, best first. {@code code.stacktrace} is a real stack trace, set
     * by Spider Sense's own OpenTelemetry extension on a database span that ran
     * past {@code slow.query.ms} and on the fifth repeat of a statement within a
     * trace (design.adoc#stack-capture), and is reduced exactly like an
     * {@code exception.stacktrace}. Failing that, the {@code code.function} /
     * {@code code.namespace} pair a few instrumentations set: no file and no line
     * in those, so the frame is {@code namespace.function}, still enough to open
     * the right class.
     */
    public List<String> ofAttributes(@Nullable Map<String, Object> attributes) {
        if (attributes == null) {
            return List.of();
        }
        Object stacktrace = attributes.get("code.stacktrace");
        if (stacktrace != null) {
            List<String> frames = ofStacktrace(String.valueOf(stacktrace));
            if (!frames.isEmpty()) {
                return frames;
            }
        }
        Object function = attributes.get("code.function");
        if (function == null) {
            return List.of();
        }
        Object namespace = attributes.get("code.namespace");
        String frame = namespace == null ? String.valueOf(function)
                : namespace + "." + function;
        return isApplication(frame) ? List.of(frame) : List.of();
    }

    /** The stack trace's frames, and the span attributes' frame when the trace gave none. */
    public List<String> of(@Nullable String stacktrace, @Nullable Map<String, Object> attributes) {
        List<String> frames = ofStacktrace(stacktrace);
        if (!frames.isEmpty()) {
            return frames;
        }
        return ofAttributes(attributes);
    }

    /** Whether a frame is the application's: the allowlist when there is one, else not a framework. */
    public boolean isApplication(String frame) {
        if (!appPackages.isEmpty()) {
            for (String prefix : appPackages) {
                if (frame.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        return ExceptionChain.notFramework(frame);
    }
}
