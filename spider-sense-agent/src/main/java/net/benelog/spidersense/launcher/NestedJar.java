package net.benelog.spidersense.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Finds the distributable jar we are running from and unpacks the nested jars out of it.
 *
 * <p>The server cannot stay nested: it needs a real {@link java.net.URLClassLoader} URL, and the
 * whole top level of our jar is on the bootstrap class path, which is exactly where the server's
 * dependencies must not be. Extraction is to
 * {@code ${java.io.tmpdir}/spider-sense-<user>/server-<version>-<crc>.jar}, where {@code <crc>} is
 * the CRC-32 of the nested entry's content, read from the jar's central directory at no cost. A
 * file of that name holds that content, so the second JVM on a machine pays nothing, and a rebuild
 * at the same version gets a file of its own instead of running the old code.
 *
 * <p>The directory belongs to one user: it is created owner-only, and a directory someone else owns
 * is refused rather than loaded from, because a jar there could be anyone's code. An extracted file
 * is never replaced while its name is right, so a JVM holding it open (which on Windows blocks a
 * replacement) never stands in another's way.
 *
 * <p>The OpenTelemetry agent extension is nested and extracted the same way, to
 * {@code extension-<version>-<crc>.jar} in that same directory, for the reason turned around: the
 * agent loads an extension from a path on disk, with a class loader of its own
 * ({@code design.adoc#extension-build}).
 */
final class NestedJar {

    /** The path of the nested server fat jar inside the distributable jar. */
    static final String ENTRY = "spider-sense/server.jar";

    /** The path of the nested OpenTelemetry agent extension inside the distributable jar. */
    static final String EXTENSION_ENTRY = "spider-sense/extension.jar";

    /** Escape hatch for exploded classes (IDE, Gradle test runs): point at the fat jar directly. */
    static final String SERVER_JAR_PROPERTY = "spidersense.serverJar";

    /** The same escape hatch for the extension jar. */
    static final String EXTENSION_JAR_PROPERTY = "spidersense.extensionJar";

    /**
     * How long another build's extracted jar stays once a new one is extracted: long enough for a
     * JVM that has just found it to open it. An open file outlives its deletion on Linux and macOS,
     * and cannot be deleted on Windows, so a running JVM never loses its jar.
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(1);

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

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
        if (own != null && hasEntry(own, ENTRY)) {
            return extractFrom(own, ENTRY, directory(), "server-" + version());
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

    /**
     * The OpenTelemetry agent extension on disk, extracted from our own jar when needed, or
     * {@code null} when there is none to point the agent at. Exploded classes without
     * {@value #EXTENSION_JAR_PROPERTY} are the ordinary way that happens, and it is not an error: a
     * {@code code.stacktrace} that is never set costs a finding a line and nothing else.
     *
     * @throws IOException when the entry is there but cannot be unpacked, or the override names a
     *                     file that is not there
     */
    static @Nullable Path extensionJar() throws IOException {
        Path own = ownJar();
        if (own != null && hasEntry(own, EXTENSION_ENTRY)) {
            return extractFrom(own, EXTENSION_ENTRY, directory(), "extension-" + version());
        }
        String override = System.getProperty(EXTENSION_JAR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            Path p = Paths.get(override);
            if (!Files.isRegularFile(p)) {
                throw new IOException(EXTENSION_JAR_PROPERTY + " points at a file that does not exist: " + p);
            }
            return p;
        }
        return null;
    }

    /** The jar this class was loaded from, or {@code null} for exploded classes. */
    static @Nullable Path ownJar() {
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
        return hasEntry(jar, ENTRY);
    }

    static boolean hasEntry(Path jar, String entry) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry(entry) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code ${java.io.tmpdir}/spider-sense-<user>}: this user's, and no one else's. */
    static Path directory() {
        return Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve(directoryName(System.getProperty("user.name")));
    }

    /** {@code spider-sense-<user>}, with anything but letters, digits, dots and dashes made {@code _}. */
    static String directoryName(@Nullable String user) {
        String name = user == null || user.isEmpty() ? "unknown" : user;
        return "spider-sense-" + name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Copies {@code name} out of {@code jar} into {@code dir} as {@code <prefix>-<crc>.jar}, and
     * returns that file. A file already there under that name and with the entry's size holds the
     * same content, and is used as it is. The copy goes to a temporary file in {@code dir} and is
     * then moved into place, so two JVMs starting at once never see a half-written jar.
     *
     * <p>The prefix is {@code server-<version>} or {@code extension-<version>}; after a new
     * extraction, the jars of other builds with the same first word are pruned.
     *
     * @throws IOException when the entry is missing, or {@code dir} belongs to another user
     */
    static Path extractFrom(Path jar, String name, Path dir, String prefix) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry(name);
            if (entry == null) {
                throw new IOException("no " + name + " inside " + jar);
            }
            ownDirectory(dir);
            long size = entry.getSize();
            Path target = dir.resolve(prefix + "-" + crc(jf, entry) + ".jar");
            if (isComplete(target, size)) {
                // Touched, so that another build's prune sees it in use.
                Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
                pruneStale(dir, TEMP_GLOB, null);
                return target;
            }
            Path temp = Files.createTempFile(dir, "nested-", ".jar.tmp");
            try {
                try (InputStream in = jf.getInputStream(entry)) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // Another JVM got there first, and on Windows may hold the file open already.
                    if (!isComplete(target, size)) {
                        throw e;
                    }
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            int dash = prefix.indexOf('-');
            pruneStale(dir, (dash < 0 ? prefix : prefix.substring(0, dash + 1)) + "*.jar", target);
            pruneStale(dir, TEMP_GLOB, null);
            return target;
        }
    }

    private static boolean isComplete(Path target, long size) throws IOException {
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && (size < 0 || Files.size(target) == size);
    }

    /**
     * The entry's CRC-32 as eight hex digits: from the central directory, or computed from the
     * content when the jar does not record it.
     */
    private static String crc(JarFile jf, JarEntry entry) throws IOException {
        long crc = entry.getCrc();
        if (crc < 0) {
            CRC32 sum = new CRC32();
            try (InputStream in = new CheckedInputStream(jf.getInputStream(entry), sum)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            crc = sum.getValue();
        }
        return String.format(Locale.ROOT, "%08x", crc);
    }

    /**
     * Creates {@code dir} owner-only where the file system has POSIX permissions, and refuses it
     * when another user owns it: that user could replace a jar in it. The owner is compared with
     * that of a file this JVM has just created there, which needs no user name lookup. Windows
     * keeps {@code java.io.tmpdir} per user already.
     */
    private static void ownDirectory(Path dir) throws IOException {
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createDirectories(dir);
        }
        if (Files.isSymbolicLink(dir)) {
            throw new IOException(dir + " is a symbolic link; remove it, and Spider Sense creates the directory");
        }
        if (!posix) {
            return;
        }
        Path probe = Files.createTempFile(dir, "owner-", ".tmp");
        try {
            UserPrincipal owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            if (!owner.equals(Files.getOwner(probe))) {
                throw new IOException(dir + " belongs to " + owner.getName()
                        + ", not to this user; remove it or give this JVM another -Djava.io.tmpdir");
            }
        } finally {
            Files.deleteIfExists(probe);
        }
        if (!Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY)) {
            Files.setPosixFilePermissions(dir, OWNER_ONLY);
        }
    }

    /**
     * The temporary files an extraction copies into before its atomic rename. One is left behind
     * only by a JVM killed during the copy, and a copy in progress keeps touching its own.
     */
    private static final String TEMP_GLOB = "nested-*.jar.tmp";

    /**
     * Deletes the files {@code glob} matches, best effort, once they have gone {@link #STALE_AFTER}
     * untouched: the jars of other builds of one kind ({@code server-} or {@code extension-}),
     * which every JVM that starts from one touches, so what goes is what no JVM has started from
     * lately, and the temporary files of extractions that never finished.
     */
    static void pruneStale(Path dir, String glob, @Nullable Path keep) {
        FileTime cutoff = FileTime.from(Instant.now().minus(STALE_AFTER));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
            for (Path file : files) {
                try {
                    if (!file.equals(keep)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) < 0) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    // Open elsewhere (Windows), or gone already: it goes another time.
                }
            }
        } catch (IOException | RuntimeException e) {
            // Pruning is housekeeping; the jar this JVM needs is in place.
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

    private static @Nullable String propertiesVersion() {
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
