package net.benelog.spidersense.launcher;

import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The settings the launcher reads and fills in before the OpenTelemetry agent starts: in
 * production this JVM's system properties with the environment behind them, in a test a map.
 *
 * <p>A setting is read by {@link Config#propertyOrEnv(String, Function, Function)}, the one rule of
 * configuration.adoc: the property, else its environment variable, an empty value being unset where
 * empty means nothing. It is written as a property, which is the only channel the agent reads after
 * {@code premain} has run.
 */
interface Settings {

    /** The property if set, else its environment variable, else {@code null}. */
    @Nullable String get(String name);

    /** Sets the property. */
    void set(String name, String value);

    /** This JVM's system properties, with its environment behind them. */
    Settings SYSTEM = of(System::getProperty, System::getenv, System::setProperty);

    /** Settings read from {@code property} and then {@code env}, and written through {@code set}. */
    static Settings of(Function<String, @Nullable String> property, Function<String, @Nullable String> env,
            BiConsumer<String, String> set) {
        return new Settings() {
            @Override
            public @Nullable String get(String name) {
                return Config.propertyOrEnv(name, property, env);
            }

            @Override
            public void set(String name, String value) {
                set.accept(name, value);
            }
        };
    }
}
