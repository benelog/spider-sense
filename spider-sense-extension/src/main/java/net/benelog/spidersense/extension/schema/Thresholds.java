package net.benelog.spidersense.extension.schema;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The two thresholds the extension captures at, the ones the server reports at: what is captured
 * is what gets reported ({@code design.adoc#extension}).
 *
 * <p>One class for both of the extension's class loaders. The span processor reads it in the
 * extension's own loader, and {@link IndexCatalog} in the application's, where it is injected as a
 * helper beside the catalog; each loader has its own copy of the class, which is harmless, because
 * nothing here is state.
 */
public final class Thresholds {

    /** {@code slow-query}: a database span at least this long gets a stack and a catalog lookup. */
    public static final String SLOW_QUERY_PROPERTY = "spidersense.slow.query.ms";
    public static final long DEFAULT_SLOW_QUERY_MS = 100;

    /** {@code slow-request}, which an outbound call is measured against as well. */
    public static final String SLOW_REQUEST_PROPERTY = "spidersense.slow.request.ms";
    public static final long DEFAULT_SLOW_REQUEST_MS = 500;

    private Thresholds() {
    }

    /** The configured slow-query threshold, from this JVM's properties and environment. */
    public static long slowQueryMillis() {
        return slowQueryMillis(System::getProperty, System::getenv);
    }

    /** The same with the properties and the environment given. */
    public static long slowQueryMillis(Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        return Settings.millis(SLOW_QUERY_PROPERTY, DEFAULT_SLOW_QUERY_MS, property, env);
    }

    /** The configured slow-request threshold, from this JVM's properties and environment. */
    public static long slowRequestMillis() {
        return slowRequestMillis(System::getProperty, System::getenv);
    }

    /** The same with the properties and the environment given. */
    public static long slowRequestMillis(Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        return Settings.millis(SLOW_REQUEST_PROPERTY, DEFAULT_SLOW_REQUEST_MS, property, env);
    }
}
