package net.benelog.spidersense.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Every test hands {@link Config#parse} its own properties, environment and home, so none depends
 * on the machine it runs on or touches JVM-global state.
 */
class ConfigTest {

    private static final Path HOME = Path.of("/home/tester");

    private final List<String> warnings = new ArrayList<>();

    private Config parse(String... args) {
        return parse(Map.of(), Map.of(), args);
    }

    private Config parse(Map<String, String> properties, Map<String, String> env, String... args) {
        return Config.parse(args, properties::get, env::get, HOME, warnings::add);
    }

    @Test
    void defaultsWhenNothingIsGiven() {
        Config config = parse();

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(4000);
        assertThat(config.mode()).isEqualTo(Config.STANDALONE);
        assertThat(config.retentionHours()).isEqualTo(24);
        assertThat(config.retentionSpans()).isEqualTo(1_000_000);
        assertThat(config.maxSpansPerSecond()).as("no ingest cap unless asked for").isNull();
        assertThat(config.slowRequestMs()).isEqualTo(500);
        assertThat(config.slowQueryMs()).isEqualTo(100);
        assertThat(config.db()).isEqualTo(Config.DEFAULT_DB);
        assertThat(config.jar()).isNull();
        assertThat(config.awaitWrites()).as("ingest never waits for the disk unless a test asks").isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void theRetentionAndIngestCapsComeFromArgumentsOrProperties() {
        Config config = parse("--retention.spans=0", "--ingest.max-spans-per-second=5000");

        assertThat(config.retentionSpans()).as("0 is the documented \"no cap\"").isZero();
        assertThat(config.maxSpansPerSecond()).isEqualTo(5_000L);

        Map<String, String> properties = Map.of(
                "spidersense.retention.spans", "250000",
                "spidersense.ingest.max-spans-per-second", "1500");
        assertThat(parse(properties, Map.of()).retentionSpans()).isEqualTo(250_000);
        assertThat(parse(properties, Map.of()).maxSpansPerSecond()).isEqualTo(1_500L);
    }

    @Test
    void argumentsOverrideDefaults() {
        Config config = parse(
                "--port=4999", "--host=0.0.0.0", "--mode=agent", "--retention.hours=6",
                "--slow.request.ms=250", "--slow.query.ms=50", "--embedded-service=silk-bookstore");

        assertThat(config.port()).isEqualTo(4999);
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.agentMode()).isTrue();
        assertThat(config.retentionHours()).isEqualTo(6);
        assertThat(config.slowRequestMs()).isEqualTo(250);
        assertThat(config.slowQueryMs()).isEqualTo(50);
        assertThat(config.embeddedService()).isEqualTo("silk-bookstore");
    }

    @Test
    void aTestAsksForIngestThatWaitsForTheWriter() {
        assertThat(parse("--await-writes").awaitWrites()).isTrue();
    }

    @Test
    void aStandaloneServerIsEmbeddedInNothing() {
        assertThat(parse("--embedded-service=orders").embeddedService())
                .as("standalone is the default mode").isNull();

        Map<String, String> properties = Map.of("spidersense.service", "orders");
        assertThat(parse(properties, Map.of(), "--mode=standalone").embeddedService()).isNull();
        assertThat(parse(properties, Map.of(), "--mode=agent").embeddedService()).isEqualTo("orders");
    }

    @Test
    void theLaunchersServiceVariableNamesTheEmbeddedServiceInAgentMode() {
        Map<String, String> env = Map.of("SPIDERSENSE_SERVICE", "orders");

        assertThat(parse(Map.of(), env, "--mode=agent").embeddedService()).isEqualTo("orders");
        assertThat(parse(Map.of("spidersense.embedded-service", "billing"), env, "--mode=agent")
                .embeddedService()).as("the server's own property wins").isEqualTo("billing");
        assertThat(parse(Map.of(), env).embeddedService()).isNull();
    }

    @Test
    void theJarPathIsTheLaunchersArgumentAndUnknownOtherwise() {
        assertThat(parse("--jar=/opt/spider-sense.jar").jar()).isEqualTo("/opt/spider-sense.jar");
        assertThat(parse(Map.of("spidersense.jar", "/opt/other.jar"), Map.of()).jar()).isEqualTo("/opt/other.jar");
        assertThat(parse().jar()).isNull();
    }

    @Test
    void systemPropertiesAreTheAgentModeChannelAndArgumentsWin() {
        Map<String, String> properties = Map.of(
                "spidersense.port", "4123",
                "spidersense.slow.query.ms", "77");

        assertThat(parse(properties, Map.of()).port()).isEqualTo(4123);
        assertThat(parse(properties, Map.of()).slowQueryMs()).isEqualTo(77);
        assertThat(parse(properties, Map.of(), "--port=1").port()).isEqualTo(1);
    }

    /** What /api/status advertises is an address to connect to, whatever the bind address. */
    @Test
    void aWildcardBindIsAdvertisedAsTheLoopbackAddress() {
        assertThat(parse("--host=0.0.0.0").endpoint(4000)).isEqualTo("http://127.0.0.1:4000");
        assertThat(parse("--host=::").endpoint(4000)).isEqualTo("http://127.0.0.1:4000");
        assertThat(parse("--host=[::]").endpoint(4000)).isEqualTo("http://127.0.0.1:4000");
        assertThat(parse("--host=::1").endpoint(4001)).isEqualTo("http://[::1]:4001");
        assertThat(parse("--host=192.168.0.7").endpoint(4000)).isEqualTo("http://192.168.0.7:4000");
    }

    /** The one table the advertised endpoint, the host check and the CLI's default URL share. */
    @Test
    void aBindAddressBecomesAHostToCall() {
        for (String wildcard : new String[]{"", " ", "0.0.0.0", "::", "[::]"}) {
            assertThat(Config.isWildcard(wildcard)).as(wildcard).isTrue();
            assertThat(Config.callableHost(wildcard)).as(wildcard).isEqualTo("127.0.0.1");
        }
        assertThat(Config.isWildcard("127.0.0.1")).isFalse();
        assertThat(Config.callableHost("::1")).isEqualTo("[::1]");
        assertThat(Config.callableHost("[::1]")).isEqualTo("[::1]");
        assertThat(Config.callableHost("192.168.0.7")).isEqualTo("192.168.0.7");
    }

    @Test
    void aLeadingTildeIsTheHomeDirectory() {
        assertThat(Config.expandHome("~/src", HOME)).isEqualTo("/home/tester/src");
        assertThat(Config.expandHome("~", HOME)).isEqualTo("/home/tester");
        assertThat(Config.expandHome("~other/src", HOME)).isEqualTo("~other/src");
        assertThat(Config.expandHome("/opt/src", HOME)).isEqualTo("/opt/src");
    }

    @Test
    void aPathBecomesAnAutoServerUrlWithTheHomeExpanded() {
        Config config = parse("--db=~/db/other/sense");

        assertThat(config.jdbcUrl())
                .isEqualTo("jdbc:h2:/home/tester/db/other/sense;AUTO_SERVER=TRUE;NON_KEYWORDS=KEY,VALUE");
        assertThat(config.databaseFile()).hasToString("/home/tester/db/other/sense.mv.db");
    }

    @Test
    void aJdbcUrlIsTakenAsWrittenApartFromTheReservedWords() {
        Config config = parse("--db=jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1");

        assertThat(config.jdbcUrl()).isEqualTo("jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE");
        assertThat(config.databaseFile()).isNull();
    }

    /** An argument is the caller's own statement, and the launcher has already refused a bad one. */
    @Test
    void anUnparseableNumberArgumentIsRejectedAtStartupRatherThanIgnored() {
        assertThat(assertThrows(IllegalArgumentException.class, () -> parse("--port=eight")))
                .hasMessageContaining("--port");
    }

    /**
     * A malformed property or variable costs its own key and no other, with a warning: it must not
     * stop the embedded UI of an application that is otherwise unaffected (configuration.adoc).
     */
    @Test
    void aMalformedPropertyOrVariableWarnsAndFallsBackAlone() {
        Config config = parse(
                Map.of("spidersense.retention.spans", "1x",
                        "spidersense.retention.hours", "abc",
                        "spidersense.slow.query.ms", "77"),
                Map.of("SPIDERSENSE_INGEST_MAX_SPANS_PER_SECOND", "abc"));

        assertThat(config.retentionSpans()).isEqualTo(1_000_000);
        assertThat(config.retentionHours()).isEqualTo(24);
        assertThat(config.maxSpansPerSecond()).as("no cap, as when unset").isNull();
        assertThat(config.slowQueryMs()).as("every other key keeps its value").isEqualTo(77);
        assertThat(String.join("\n", warnings))
                .contains("spidersense.retention.spans=1x is not a number; using 1000000")
                .contains("spidersense.retention.hours=abc is not a number; using 24")
                .contains("spidersense.ingest.max-spans-per-second=abc is not a number");
    }

    @Test
    void environmentVariablesAreReadBelowArgumentsAndSystemProperties() {
        Config config = parse(
                Map.of("spidersense.slow.request.ms", "600"),
                Map.of("SPIDERSENSE_PORT", "4001",
                        "SPIDERSENSE_SLOW_QUERY_MS", "25",
                        "SPIDERSENSE_SLOW_REQUEST_MS", "700"),
                "--slow.query.ms=40");

        assertThat(config.port()).isEqualTo(4001);
        assertThat(config.slowQueryMs()).isEqualTo(40);
        assertThat(config.slowRequestMs()).isEqualTo(600);
    }

    @Test
    void aSettingIsThePropertyElseTheVariableAndEmptyIsUnset() {
        Map<String, String> env = Map.of("SPIDERSENSE_SOURCE_DIRS", "from-env");

        assertThat(Config.setting("spidersense.source.dirs", Map.of("spidersense.source.dirs", "from-property")::get,
                env::get)).isEqualTo("from-property");
        assertThat(Config.setting("spidersense.source.dirs", Map.<String, String>of()::get, env::get))
                .isEqualTo("from-env");
        assertThat(Config.setting("spidersense.source.dirs", Map.<String, String>of()::get,
                Map.of("SPIDERSENSE_SOURCE_DIRS", "")::get)).isNull();
    }
}
