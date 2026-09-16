package net.benelog.spidersense.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the distributable jar we are running from and unpacks the nested server fat jar out of it.
 *
 * <p>The server cannot stay nested: it needs a real {@link java.net.URLClassLoader} URL, and the
 * whole top level of our jar is on the bootstrap class path, which is exactly where the server's
 * dependencies must not be. Extraction is to
 * {@code ${java.io.tmpdir}/spider-sense-<version>/server.jar}, skipped when a file of the same size
 * is already there, so the second JVM on a machine pays nothing.
 */
final class NestedJar {

    /** The path of the nested fat jar inside the distributable jar. */
    static final String ENTRY = "spider-sense/server.jar";

    /** Escape hatch for exploded classes (IDE, Gradle test runs): point at the fat jar directly. */
    static final String SERVER_JAR_PROPERTY = "spidersense.serverJar";

    private NestedJar() {
    }

    /**
     * The server fat jar on disk, extracting it from our own jar when needed.
     *
     * @throws IOException when we are neither inside a jar that carries the server nor told where
     *                     the fat jar is by {@value #SERVER_JAR_PROPERTY}
     */
    static Path serverJar() throws IOException {
        Path own = ownJar();
        if (own != null && hasEntry(own)) {
            return extractFrom(own, targetFile(version()));
        }
        String override = System.getProperty(SERVER_JAR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            Path p = Paths.get(override);
            if (!Files.isRegularFile(p)) {
                throw new IOException(SERVER_JAR_PROPERTY + " points at a file that does not exist: " + p);
            }
            return p;
        }
        throw new IOException("no " + ENTRY + " inside " + (own == null ? "the launcher's location" : own)
                + " and no -D" + SERVER_JAR_PROPERTY + "=<path to spider-sense-server-all.jar>");
    }

    /** The jar this class was loaded from, or {@code null} for exploded classes. */
    static Path ownJar() {
        try {
            CodeSource source = NestedJar.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return null;
            }
            Path path = Paths.get(location.toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    static boolean hasEntry(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry(ENTRY) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code ${java.io.tmpdir}/spider-sense-<version>/server.jar}. */
    static Path targetFile(String version) {
        return Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("spider-sense-" + version)
                .resolve("server.jar");
    }

    /**
     * Copies {@link #ENTRY} out of {@code jar} to {@code target}, skipping the copy when the target
     * is already there with the same size. The copy goes to a temporary file in the target's
     * directory and is then moved into place, so two JVMs starting at once never see a half-written
     * jar.
     */
    static Path extractFrom(Path jar, Path target) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry(ENTRY);
            if (entry == null) {
                throw new IOException("no " + ENTRY + " inside " + jar);
            }
            long size = entry.getSize();
            if (Files.isRegularFile(target) && size >= 0 && Files.size(target) == size) {
                return target;
            }
            Path dir = target.getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "server-", ".jar.tmp");
            try {
                try (InputStream in = jf.getInputStream(entry)) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return target;
        }
    }

    /**
     * The Spider Sense version: the manifest's {@code Implementation-Version} of our own package,
     * else the {@code spider-sense.properties} the build generates (the path taken from exploded
     * classes, which have no manifest), else {@code dev}.
     */
    static String version() {
        String v = NestedJar.class.getPackage() == null
                ? null
                : NestedJar.class.getPackage().getImplementationVersion();
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = propertiesVersion();
        return v != null && !v.isEmpty() ? v : "dev";
    }

    private static String propertiesVersion() {
        try (InputStream in = NestedJar.class.getResourceAsStream("/spider-sense.properties")) {
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
