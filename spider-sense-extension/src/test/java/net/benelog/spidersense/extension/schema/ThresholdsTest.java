package net.benelog.spidersense.extension.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The thresholds the extension captures at, with the properties and the environment given. */
class ThresholdsTest {

    private static final Map<String, String> NONE = Map.of();

    @Test
    void unsetIsTheServersDefault() {
        assertThat(Thresholds.slowQueryMillis(NONE::get, NONE::get)).isEqualTo(100);
        assertThat(Thresholds.slowRequestMillis(NONE::get, NONE::get)).isEqualTo(500);
    }

    @Test
    void theThresholdComesFromTheSamePropertyTheServerUses() {
        Map<String, String> properties = Map.of(
                "spidersense.slow.query.ms", "250", "spidersense.slow.request.ms", "900");

        assertThat(Thresholds.slowQueryMillis(properties::get, NONE::get)).isEqualTo(250);
        assertThat(Thresholds.slowRequestMillis(properties::get, NONE::get)).isEqualTo(900);
    }

    @Test
    void theVariableCountsAndAnEmptyPropertyDoesNotHideIt() {
        Map<String, String> properties = Map.of("spidersense.slow.query.ms", "");
        Map<String, String> env = Map.of(
                "SPIDERSENSE_SLOW_QUERY_MS", "50", "SPIDERSENSE_SLOW_REQUEST_MS", "700");

        assertThat(Thresholds.slowQueryMillis(properties::get, env::get)).isEqualTo(50);
        assertThat(Thresholds.slowRequestMillis(properties::get, env::get)).isEqualTo(700);
    }

    @Test
    void nonsenseFallsBackRatherThanThrowingOutOfAStaticInitialiser() {
        Map<String, String> properties = Map.of("spidersense.slow.query.ms", "not a number");

        assertThat(Thresholds.slowQueryMillis(properties::get, NONE::get))
                .isEqualTo(Thresholds.DEFAULT_SLOW_QUERY_MS);
    }
}
