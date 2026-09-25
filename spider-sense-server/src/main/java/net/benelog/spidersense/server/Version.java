package net.benelog.spidersense.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * What this server calls itself: the name and the release version that {@code /api/status}, the
 * MCP handshake and every export document report.
 *
 * <p>The version is {@code version} in {@code gradle.properties}, written by the build in two
 * places: the server jar's manifest as {@code Implementation-Version}, and a
 * {@code version.properties} beside this class for exploded classes (tests, an IDE), which have
 * no manifest. Neither present means a build this code cannot see, and it answers {@code dev}.
 */
public final class Version {

    public static final String NAME = "Spider Sense";

    /** The release version this server was built as. */
    public static final String CURRENT = resolve(
            Version.class.getPackage() == null ? null : Version.class.getPackage().getImplementationVersion(),
            fromProperties());

    private Version() {
    }

    /** The manifest's version, else the generated resource's, else {@code dev}. */
    static String resolve(@Nullable String manifest, @Nullable String properties) {
        if (manifest != null && !manifest.isBlank()) {
            return manifest;
        }
        return properties != null && !properties.isBlank() ? properties : "dev";
    }

    private static @Nullable String fromProperties() {
        try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
