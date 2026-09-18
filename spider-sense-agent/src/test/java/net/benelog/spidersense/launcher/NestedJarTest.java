package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NestedJarTest {

    @TempDir
    Path dir;

    private Path jarContaining(String entry, byte[] bytes) throws IOException {
        Path jar = dir.resolve("outer-" + entry.hashCode() + ".jar");
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

    @Test
    void extractsTheNestedJar() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = dir.resolve("out").resolve("server.jar");

        Path extracted = NestedJar.extractFrom(jar, target);

        assertThat(extracted).isEqualTo(target);
        assertThat(target).exists().hasContent("the server fat jar");
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = dir.resolve("out").resolve("server.jar");

        NestedJar.extractFrom(jar, target);

        assertThat(Files.list(target.getParent()).map(p -> p.getFileName().toString()))
                .containsExactly("server.jar");
    }

    @Test
    void skipsExtractionWhenTheFileIsAlreadyThereWithTheSameSize() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = dir.resolve("out").resolve("server.jar");
        Files.createDirectories(target.getParent());
        // Same length, different content: if the file is rewritten the content changes.
        Files.writeString(target, "XXX XXXXXX XXX XXX");

        NestedJar.extractFrom(jar, target);

        assertThat(target).hasContent("XXX XXXXXX XXX XXX");
    }

    @Test
    void rewritesWhenTheSizeDiffers() throws IOException {
        Path jar = jarContaining(NestedJar.ENTRY, payload("the server fat jar"));
        Path target = dir.resolve("out").resolve("server.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "an older, shorter jar");

        NestedJar.extractFrom(jar, target);

        assertThat(target).hasContent("the server fat jar");
    }

    @Test
    void complainsWhenTheEntryIsMissing() throws IOException {
        Path jar = jarContaining("something/else.jar", payload("not it"));

        assertThatThrownBy(() -> NestedJar.extractFrom(jar, dir.resolve("out/server.jar")))
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
    void theTargetIsUnderTheTemporaryDirectoryAndNamedAfterTheVersion() {
        Path target = NestedJar.targetFile("1.2.3");
        assertThat(target.getFileName().toString()).isEqualTo("server.jar");
        assertThat(target.getParent().getFileName().toString()).isEqualTo("spider-sense-1.2.3");
        assertThat(target.getParent().getParent())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Test
    void theExtensionIsExtractedBesideTheServer() throws IOException {
        Path jar = jarContainingBoth();

        assertThat(NestedJar.hasEntry(jar, NestedJar.ENTRY)).isTrue();
        assertThat(NestedJar.hasEntry(jar, NestedJar.EXTENSION_ENTRY)).isTrue();

        Path server = dir.resolve("out").resolve("server.jar");
        Path extension = dir.resolve("out").resolve("extension.jar");
        assertThat(NestedJar.extractFrom(jar, NestedJar.ENTRY, server)).isEqualTo(server);
        assertThat(NestedJar.extractFrom(jar, NestedJar.EXTENSION_ENTRY, extension)).isEqualTo(extension);

        assertThat(server).hasContent("the server fat jar");
        assertThat(extension).hasContent("the extension jar");
        assertThat(Files.list(server.getParent()).map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder("server.jar", "extension.jar");
    }

    @Test
    void bothNestedJarsLandInTheSameVersionedDirectory() {
        Path server = NestedJar.targetFile("1.2.3");
        Path extension = NestedJar.extensionFile("1.2.3");
        assertThat(extension.getFileName().toString()).isEqualTo("extension.jar");
        assertThat(extension.getParent()).isEqualTo(server.getParent());
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
