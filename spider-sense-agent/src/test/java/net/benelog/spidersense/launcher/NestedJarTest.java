package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NestedJarTest {

    /** The moment every extraction and prune in these tests happens at. */
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    /** Long enough untouched to be pruned at {@link #NOW}. */
    private static final FileTime STALE = FileTime.from(NOW.minus(NestedJar.STALE_AFTER).minus(Duration.ofMinutes(1)));

    /** Touched just before {@link #NOW}: in use elsewhere. */
    private static final FileTime RECENT = FileTime.from(NOW.minusSeconds(10));

    @TempDir
    Path dir;

    private Path jarContaining(String entry, byte[] bytes) throws IOException {
        Path jar = dir.resolve("outer-" + entry.hashCode() + "-" + new String(bytes, StandardCharsets.UTF_8).hashCode() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "9.9.9");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry(entry));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }

    /** The distributable jar as it really is: both nested jars, side by side. */
    private Path jarContainingBoth() throws IOException {
        Path jar = dir.resolve("spider-sense.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "9.9.9");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry(NestedJar.SERVER_ENTRY));
            jos.write(payload("the server fat jar"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry(NestedJar.EXTENSION_ENTRY));
            jos.write(payload("the extension jar"));
            jos.closeEntry();
        }
        return jar;
    }

    private static byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String crcOf(String text) {
        CRC32 crc = new CRC32();
        crc.update(payload(text));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    private Path out() {
        return dir.resolve("out");
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void extractsTheNestedJarUnderTheCrcOfItsContent() throws IOException {
        Path jar = jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar"));

        Path extracted = NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(extracted).isEqualTo(out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar"));
        assertThat(extracted).exists().hasContent("the server fat jar");
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        Path jar = jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar"));

        Path extracted = NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        try (Stream<Path> files = Files.list(out())) {
            assertThat(files).containsExactly(extracted);
        }
    }

    @Test
    void reusesTheFileAlreadyThereUnderTheSameName() throws IOException {
        Path jar = jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar"));
        Path target = out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar");
        Files.createDirectories(out());
        // Same name and length, different content: if the file is rewritten the content changes.
        Files.writeString(target, "XXX XXXXXX XXX XXX");

        assertThat(NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW)).isEqualTo(target);

        assertThat(target).hasContent("XXX XXXXXX XXX XXX");
    }

    @Test
    void aRebuildOfTheSameSizeGetsAFileOfItsOwn() throws IOException {
        Path before = NestedJar.extractFrom(
                jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar")), NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);
        Path after = NestedJar.extractFrom(
                jarContaining(NestedJar.SERVER_ENTRY, payload("the server NEW jar")), NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(after).isNotEqualTo(before).hasContent("the server NEW jar");
        assertThat(before).as("just used, so not pruned").hasContent("the server fat jar");
    }

    @Test
    void rewritesAFileOfTheRightNameButTheWrongSize() throws IOException {
        Path jar = jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar"));
        Path target = out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar");
        Files.createDirectories(out());
        Files.writeString(target, "cut short");

        NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(target).hasContent("the server fat jar");
    }

    @Test
    void prunesTheJarsOfOtherBuildsNoJvmHasUsedLately() throws IOException {
        Files.createDirectories(out());
        Path stale = Files.writeString(out().resolve("server-9.9.8-0badf00d.jar"), "old build");
        Files.setLastModifiedTime(stale, STALE);
        Path staleExtension = Files.writeString(out().resolve("extension-9.9.8-0badf00d.jar"), "old build");
        Files.setLastModifiedTime(staleExtension, STALE);
        Path recent = Files.writeString(out().resolve("server-9.9.9-0000beef.jar"), "in use elsewhere");
        Files.setLastModifiedTime(recent, RECENT);

        Path extracted = NestedJar.extractFrom(
                jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar")), NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(stale).doesNotExist();
        assertThat(recent).exists();
        assertThat(staleExtension).as("another kind, pruned by its own extraction").exists();
        assertThat(extracted).exists();
    }

    /** A JVM killed during the copy leaves its temporary file; the next start clears an old one. */
    @Test
    void prunesTheTemporaryFileOfAnExtractionThatNeverFinished() throws IOException {
        Files.createDirectories(out());
        Path abandoned = Files.writeString(out().resolve("nested-123.jar.tmp"), "half a jar");
        Files.setLastModifiedTime(abandoned, STALE);
        Path inProgress = Files.writeString(out().resolve("nested-456.jar.tmp"), "being copied");
        Files.setLastModifiedTime(inProgress, RECENT);

        NestedJar.extractFrom(
                jarContaining(NestedJar.SERVER_ENTRY, payload("the server fat jar")), NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(abandoned).doesNotExist();
        assertThat(inProgress).as("another JVM may be copying it right now").exists();
    }

    @Test
    void prunesWhatTheGlobMatchesOnceStaleAsOfTheGivenMoment() throws IOException {
        Path stale = Files.writeString(dir.resolve("server-1.jar"), "stale");
        Files.setLastModifiedTime(stale, STALE);
        Path kept = Files.writeString(dir.resolve("server-2.jar"), "stale, but this JVM's own");
        Files.setLastModifiedTime(kept, STALE);
        Path recent = Files.writeString(dir.resolve("server-3.jar"), "recent");
        Files.setLastModifiedTime(recent, RECENT);
        Path otherKind = Files.writeString(dir.resolve("extension-1.jar"), "stale, another kind");
        Files.setLastModifiedTime(otherKind, STALE);

        NestedJar.pruneStale(dir, "server-*.jar", kept, NOW);

        assertThat(stale).doesNotExist();
        assertThat(kept).exists();
        assertThat(recent).exists();
        assertThat(otherKind).exists();
    }

    @Test
    void theDirectoryIsOwnerOnly() throws IOException {
        assumeTrue(posix(dir));
        Files.createDirectories(out());
        Files.setPosixFilePermissions(out(), PosixFilePermissions.fromString("rwxrwxrwx"));

        NestedJar.extractFrom(jarContaining(NestedJar.SERVER_ENTRY, payload("x")), NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out()))).isEqualTo("rwx------");
    }

    @Test
    void aNewDirectoryIsCreatedOwnerOnly() throws IOException {
        assumeTrue(posix(dir));
        Path nested = dir.resolve("fresh");

        NestedJar.extractFrom(jarContaining(NestedJar.SERVER_ENTRY, payload("x")), NestedJar.SERVER_ENTRY, nested, "server", "9.9.9", NOW);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested))).isEqualTo("rwx------");
    }

    @Test
    void refusesADirectoryThatIsASymbolicLink() throws IOException {
        assumeTrue(posix(dir));
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), real);

        assertThatThrownBy(() -> NestedJar.extractFrom(
                jarContaining(NestedJar.SERVER_ENTRY, payload("x")), NestedJar.SERVER_ENTRY, link, "server", "9.9.9", NOW))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void complainsWhenTheEntryIsMissing() throws IOException {
        Path jar = jarContaining("something/else.jar", payload("not it"));

        assertThatThrownBy(() -> NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(NestedJar.SERVER_ENTRY);
    }

    @Test
    void recognisesAJarThatCarriesTheServer() throws IOException {
        assertThat(NestedJar.hasServerEntry(jarContaining(NestedJar.SERVER_ENTRY, payload("x")))).isTrue();
        assertThat(NestedJar.hasServerEntry(jarContaining("other", payload("x")))).isFalse();
        assertThat(NestedJar.hasServerEntry(dir.resolve("no-such.jar"))).isFalse();
    }

    @Test
    void theDirectoryIsUnderTheTemporaryDirectoryAndNamedAfterTheUser() {
        Path directory = NestedJar.directory();
        assertThat(directory.getParent()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
        assertThat(directory.getFileName().toString())
                .isEqualTo(NestedJar.directoryName(System.getProperty("user.name")));
    }

    @Test
    void theUserNameIsMadeSafeForAPath() {
        assertThat(NestedJar.directoryName("alice")).isEqualTo("spider-sense-alice");
        assertThat(NestedJar.directoryName("CORP\\bob smith")).isEqualTo("spider-sense-CORP_bob_smith");
        assertThat(NestedJar.directoryName("../x")).isEqualTo("spider-sense-.._x");
        assertThat(NestedJar.directoryName("")).isEqualTo("spider-sense-unknown");
        assertThat(NestedJar.directoryName(null)).isEqualTo("spider-sense-unknown");
    }

    @Test
    void theExtensionIsExtractedBesideTheServer() throws IOException {
        Path jar = jarContainingBoth();

        assertThat(NestedJar.hasEntry(jar, NestedJar.SERVER_ENTRY)).isTrue();
        assertThat(NestedJar.hasEntry(jar, NestedJar.EXTENSION_ENTRY)).isTrue();

        Path server = NestedJar.extractFrom(jar, NestedJar.SERVER_ENTRY, out(), "server", "9.9.9", NOW);
        Path extension = NestedJar.extractFrom(jar, NestedJar.EXTENSION_ENTRY, out(), "extension", "9.9.9", NOW);

        assertThat(server).hasContent("the server fat jar");
        assertThat(extension).hasContent("the extension jar");
        assertThat(extension.getFileName().toString()).startsWith("extension-9.9.9-");
        try (Stream<Path> files = Files.list(out())) {
            assertThat(files).containsExactlyInAnyOrder(server, extension);
        }
    }

    @Test
    void thereIsNoExtensionToPointAtFromExplodedClasses() throws IOException {
        assertThat(NestedJar.ownJar()).isNull();
        assertThat(NestedJar.locate(NestedJar.EXTENSION_ENTRY, NestedJar.EXTENSION_KIND, null))
                .as("null, not an exception").isNull();
        assertThat(NestedJar.locate(NestedJar.EXTENSION_ENTRY, NestedJar.EXTENSION_KIND, ""))
                .as("an empty override is none").isNull();

        Path jar = dir.resolve("extension.jar");
        Files.writeString(jar, "pretend extension jar");
        assertThat(NestedJar.locate(NestedJar.EXTENSION_ENTRY, NestedJar.EXTENSION_KIND, jar.toString()))
                .isEqualTo(jar);

        String gone = dir.resolve("gone.jar").toString();
        assertThatThrownBy(() -> NestedJar.locate(NestedJar.EXTENSION_ENTRY, NestedJar.EXTENSION_KIND, gone))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(NestedJar.EXTENSION_JAR_PROPERTY);
    }

    @Test
    void theVersionComesFromTheBuild() {
        // Exploded classes in the test run: spider-sense-version.properties is the only source.
        assertThat(NestedJar.version()).isNotEmpty().isNotEqualTo("dev");
    }

    @Test
    void theServerJarFallsBackToTheOverride() throws IOException {
        // Tests run from exploded classes, so ownJar() is null and the override decides.
        assertThat(NestedJar.ownJar()).isNull();
        Path fat = dir.resolve("spider-sense-server-all.jar");
        Files.writeString(fat, "pretend fat jar");
        assertThat(NestedJar.locate(NestedJar.SERVER_ENTRY, NestedJar.SERVER_KIND, fat.toString())).isEqualTo(fat);

        String gone = dir.resolve("gone.jar").toString();
        assertThatThrownBy(() -> NestedJar.locate(NestedJar.SERVER_ENTRY, NestedJar.SERVER_KIND, gone))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(NestedJar.SERVER_JAR_PROPERTY);
    }
}
