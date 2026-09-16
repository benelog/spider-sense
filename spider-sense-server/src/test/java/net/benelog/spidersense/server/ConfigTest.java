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
        assertThat(config.slowRequestMs()).isEqualTo(500);
        assertThat(config.slowQueryMs()).isEqualTo(100);
        assertThat(config.db()).isEqualTo(Config.DEFAULT_DB);
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

    @Test
    void anUnparseableNumberIsRejectedAtStartupRatherThanIgnored() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Config.parse(new String[]{"--port=eight"})))
                .hasMessageContaining("--port");
    }
}
