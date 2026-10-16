package net.benelog.spidersense.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The jar's first class: which JVM it hands over on, and that nothing escapes it. */
class VersionGateTest {

    @Test
    void theFeatureReleaseIsReadFromEitherSpelling() {
        assertThat(VersionGate.feature("1.8")).isEqualTo(8);
        assertThat(VersionGate.feature("11")).isEqualTo(11);
        assertThat(VersionGate.feature("17")).isEqualTo(17);
        assertThat(VersionGate.feature("25")).isEqualTo(25);
        assertThat(VersionGate.feature(null)).as("not said: new enough to try").isEqualTo(Integer.MAX_VALUE);
        assertThat(VersionGate.feature("x")).as("not a number: new enough to try").isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void belowTheAgentsFloorItIsOneLineAndNoHandOver() {
        AtomicInteger handedOver = new AtomicInteger();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        VersionGate.attach("11", "11.0.25", new PrintStream(err, true, StandardCharsets.UTF_8),
                handedOver::incrementAndGet);
        VersionGate.attach("1.8", "1.8.0_462", new PrintStream(err, true, StandardCharsets.UTF_8),
                handedOver::incrementAndGet);

        assertThat(handedOver).hasValue(0);
        assertThat(err.toString(StandardCharsets.UTF_8).lines()).containsExactly(
                "[spider-sense] this JVM is Java 11.0.25, and Spider Sense needs 17 or later; the application runs without it",
                "[spider-sense] this JVM is Java 1.8.0_462, and Spider Sense needs 17 or later; the application runs without it");
    }

    @Test
    void fromTheAgentsFloorItHandsOver() {
        AtomicInteger handedOver = new AtomicInteger();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        VersionGate.attach("17", "17.0.16", new PrintStream(err, true, StandardCharsets.UTF_8),
                handedOver::incrementAndGet);
        VersionGate.attach("21", "21.0.8", new PrintStream(err, true, StandardCharsets.UTF_8),
                handedOver::incrementAndGet);

        assertThat(handedOver).hasValue(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** Anything that escaped premain would abort the JVM, the application with it. */
    @Test
    void aClassThisJvmCannotReadIsOneLineAndNoFailure() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        VersionGate.attach("17", "17.0.16", new PrintStream(err, true, StandardCharsets.UTF_8), () -> {
            throw new UnsupportedClassVersionError("SpiderSenseAgent has been compiled by a more recent version");
        });

        assertThat(err.toString(StandardCharsets.UTF_8).lines()).containsExactly(
                "[spider-sense] did not start, and the application runs without it: java.lang.UnsupportedClassVersionError:"
                        + " SpiderSenseAgent has been compiled by a more recent version");
    }

    @Test
    void theStandaloneJarNamesTheJavaItNeedsAndTheOneItGot() {
        assertThat(VersionGate.belowServerFloor("21.0.8", "/opt/jdk-21"))
                .isEqualTo("the standalone server and the command line need Java 25 or later, and this JVM is Java 21.0.8"
                        + " (/opt/jdk-21)");
    }
}
