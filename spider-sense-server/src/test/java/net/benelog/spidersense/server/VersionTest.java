package net.benelog.spidersense.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void theManifestWinsOverTheGeneratedResource() {
        assertThat(Version.resolve("1.2.3", "1.2.2")).isEqualTo("1.2.3");
    }

    @Test
    void explodedClassesReadTheGeneratedResource() {
        assertThat(Version.resolve(null, "1.2.3")).isEqualTo("1.2.3");
        assertThat(Version.resolve(" ", "1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void neitherPresentIsADevBuild() {
        assertThat(Version.resolve(null, null)).isEqualTo("dev");
        assertThat(Version.resolve("", "")).isEqualTo("dev");
    }

    @Test
    void theTestRunReadsTheVersionTheBuildWrote() {
        // Exploded classes: no manifest, so this is the generated resource, which is gradle.properties' version.
        assertThat(Version.CURRENT).isNotEqualTo("dev").matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
