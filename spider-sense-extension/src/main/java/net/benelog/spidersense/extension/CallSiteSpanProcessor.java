package net.benelog.spidersense.extension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import net.benelog.spidersense.extension.schema.Thresholds;
import org.jspecify.annotations.Nullable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Gives a slow database span, the fifth repeat of a statement within a trace, a slow outbound call
 * and the fifth repeat of an HTTP call the stack it was issued from, as {@code code.stacktrace}.
 *
 * <p>The stock OpenTelemetry agent records where an exception was thrown and nothing about where a
 * query came from, so a {@code slow-query} or {@code n-plus-one} finding could name a statement but
 * never a line ({@code findings.adoc#code}). This is the one thing Spider Sense collects itself.
 *
 * <p>The work is split between two callbacks, and {@code onEnd} is not wanted, because by then the
 * span is immutable. The thresholds are applied in {@link #onEnding(ReadWriteSpan)}, which the SDK
 * calls on the thread that is ending the span while the span is still writable: the duration is
 * already known, and an attribute can still be set. The repeats are counted and captured in
 * {@link #onStart(Context, ReadWriteSpan)}, while the thread is still the one that made the call.
 * Neither callback lets an exception out: a missing code location is never worth a broken span.
 *
 * <p>The individual queries of an N+1 are fast, so the threshold would never fire on them. For
 * those, the processor counts per trace how many database spans of it have started with the same
 * statement, and captures the stack once, on the fifth repeat: the same number that makes a query
 * group an N+1 on the server ({@code design.adoc#extension}).
 *
 * <p>The third case is a slow outbound call: a {@code CLIENT} span that is not a database span and
 * took at least {@code spidersense.slow.request.ms}, so a {@code slow-external} finding names the
 * line that made the call rather than only the host it went to.
 *
 * <p>The fourth is that case's N+1, counted exactly as the statements are: an outbound HTTP call is
 * counted per trace under its name and its URL with the digits replaced, and the fifth repeat gets
 * the stack, so an {@code n-plus-one-http} finding names the loop rather than only the host.
 */
public final class CallSiteSpanProcessor implements ExtendedSpanProcessor {

    /** Where the frames go; read back by the server's {@code CodeFrames}. */
    static final AttributeKey<String> CODE_STACKTRACE = AttributeKey.stringKey("code.stacktrace");

    /** The older and the stable spelling; either one makes a span a database span. */
    static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
    static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

    /** What makes two database spans the same statement; the span name is the last resort. */
    static final AttributeKey<String> DB_QUERY_TEXT = AttributeKey.stringKey("db.query.text");
    static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");

    /** What makes an outbound span an HTTP call, and what says where it went. */
    static final AttributeKey<String> HTTP_REQUEST_METHOD =
            AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.method");
    static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");

    /** The digits a loop varies; the server's {@code Ids.normaliseDigits}, repeated here. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** The repeat that gets the stack: the server's {@code Findings.REPEATS}. */
    static final int N_PLUS_ONE_REPEATS = 5;

    /** How many distinct statements and calls of one trace are counted before it gives up. */
    static final int MAX_KEYS_PER_TRACE = 256;

    /**
     * How many traces are counted at once, so a map keyed by trace id cannot grow without bound.
     *
     * <p>A trace is normally forgotten when its local root ends; this is the guard for the one that
     * never does, which is a crash or a leak in the monitored application.
     */
    static final int MAX_TRACES = 1_000;

    /** A finding wants a line to open, not a core dump. */
    static final int MAX_FRAMES = 64;

    private final long thresholdNanos;
    private final long requestThresholdNanos;

    /**
     * How often each statement and call of a trace has ended, by trace id.
     *
     * <p>Per trace and not per thread, which is what this was. A counter on the thread is cheaper
     * and needs no lifecycle, and it is also wrong for anything asynchronous: a client that
     * completes its exchange off the calling thread ends every span of a run on a different worker,
     * each of them counts one repeat, and none reaches five. That is most HTTP clients, so a
     * thread-local counter gave an {@code n-plus-one-http} finding no code location at all in the
     * common case ({@code design.adoc#extension}).
     *
     * <p>Not static: the counter belongs to this processor, so a second processor, which is what a
     * test builds, starts from nothing rather than inheriting another one's counts.
     */
    private final ConcurrentMap<String, ConcurrentMap<String, Integer>> repeats =
            new ConcurrentHashMap<>();

    /**
     * The configured thresholds, the ones the server reports at ({@link Thresholds}), read once:
     * this runs on every span that ends.
     */
    public CallSiteSpanProcessor() {
        this(Thresholds.slowQueryMillis(), Thresholds.slowRequestMillis());
    }

    CallSiteSpanProcessor(long thresholdMillis) {
        this(thresholdMillis, Thresholds.DEFAULT_SLOW_REQUEST_MS);
    }

    CallSiteSpanProcessor(long thresholdMillis, long requestThresholdMillis) {
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, thresholdMillis));
        this.requestThresholdNanos =
                TimeUnit.MILLISECONDS.toNanos(Math.max(0, requestThresholdMillis));
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        try {
            if (isLocalRoot(span)) {
                repeats.remove(span.getSpanContext().getTraceId());
            }
            // The repeats are counted and captured at the start (onStart); here only the
            // thresholds, which need a duration and can therefore only be read at the end.
            long threshold = isDatabase(span) ? thresholdNanos
                    : span.getKind() == SpanKind.CLIENT ? requestThresholdNanos : Long.MAX_VALUE;
            if (span.getLatencyNanos() >= threshold) {
                capture(span);
            }
        } catch (Throwable swallowed) {
            // Documented: nothing this processor does may reach the application.
        }
    }

    /**
     * The repeat, counted and captured where the call was made.
     *
     * <p>This is the half of the work that cannot wait for the end of the span. The stack at the
     * end is the stack of whichever thread ends it, and for an asynchronous client that is the
     * completion callback of a {@code CompletableFuture} on a worker: every frame of it belongs to
     * the JDK and the finding names no line. At the start the thread is still the one that made the
     * call, so the stack is the call site ({@code design.adoc#extension}).
     *
     * <p>The span already carries the attributes the instrumentation sets on the request, which is
     * what the statement and the URL are, so the key is the same one the end would have computed.
     */
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        try {
            String key = repeatKey(span);
            if (key != null && count(span, key) == N_PLUS_ONE_REPEATS) {
                capture(span);
            }
        } catch (Throwable swallowed) {
            // Documented: nothing this processor does may reach the application.
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    /** What a span's repeats are counted under, or null when it is neither a statement nor a call. */
    private static @Nullable String repeatKey(ReadWriteSpan span) {
        if (isDatabase(span)) {
            return statementOf(span);
        }
        return span.getKind() == SpanKind.CLIENT && isHttp(span) ? callOf(span) : null;
    }

    private static boolean isDatabase(ReadWriteSpan span) {
        return span.getAttribute(DB_SYSTEM) != null || span.getAttribute(DB_SYSTEM_NAME) != null;
    }

    /** The stack of the thread the span is on, unless the span already carries one. */
    private static void capture(ReadWriteSpan span) {
        if (span.getAttribute(CODE_STACKTRACE) != null) {
            return;
        }
        String stacktrace = stacktraceOf(Thread.currentThread().getStackTrace());
        if (!stacktrace.isEmpty()) {
            span.setAttribute(CODE_STACKTRACE, stacktrace);
        }
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    /**
     * How often this statement or call has now started in this trace.
     *
     * <p>Zero when it is not counted at all, which is what beyond {@link #MAX_KEYS_PER_TRACE} distinct
     * keys of one trace means: the counter stops adding keys and nothing else changes.
     */
    private int count(ReadWriteSpan span, String key) {
        ConcurrentMap<String, Integer> counts = countsOf(span.getSpanContext().getTraceId());
        if (!counts.containsKey(key) && counts.size() >= MAX_KEYS_PER_TRACE) {
            return 0;
        }
        // merge is atomic, so exactly one of several threads ending the repeats of one trace
        // sees the fifth and captures the stack.
        return counts.merge(key, 1, Integer::sum);
    }

    /** How many traces are being counted; a test's way of seeing that they are forgotten. */
    int tracesCounted() {
        return repeats.size();
    }

    /** The counts of one trace, made on first sight and kept until its local root ends. */
    private ConcurrentMap<String, Integer> countsOf(String traceId) {
        ConcurrentMap<String, Integer> counts = repeats.get(traceId);
        if (counts != null) {
            return counts;
        }
        ConcurrentMap<String, Integer> fresh = new ConcurrentHashMap<>();
        ConcurrentMap<String, Integer> raced = repeats.putIfAbsent(traceId, fresh);
        if (raced != null) {
            return raced;
        }
        // Bounded on insert rather than swept: an application that never ends a root span would
        // otherwise keep every trace it ever started. Which traces go is not defined, and under
        // that much load a missing code location is the least of it.
        while (repeats.size() > MAX_TRACES) {
            Iterator<String> oldest = repeats.keySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        return fresh;
    }

    /**
     * Whether this span is where the trace entered this process: a root span, or the entry span
     * under a caller that is traced too.
     *
     * <p>It ends after everything it started, so it is when this process's work on the trace is
     * done and the counts can go. A span of that trace that ends later simply starts the count
     * again, which is what a counter on the thread did when the thread moved on.
     */
    private static boolean isLocalRoot(ReadWriteSpan span) {
        SpanContext parent = span.getParentSpanContext();
        return !parent.isValid() || parent.isRemote();
    }

    /** Whether an outbound span is an HTTP call: the rule the server's {@code category()} uses. */
    private static boolean isHttp(ReadWriteSpan span) {
        return span.getAttribute(HTTP_REQUEST_METHOD) != null
                || span.getAttribute(HTTP_METHOD) != null
                || span.getAttribute(URL_FULL) != null;
    }

    /**
     * What two outbound calls have to share to be the same call: the span's name and its URL with
     * every run of digits replaced by {@code ?}.
     *
     * <p>The digits are what a loop varies, so replacing them is what makes three calls one call,
     * and the server groups the finding on the same rule ({@code findings.adoc#repeated-call}).
     * The key carries a prefix, so a URL is never counted as a statement of the same trace.
     */
    private static String callOf(ReadWriteSpan span) {
        String url = span.getAttribute(URL_FULL);
        return "http\0" + span.getName() + " "
                + (url == null ? "" : DIGITS.matcher(url).replaceAll("?"));
    }

    /** What two spans have to share to be the same statement: the query text, else the span name. */
    private static String statementOf(ReadWriteSpan span) {
        String statement = span.getAttribute(DB_QUERY_TEXT);
        if (statement == null) {
            statement = span.getAttribute(DB_STATEMENT);
        }
        return statement == null ? span.getName() : statement;
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
    static String stacktraceOf(StackTraceElement[] frames) {
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

    private static final String OWN_CLASS = CallSiteSpanProcessor.class.getName();

    private static boolean isPlumbing(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.startsWith("io.opentelemetry.")
                || className.equals(OWN_CLASS)
                || ("java.lang.Thread".equals(className) && "getStackTrace".equals(frame.getMethodName()));
    }
}
