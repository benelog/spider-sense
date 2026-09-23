package net.benelog.spidersense.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The properties file, the one channel besides the command line ({@code docs/design.md},
 * "Configuration").
 *
 * <p>{@code spidersense.config} (or {@code SPIDERSENSE_CONFIG}) names the file; without it,
 * {@code spider-sense.properties} in the working directory is read when it exists. Every
 * {@code spidersense.*} key in it becomes the system property of the same name, unless that
 * property or its environment variable is set already: the command line is the more specific
 * statement and wins. Once the keys are system properties, the launcher, the server, the
 * extension and the CLI read them exactly as they read {@code -D}, so nothing else changes.
 *
 * <p>Keys without the prefix are left alone, so the same file can be handed to the OpenTelemetry
 * agent as {@code otel.javaagent.configuration-file}. A {@code spidersense.*} key that is not in
 * the table is still applied, with a warning, so a typo is visible.
 *
 * <p>Nothing here throws: a file that cannot be read is a line on stderr, and the application
 * starts as if there were none.
 */
final class ConfigFile {

    static final String PROPERTY = "spidersense.config";
    static final String DEFAULT_NAME = "spider-sense.properties";
    static final String PREFIX = "spidersense.";

    /** The keys of the table in design.md, and the ones the launcher itself understands. */
    static final Set<String> KNOWN = Set.of(
            "spidersense.port",
            "spidersense.host",
            "spidersense.collector",
            "spidersense.service",
            "spidersense.db",
            "spidersense.retention.hours",
            "spidersense.retention.spans",
            "spidersense.ingest.max-spans-per-second",
            "spidersense.slow.request.ms",
            "spidersense.slow.query.ms",
            "spidersense.open",
            "spidersense.app.packages",
            "spidersense.ignore.endpoints",
            "spidersense.source.dirs");

    private ConfigFile() {
    }

    /**
     * Reads the file and applies it to the system properties. Returns the file that was read, or
     * {@code null} when there was none.
     */
    static @Nullable Path apply() {
        Path file = locate();
        if (file == null) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            SpiderSenseAgent.warn("could not read " + file + "; ignoring it", e);
            return null;
        }
        apply(properties, file);
        return file;
    }

    /** The named file, which must exist, else the default one, which may not. */
    static @Nullable Path locate() {
        String named = Config.propertyOrEnv(PROPERTY);
        if (named != null) {
            Path file = Paths.get(named.trim());
            if (Files.isRegularFile(file)) {
                return file;
            }
            System.err.println(SpiderSenseAgent.PREFIX + PROPERTY + " names " + file
                    + ", which is not a file; ignoring it");
            return null;
        }
        Path file = Paths.get(DEFAULT_NAME);
        return Files.isRegularFile(file) ? file : null;
    }

    static void apply(Properties properties, Path file) {
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            if (!KNOWN.contains(key)) {
                System.err.println(SpiderSenseAgent.PREFIX + file + ": " + key
                        + " is not a Spider Sense property; applying it anyway");
            }
            if (System.getProperty(key) != null || System.getenv(Config.envName(key)) != null) {
                continue;
            }
            // Trimmed, as a -D value would be typed; an empty value is kept, because an empty
            // spidersense.ignore.endpoints means "ignore nothing".
            System.setProperty(key, properties.getProperty(key).trim());
        }
    }
}
