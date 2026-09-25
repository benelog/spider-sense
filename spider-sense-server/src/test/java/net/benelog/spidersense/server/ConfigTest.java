package net.benelog.spidersense.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsWhenNothingIsGiven() {
        Config config = Config.parse(new String[0]);

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(4000);
        assertThat(config.mode()).isEqualTo(Config.STANDALONE);
        assertThat(config.retentionHours()).isEqualTo(24);
        assertThat(config.retentionSpans()).isEqualTo(1_000_000);
        assertThat(config.maxSpansPerSecond()).as("no ingest cap unless asked for").isNull();
        assertThat(config.slowRequestMs()).isEqualTo(500);
        assertThat(config.slowQueryMs()).isEqualTo(100);
        assertThat(config.db()).isEqualTo(Config.DEFAULT_DB);
    }

    @Test
    void theRetentionAndIngestCapsComeFromArgumentsOrProperties() {
        Config config = Config.parse(new String[]{
                "--retention.spans=0", "--ingest.max-spans-per-second=5000"});

        assertThat(config.retentionSpans()).as("0 is the documented \"no cap\"").isZero();
        assertThat(config.maxSpansPerSecond()).isEqualTo(5_000L);

        System.setProperty("spidersense.retention.spans", "250000");
        System.setProperty("spidersense.ingest.max-spans-per-second", "1500");
        try {
            assertThat(Config.parse(new String[0]).retentionSpans()).isEqualTo(250_000);
            assertThat(Config.parse(new String[0]).maxSpansPerSecond()).isEqualTo(1_500L);
        } finally {
            System.clearProperty("spidersense.retention.spans");
            System.clearProperty("spidersense.ingest.max-spans-per-second");
        }
    }

    @Test
    void argumentsOverrideDefaults() {
        Config config = Config.parse(new String[]{
                "--port=4999", "--host=0.0.0.0", "--mode=agent", "--retention.hours=6",
                "--slow.request.ms=250", "--slow.query.ms=50", "--embedded-service=silk-bookstore"});

        assertThat(config.port()).isEqualTo(4999);
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.agentMode()).isTrue();
        assertThat(config.retentionHours()).isEqualTo(6);
        assertThat(config.slowRequestMs()).isEqualTo(250);
        assertThat(config.slowQueryMs()).isEqualTo(50);
        assertThat(config.embeddedService()).isEqualTo("silk-bookstore");
    }

    @Test
    void aStandaloneServerIsEmbeddedInNothing() {
        assertThat(Config.parse(new String[]{"--embedded-service=orders"}).embeddedService())
                .as("standalone is the default mode").isNull();

        System.setProperty("spidersense.service", "orders");
        try {
            assertThat(Config.parse(new String[]{"--mode=standalone"}).embeddedService()).isNull();
            assertThat(Config.parse(new String[]{"--mode=agent"}).embeddedService()).isEqualTo("orders");
        } finally {
            System.clearProperty("spidersense.service");
        }
    }

    @Test
    void theJarPathIsTheLaunchersArgumentAndUnknownOtherwise() {
        assertThat(Config.parse(new String[]{"--jar=/opt/spider-sense.jar"}).jar())
                .isEqualTo("/opt/spider-sense.jar");
        if (System.getProperty("spidersense.jar") == null) {
            assertThat(Config.parse(new String[0]).jar()).isNull();
        }
    }

    @Test
    void systemPropertiesAreTheAgentModeChannelAndArgumentsWin() {
        System.setProperty("spidersense.port", "4123");
        System.setProperty("spidersense.slow.query.ms", "77");
        try {
            assertThat(Config.parse(new String[0]).port()).isEqualTo(4123);
            assertThat(Config.parse(new String[0]).slowQueryMs()).isEqualTo(77);
            assertThat(Config.parse(new String[]{"--port=1"}).port()).isEqualTo(1);
        } finally {
            System.clearProperty("spidersense.port");
            System.clearProperty("spidersense.slow.query.ms");
        }
    }

    @Test
    void aPathBecomesAnAutoServerUrlWithTheHomeExpanded() {
        Config config = Config.parse(new String[]{"--db=~/db/other/sense"});

        String home = System.getProperty("user.home");
        assertThat(config.jdbcUrl())
                .isEqualTo("jdbc:h2:" + home + "/db/other/sense;AUTO_SERVER=TRUE;NON_KEYWORDS=KEY,VALUE");
        assertThat(config.databaseFile()).hasToString(home + "/db/other/sense.mv.db");
    }

    @Test
    void aJdbcUrlIsTakenAsWrittenApartFromTheReservedWords() {
        Config config = Config.parse(new String[]{"--db=jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1"});

        assertThat(config.jdbcUrl()).isEqualTo("jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE");
        assertThat(config.databaseFile()).isNull();
    }

    /** An argument is the caller's own statement, and the launcher has already refused a bad one. */
    @Test
    void anUnparseableNumberArgumentIsRejectedAtStartupRatherThanIgnored() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Config.parse(new String[]{"--port=eight"})))
                .hasMessageContaining("--port");
    }

    /**
     * A malformed property or variable costs its own key and no other, with a warning: it must not
     * stop the embedded UI of an application that is otherwise unaffected (configuration.adoc).
     */
    @Test
    void aMalformedPropertyOrVariableWarnsAndFallsBackAlone() {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stderr = System.err;
        System.setProperty("spidersense.retention.spans", "1x");
        System.setProperty("spidersense.retention.hours", "abc");
        System.setProperty("spidersense.slow.query.ms", "77");
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            Config config = Config.parse(new String[0],
                    java.util.Map.of("SPIDERSENSE_INGEST_MAX_SPANS_PER_SECOND", "abc")::get);

            assertThat(config.retentionSpans()).isEqualTo(1_000_000);
            assertThat(config.retentionHours()).isEqualTo(24);
            assertThat(config.maxSpansPerSecond()).as("no cap, as when unset").isNull();
            assertThat(config.slowQueryMs()).as("every other key keeps its value").isEqualTo(77);
            assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("spidersense.retention.spans=1x is not a number; using 1000000")
                    .contains("spidersense.retention.hours=abc is not a number; using 24")
                    .contains("spidersense.ingest.max-spans-per-second=abc is not a number");
        } finally {
            System.setErr(stderr);
            System.clearProperty("spidersense.retention.spans");
            System.clearProperty("spidersense.retention.hours");
            System.clearProperty("spidersense.slow.query.ms");
        }
    }

    @Test
    void environmentVariablesAreReadBelowArgumentsAndSystemProperties() {
        java.util.Map<String, String> env = java.util.Map.of(
                "SPIDERSENSE_PORT", "4001",
                "SPIDERSENSE_SLOW_QUERY_MS", "25",
                "SPIDERSENSE_SLOW_REQUEST_MS", "700");
        System.setProperty("spidersense.slow.request.ms", "600");
        try {
            Config config = Config.parse(new String[]{"--slow.query.ms=40"}, env::get);

            assertThat(config.port()).isEqualTo(4001);
            assertThat(config.slowQueryMs()).isEqualTo(40);
            assertThat(config.slowRequestMs()).isEqualTo(600);
        } finally {
            System.clearProperty("spidersense.slow.request.ms");
        }
    }
}
