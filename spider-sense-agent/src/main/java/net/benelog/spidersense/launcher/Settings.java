package net.benelog.spidersense.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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

    /** The OpenTelemetry agent's own properties file, which it reads below properties and variables. */
    String AGENT_CONFIGURATION_FILE = "otel.javaagent.configuration-file";

    /**
     * {@code settings} with the OpenTelemetry agent's configuration file behind them: a key that
     * neither the property nor its variable sets is read from the file that
     * {@value #AGENT_CONFIGURATION_FILE} names, as the agent itself reads it, so a default is not
     * written over a value the file holds. Writes still go to {@code settings}.
     *
     * <p>Nothing here throws: a file that is not there or cannot be read is as if none were
     * named, and the agent says so itself when it starts.
     *
     * @param workingDir what a relative path in {@value #AGENT_CONFIGURATION_FILE} is taken from
     */
    static Settings withAgentConfigurationFile(Settings settings, Path workingDir) {
        Properties file = new Properties();
        try {
            String named = settings.get(AGENT_CONFIGURATION_FILE);
            if (named == null) {
                return settings;
            }
            Path path = workingDir.resolve(named.trim());
            if (!Files.isRegularFile(path)) {
                return settings;
            }
            // The agent reads the file as UTF-8 properties.
            try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                file.load(in);
            }
        } catch (IOException | RuntimeException e) {
            return settings;
        }
        return new Settings() {
            @Override
            public @Nullable String get(String name) {
                String value = settings.get(name);
                if (value != null) {
                    return value;
                }
                String inFile = file.getProperty(name);
                return inFile == null || inFile.isBlank() ? null : inFile.trim();
            }

            @Override
            public void set(String name, String value) {
                settings.set(name, value);
            }
        };
    }

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
