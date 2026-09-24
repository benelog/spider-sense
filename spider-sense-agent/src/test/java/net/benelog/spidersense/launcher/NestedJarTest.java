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
            jos.putNextEntry(new JarEntry(NestedJar.ENTRY));
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
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));

        Path extracted = NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(extracted).isEqualTo(out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar"));
        assertThat(extracted).exists().hasContent("the server fat jar");
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));

        Path extracted = NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9");

        try (Stream<Path> files = Files.list(out())) {
            assertThat(files).containsExactly(extracted);
        }
    }

    @Test
    void reusesTheFileAlreadyThereUnderTheSameName() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar");
        Files.createDirectories(out());
        // Same name and length, different content: if the file is rewritten the content changes.
        Files.writeString(target, "XXX XXXXXX XXX XXX");

        assertThat(NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9")).isEqualTo(target);

        assertThat(target).hasContent("XXX XXXXXX XXX XXX");
    }

    @Test
    void aRebuildOfTheSameSizeGetsAFileOfItsOwn() throws IOException {
        Path before = NestedJar.extractFrom(
                jarContaining(NestedJar.ENTRY, payload("the server fat jar")), NestedJar.ENTRY, out(), "server-9.9.9");
        Path after = NestedJar.extractFrom(
                jarContaining(NestedJar.ENTRY, payload("the server NEW jar")), NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(after).isNotEqualTo(before).hasContent("the server NEW jar");
        assertThat(before).as("just used, so not pruned").hasContent("the server fat jar");
    }

    @Test
    void rewritesAFileOfTheRightNameButTheWrongSize() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = out().resolve("server-9.9.9-" + crcOf("the server fat jar") + ".jar");
        Files.createDirectories(out());
        Files.writeString(target, "cut short");

        NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(target).hasContent("the server fat jar");
    }

    @Test
    void prunesTheJarsOfOtherBuildsNoJvmHasUsedLately() throws IOException {
        Files.createDirectories(out());
        FileTime old = FileTime.from(Instant.now().minus(NestedJar.STALE_AFTER).minus(Duration.ofMinutes(1)));
        Path stale = Files.writeString(out().resolve("server-9.9.8-0badf00d.jar"), "old build");
        Files.setLastModifiedTime(stale, old);
        Path staleExtension = Files.writeString(out().resolve("extension-9.9.8-0badf00d.jar"), "old build");
        Files.setLastModifiedTime(staleExtension, old);
        Path recent = Files.writeString(out().resolve("server-9.9.9-0000beef.jar"), "in use elsewhere");

        Path extracted = NestedJar.extractFrom(
                jarContaining(NestedJar.ENTRY, payload("the server fat jar")), NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(stale).doesNotExist();
        assertThat(recent).exists();
        assertThat(staleExtension).as("another kind, pruned by its own extraction").exists();
        assertThat(extracted).exists();
    }

    /** A JVM killed during the copy leaves its temporary file; the next start clears an old one. */
    @Test
    void prunesTheTemporaryFileOfAnExtractionThatNeverFinished() throws IOException {
        Files.createDirectories(out());
        FileTime old = FileTime.from(Instant.now().minus(NestedJar.STALE_AFTER).minus(Duration.ofMinutes(1)));
        Path abandoned = Files.writeString(out().resolve("nested-123.jar.tmp"), "half a jar");
        Files.setLastModifiedTime(abandoned, old);
        Path inProgress = Files.writeString(out().resolve("nested-456.jar.tmp"), "being copied");

        NestedJar.extractFrom(
                jarContaining(NestedJar.ENTRY, payload("the server fat jar")), NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(abandoned).doesNotExist();
        assertThat(inProgress).as("another JVM may be copying it right now").exists();
    }

    @Test
    void theDirectoryIsOwnerOnly() throws IOException {
        assumeTrue(posix(dir));
        Files.createDirectories(out());
        Files.setPosixFilePermissions(out(), PosixFilePermissions.fromString("rwxrwxrwx"));

        NestedJar.extractFrom(jarContaining(NestedJar.ENTRY, payload("x")), NestedJar.ENTRY, out(), "server-9.9.9");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out()))).isEqualTo("rwx------");
    }

    @Test
    void aNewDirectoryIsCreatedOwnerOnly() throws IOException {
        assumeTrue(posix(dir));
        Path nested = dir.resolve("fresh");

        NestedJar.extractFrom(jarContaining(NestedJar.ENTRY, payload("x")), NestedJar.ENTRY, nested, "server-9.9.9");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested))).isEqualTo("rwx------");
    }

    @Test
    void refusesADirectoryThatIsASymbolicLink() throws IOException {
        assumeTrue(posix(dir));
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), real);

        assertThatThrownBy(() -> NestedJar.extractFrom(
                jarContaining(NestedJar.ENTRY, payload("x")), NestedJar.ENTRY, link, "server-9.9.9"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void complainsWhenTheEntryIsMissing() throws IOException {
        Path jar = jarContaining("something/else.jar", payload("not it"));

        assertThatThrownBy(() -> NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(NestedJar.ENTRY);
    }

    @Test
    void recognisesAJarThatCarriesTheServer() throws IOException {
        assertThat(NestedJar.hasEntry(jarContaining(NestedJar.ENTRY, payload("x")))).isTrue();
        assertThat(NestedJar.hasEntry(jarContaining("other", payload("x")))).isFalse();
        assertThat(NestedJar.hasEntry(dir.resolve("no-such.jar"))).isFalse();
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

        assertThat(NestedJar.hasEntry(jar, NestedJar.ENTRY)).isTrue();
        assertThat(NestedJar.hasEntry(jar, NestedJar.EXTENSION_ENTRY)).isTrue();

        Path server = NestedJar.extractFrom(jar, NestedJar.ENTRY, out(), "server-9.9.9");
        Path extension = NestedJar.extractFrom(jar, NestedJar.EXTENSION_ENTRY, out(), "extension-9.9.9");

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
        String previous = System.getProperty(NestedJar.EXTENSION_JAR_PROPERTY);
        try {
            System.clearProperty(NestedJar.EXTENSION_JAR_PROPERTY);
            assertThat(NestedJar.extensionJar()).as("null, not an exception").isNull();

            Path jar = dir.resolve("extension.jar");
            Files.writeString(jar, "pretend extension jar");
            System.setProperty(NestedJar.EXTENSION_JAR_PROPERTY, jar.toString());
            assertThat(NestedJar.extensionJar()).isEqualTo(jar);

            System.setProperty(NestedJar.EXTENSION_JAR_PROPERTY, dir.resolve("gone.jar").toString());
            assertThatThrownBy(NestedJar::extensionJar).isInstanceOf(IOException.class);
        } finally {
            if (previous == null) {
                System.clearProperty(NestedJar.EXTENSION_JAR_PROPERTY);
            } else {
                System.setProperty(NestedJar.EXTENSION_JAR_PROPERTY, previous);
            }
        }
    }

    @Test
    void theVersionComesFromTheBuild() {
        // Exploded classes in the test run: spider-sense.properties is the only source.
        assertThat(NestedJar.version()).isNotEmpty().isNotEqualTo("dev");
    }

    @Test
    void theServerJarFallsBackToTheSystemProperty() throws IOException {
        // Tests run from exploded classes, so ownJar() is null and the override decides.
        assertThat(NestedJar.ownJar()).isNull();
        Path fat = dir.resolve("spider-sense-server-all.jar");
        Files.writeString(fat, "pretend fat jar");
        String previous = System.getProperty(NestedJar.SERVER_JAR_PROPERTY);
        try {
            System.setProperty(NestedJar.SERVER_JAR_PROPERTY, fat.toString());
            assertThat(NestedJar.serverJar()).isEqualTo(fat);

            System.setProperty(NestedJar.SERVER_JAR_PROPERTY, dir.resolve("gone.jar").toString());
            assertThatThrownBy(NestedJar::serverJar).isInstanceOf(IOException.class);
        } finally {
            if (previous == null) {
                System.clearProperty(NestedJar.SERVER_JAR_PROPERTY);
            } else {
                System.setProperty(NestedJar.SERVER_JAR_PROPERTY, previous);
            }
        }
    }
}
