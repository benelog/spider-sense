package net.benelog.spidersense.extension.schema;

import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How the extension reads a {@code spidersense.*} setting: the system property, else its
 * {@code SPIDERSENSE_*} environment variable, and an empty value is unset (configuration.adoc).
 *
 * <p>The launcher and the server read their settings by the same rule, each with its own copy,
 * because the three jars share no code; the extension's copy is the one that must agree with them
 * for the thresholds, so that what is captured is what gets reported.
 * The launcher's and the server's exception, the keys where an empty value means something
 * ({@code spidersense.ignore.endpoints}, {@code spidersense.source.dirs}), does not apply here: the
 * extension reads neither.
 *
 * <p>This class is a helper of {@link IndexCatalog} as well as of the span processor, so it is
 * injected into the application's class loader too and references nothing but the JDK.
 */
public final class Settings {

    private Settings() {
    }

    /**
     * The setting by configuration.adoc's rule, with the properties and the environment given: an
     * empty property, such as the {@code -Dspidersense.slow.query.ms=} an unset shell variable
     * leaves, is unset and the variable is next; an empty variable is unset too.
     */
    public static @Nullable String propertyOrEnv(String name, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        String value = property.apply(name);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        String variable = env.apply(envName(name));
        return variable == null || variable.isEmpty() ? null : variable;
    }

    /**
     * A number of milliseconds read by {@link #propertyOrEnv}, trimmed; the fallback when it is
     * unset or not a number, because a malformed setting is never worth a broken span.
     */
    public static long millis(String name, long fallback, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        try {
            String value = propertyOrEnv(name, property, env);
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    /** {@code spidersense.slow.query.ms} is {@code SPIDERSENSE_SLOW_QUERY_MS}. */
    public static String envName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
