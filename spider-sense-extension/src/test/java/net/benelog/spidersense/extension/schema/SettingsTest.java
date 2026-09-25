package net.benelog.spidersense.extension.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The extension's reading of a setting, with the properties and the environment given. */
class SettingsTest {

    private static final String KEY = "spidersense.slow.query.ms";

    /**
     * configuration.adoc's rule as a table, the same rows the launcher's and the server's tests
     * hold: the property, else the variable; an empty property or variable is unset.
     */
    @Test
    void aSettingIsThePropertyElseTheVariableAndEmptyIsUnset() {
        record Row(String property, String variable, String expected) {
        }
        String unset = null;
        List<Row> rows = List.of(
                new Row("250", "50", "250"),
                new Row(unset, "50", "50"),
                new Row("", "50", "50"),
                new Row("", "", unset),
                new Row(unset, "", unset),
                new Row(unset, unset, unset));
        for (Row row : rows) {
            assertThat(Settings.propertyOrEnv(KEY, properties(row.property())::get,
                    env(row.variable())::get)).as("%s", row).isEqualTo(row.expected());
        }
    }

    /**
     * The case an unset shell variable makes: {@code -Dspidersense.slow.query.ms=} beside
     * {@code SPIDERSENSE_SLOW_QUERY_MS=50} is 50, which is also what the launcher passes the server.
     */
    @Test
    void anEmptyPropertyLeavesTheVariableInForce() {
        assertThat(Settings.millis(KEY, 100, properties("")::get, env("50")::get)).isEqualTo(50);
    }

    @Test
    void anUnsetOrMalformedValueIsTheFallback() {
        assertThat(Settings.millis(KEY, 100, properties(null)::get, env(null)::get)).isEqualTo(100);
        assertThat(Settings.millis(KEY, 100, properties("fast")::get, env("50")::get)).isEqualTo(100);
        assertThat(Settings.millis(KEY, 100, properties(" 250 ")::get, env(null)::get)).isEqualTo(250);
    }

    @Test
    void theVariableIsTheKeyUpperCasedWithUnderscores() {
        assertThat(Settings.envName("spidersense.slow.query.ms")).isEqualTo("SPIDERSENSE_SLOW_QUERY_MS");
        assertThat(Settings.envName("spidersense.ingest.max-spans-per-second"))
                .isEqualTo("SPIDERSENSE_INGEST_MAX_SPANS_PER_SECOND");
    }

    private static Map<String, String> properties(String value) {
        Map<String, String> properties = new HashMap<>();
        if (value != null) {
            properties.put(KEY, value);
        }
        return properties;
    }

    private static Map<String, String> env(String value) {
        Map<String, String> env = new HashMap<>();
        if (value != null) {
            env.put("SPIDERSENSE_SLOW_QUERY_MS", value);
        }
        return env;
    }
}
