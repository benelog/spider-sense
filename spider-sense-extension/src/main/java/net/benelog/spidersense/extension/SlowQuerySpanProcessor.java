package net.benelog.spidersense.extension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import java.util.concurrent.TimeUnit;

/**
 * Gives a slow database span the stack it was issued from, as {@code code.stacktrace}.
 *
 * <p>The stock OpenTelemetry agent records where an exception was thrown and nothing about where a
 * query came from, so a {@code slow-query} or {@code n-plus-one} finding could name a statement but
 * never a line ({@code docs/agent.md}). This is the one thing Spider Sense collects itself.
 *
 * <p>The work happens in {@link #onEnding(ReadWriteSpan)}, which the SDK calls on the thread that is
 * ending the span while the span is still writable: the duration is already known, so the threshold
 * can be applied, and an attribute can still be set. {@code onStart} and {@code onEnd} are not
 * wanted, and neither is an exception: a missing code location is never worth a broken span.
 */
public final class SlowQuerySpanProcessor implements ExtendedSpanProcessor {

    /** Where the frames go; read back by the server's {@code CodeFrames}. */
    static final AttributeKey<String> CODE_STACKTRACE = AttributeKey.stringKey("code.stacktrace");

    /** The older and the stable spelling; either one makes a span a database span. */
    static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
    static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

    /** The same threshold the server calls a tingle, so what is captured is what gets reported. */
    static final String THRESHOLD_PROPERTY = "spidersense.slow.query.ms";
    static final String THRESHOLD_ENV = "SPIDERSENSE_SLOW_QUERY_MS";
    static final long DEFAULT_THRESHOLD_MS = 100;

    /** A finding wants a line to open, not a core dump. */
    static final int MAX_FRAMES = 64;

    private final long thresholdNanos;

    /** The configured threshold, read once: this runs on every database span that ends. */
    public SlowQuerySpanProcessor() {
        this(configuredThresholdMillis());
    }

    SlowQuerySpanProcessor(long thresholdMillis) {
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, thresholdMillis));
    }

    static long configuredThresholdMillis() {
        try {
            String value = System.getProperty(THRESHOLD_PROPERTY);
            if (value == null || value.isBlank()) {
                value = System.getenv(THRESHOLD_ENV);
            }
            return value == null || value.isBlank() ? DEFAULT_THRESHOLD_MS : Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return DEFAULT_THRESHOLD_MS;
        }
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        try {
            if (span.getLatencyNanos() < thresholdNanos) {
                return;
            }
            if (span.getAttribute(DB_SYSTEM) == null && span.getAttribute(DB_SYSTEM_NAME) == null) {
                return;
            }
            if (span.getAttribute(CODE_STACKTRACE) != null) {
                return;
            }
            String stacktrace = format(Thread.currentThread().getStackTrace());
            if (!stacktrace.isEmpty()) {
                span.setAttribute(CODE_STACKTRACE, stacktrace);
            }
        } catch (Throwable swallowed) {
            // Documented: nothing this processor does may reach the application.
        }
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Nothing to do at the start.
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // By then the span is immutable; onEnding is where the attribute can still be set.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    /**
     * The frames as {@code Throwable.printStackTrace} writes them, one {@code \tat …} line each and
     * no header, so the server parses them with the code it already has for
     * {@code exception.stacktrace}.
     *
     * <p>The leading frames are ours and the SDK's — {@code Thread.getStackTrace}, this class, and
     * the {@code io.opentelemetry.} call chain down from {@code end()} — and say nothing about the
     * query; they are dropped until the first frame that is neither, which is the JDBC driver the
     * instrumentation advice sits in. Dropping only leading frames keeps a later application frame
     * that happens to live in one of those packages.
     */
    static String format(StackTraceElement[] frames) {
        StringBuilder out = new StringBuilder();
        int written = 0;
        boolean started = false;
        for (StackTraceElement frame : frames) {
            if (!started) {
                if (isPlumbing(frame)) {
                    continue;
                }
                started = true;
            }
            out.append("\tat ").append(frame).append('\n');
            if (++written >= MAX_FRAMES) {
                break;
            }
        }
        return out.toString();
    }

    private static final String OWN_CLASS = SlowQuerySpanProcessor.class.getName();

    private static boolean isPlumbing(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.startsWith("io.opentelemetry.")
                || className.equals(OWN_CLASS)
                || ("java.lang.Thread".equals(className) && "getStackTrace".equals(frame.getMethodName()));
    }
}
