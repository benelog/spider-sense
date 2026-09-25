package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The launcher's configuration, read from given properties and a given environment: nothing here
 * reads or writes this JVM's system properties or depends on the developer's environment.
 */
class ConfigTest {

    private static final Map<String, String> NONE = Map.of();

    /** The arguments alone, with no property or variable set. */
    private static Config.Parsed parse(String... args) {
        return Config.parse(args, NONE::get, NONE::get);
    }

    private static Config config(String... args) {
        return parse(args).config();
    }

    /** A malformed number on the command line is one usage line, not a stack trace. */
    @Test
    void aMalformedNumberArgumentSaysWhichAndWhat() {
        assertThatThrownBy(() -> parse("--port=abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--port is not a number: abc");
        assertThatThrownBy(() -> parse("--slow.query.ms=1x"))
                .hasMessage("--slow.query.ms is not a number: 1x");
        assertThatThrownBy(() -> parse("--retention-hours=a day"))
                .as("named as it was typed")
                .hasMessage("--retention-hours is not a number: a day");
        assertThatThrownBy(() -> parse("--retention.spans=1x"))
                .as("a key the server owns is checked before it is forwarded")
                .hasMessage("--retention.spans is not a number: 1x");
        assertThatThrownBy(() -> parse("--ingest.max-spans-per-second=abc"))
                .hasMessage("--ingest.max-spans-per-second is not a number: abc");
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        Config c = Config.defaults();
        assertThat(c.port()).isEqualTo(4000);
        assertThat(c.host()).isEqualTo("127.0.0.1");
        assertThat(c.collector()).isNull();
        assertThat(c.service()).isNull();
        assertThat(c.db()).as("unset: the server opens ~/db/spider-sense/sense").isNull();
        assertThat(c.retentionHours()).as("unset: the server sweeps to 24 hours").isNull();
        assertThat(c.slowRequestMs()).isEqualTo(500);
        assertThat(c.slowQueryMs()).isEqualTo(100);
        assertThat(c.open()).isFalse();
        assertThat(c.mode()).isEqualTo("agent");
    }

    @Test
    void systemPropertiesWithoutAnythingSetGiveTheDefaults() {
        assertThat(Config.fromSystemProperties(NONE::get, NONE::get)).isEqualTo(Config.defaults());
    }

    @Test
    void environmentVariablesAreReadBelowSystemProperties() {
        Map<String, String> env = Map.of(
                "SPIDERSENSE_PORT", "4001",
                "SPIDERSENSE_COLLECTOR", "http://127.0.0.1:4000",
                "SPIDERSENSE_SLOW_QUERY_MS", "25");
        Map<String, String> properties = Map.of("spidersense.slow.query.ms", "30");

        Config c = Config.fromSystemProperties(properties::get, env::get);

        assertThat(c.port()).isEqualTo(4001);
        assertThat(c.collector()).isEqualTo("http://127.0.0.1:4000");
        assertThat(c.slowQueryMs()).isEqualTo(30);
    }

    /**
     * The case an unset shell variable makes: {@code -Dspidersense.slow.query.ms=} beside
     * {@code SPIDERSENSE_SLOW_QUERY_MS=50} is 50, which the launcher passes to the server and the
     * extension captures at.
     */
    @Test
    void anEmptyPropertyLeavesTheVariableInForce() {
        Map<String, String> properties = Map.of(
                "spidersense.slow.query.ms", "", "spidersense.port", "");
        Map<String, String> env = Map.of("SPIDERSENSE_SLOW_QUERY_MS", "50");

        Config c = Config.fromSystemProperties(properties::get, env::get);

        assertThat(c.slowQueryMs()).isEqualTo(50);
        assertThat(c.port()).isEqualTo(Config.DEFAULT_PORT);
        assertThat(c.toServerArgs()).contains("--slow.query.ms=50");
    }

    /**
     * configuration.adoc's rule as a table: the property, else the variable; an empty variable is
     * unset, and so is an empty property, except for the two keys where empty means something.
     */
    @Test
    void aSettingIsThePropertyElseTheVariableAndEmptyIsUnset() {
        record Row(String key, String property, String variable, String expected) {
        }
        String unset = null;
        List<Row> rows = List.of(
                new Row("spidersense.slow.query.ms", "250", "50", "250"),
                new Row("spidersense.slow.query.ms", unset, "50", "50"),
                new Row("spidersense.slow.query.ms", "", "50", "50"),
                new Row("spidersense.slow.query.ms", "", "", unset),
                new Row("spidersense.slow.query.ms", unset, "", unset),
                new Row("spidersense.slow.query.ms", unset, unset, unset),
                new Row("spidersense.source.dirs", "from-property", "from-env", "from-property"),
                new Row("spidersense.source.dirs", unset, "from-env", "from-env"),
                new Row("spidersense.source.dirs", "", "from-env", ""),
                new Row("spidersense.source.dirs", unset, "", unset),
                new Row("spidersense.ignore.endpoints", "", "/internal/**", ""),
                new Row("spidersense.ignore.endpoints", unset, "", unset));
        for (Row row : rows) {
            Map<String, String> properties = new HashMap<>();
            if (row.property() != null) {
                properties.put(row.key(), row.property());
            }
            Map<String, String> env = new HashMap<>();
            if (row.variable() != null) {
                env.put(Config.envName(row.key()), row.variable());
            }
            assertThat(Config.propertyOrEnv(row.key(), properties::get, env::get)).as("%s", row)
                    .isEqualTo(row.expected());
        }
    }

    @Test
    void systemPropertiesAreRead() {
        Map<String, String> properties = Map.of(
                "spidersense.port", "4321",
                "spidersense.host", "0.0.0.0",
                "spidersense.collector", "http://elsewhere:4000/",
                "spidersense.service", "orders",
                "spidersense.db", "~/db/other/sense",
                "spidersense.retention.hours", "6",
                "spidersense.slow.request.ms", "13",
                "spidersense.slow.query.ms", "14",
                "spidersense.open", "true");

        Config c = Config.fromSystemProperties(properties::get, NONE::get);

        assertThat(c.port()).isEqualTo(4321);
        assertThat(c.host()).isEqualTo("0.0.0.0");
        assertThat(c.collector()).isEqualTo("http://elsewhere:4000/");
        assertThat(c.service()).isEqualTo("orders");
        assertThat(c.db()).isEqualTo("~/db/other/sense");
        assertThat(c.retentionHours()).isEqualTo(6);
        assertThat(c.slowRequestMs()).isEqualTo(13);
        assertThat(c.slowQueryMs()).isEqualTo(14);
        assertThat(c.open()).isTrue();
    }

    @Test
    void argumentsOverrideSystemProperties() {
        Map<String, String> properties = Map.of("spidersense.port", "4321", "spidersense.slow.query.ms", "14");

        Config c = Config.parse(new String[] {"--port=4005", "--host=0.0.0.0", "--open=true"},
                properties::get, NONE::get).config();

        assertThat(c.port()).isEqualTo(4005);
        assertThat(c.host()).isEqualTo("0.0.0.0");
        assertThat(c.open()).isTrue();
        assertThat(c.slowQueryMs()).as("untouched by arguments").isEqualTo(14);
    }

    @Test
    void bareFlagsAreIgnored() {
        Config c = config("--help", "-x", "--port=4100", "");
        assertThat(c.port()).isEqualTo(4100);
        assertThat(c).isEqualTo(Config.defaults().withPort(4100));
    }

    /** A mistyped key would otherwise take its default with nothing to say so. */
    @Test
    void anUnknownKeyIsAUsageError() {
        assertThatThrownBy(() -> parse("--prot=4100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown option: --prot; java -jar spider-sense.jar --help lists them");
    }

    /**
     * {@code --help} lists every documented key the standalone jar takes and points at the CLI's own
     * {@code help} for the commands rather than repeating a subset of them; and the jar accepts
     * each of them, with a value of the key's kind.
     */
    @Test
    void theHelpListsEveryKeyTheJarTakesAndNothingElse() {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stdout = System.out;
        System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            SpiderSenseMain.printHelp();
        } finally {
            System.setOut(stdout);
        }
        String help = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> keys = java.util.regex.Pattern.compile("(?m)^\\s+--([a-z.-]+)=").matcher(help)
                .results().map(m -> "spidersense." + m.group(1)).toList();

        assertThat(help).contains("java -jar spider-sense.jar help");
        assertThat(keys).containsExactlyInAnyOrderElementsOf(Key.documentedProperties());
        for (Key key : Key.values()) {
            String value = switch (key.kind()) {
                case INT, LONG -> "1";
                case BOOLEAN -> "true";
                case STRING -> "x";
            };
            assertThat(parse("--" + key.argument() + "=" + value)).as("%s", key).isNotNull();
        }
    }

    @Test
    void zeroZeroZeroZeroIsNotAnAddressToConnectTo() {
        assertThat(Config.defaults().baseUrl()).isEqualTo("http://127.0.0.1:4000");
        assertThat(config("--host=0.0.0.0", "--port=4010").baseUrl())
                .isEqualTo("http://127.0.0.1:4010");
        assertThat(config("--host=::1", "--port=4010").baseUrl())
                .as("an IPv6 address in brackets").isEqualTo("http://[::1]:4010");
        assertThat(config("--host=::", "--port=4010").baseUrl())
                .isEqualTo("http://127.0.0.1:4010");
    }

    @Test
    void theOtlpEndpointIsTheCollectorWhenForwarding() {
        assertThat(Config.defaults().otlpEndpoint()).isEqualTo("http://127.0.0.1:4000");
        assertThat(config("--collector=http://box:4000//").otlpEndpoint())
                .isEqualTo("http://box:4000");
    }

    @Test
    void serverArgumentsCarryEveryKeyTheServerKnows() {
        Config c = Config.defaults().withMode("standalone");

        assertThat(c.toServerArgs()).as("what the user did not set is the server's to default")
                .containsExactly(
                        "--port=4000",
                        "--host=127.0.0.1",
                        "--mode=standalone",
                        "--slow.request.ms=500",
                        "--slow.query.ms=100");

        assertThat(Config.defaults().withService("orders").toServerArgs())
                .as("an agent is embedded in the service").contains("--embedded-service=orders");
        assertThat(c.withService("orders").toServerArgs())
                .as("a standalone server is embedded in nothing")
                .noneMatch(arg -> arg.startsWith("--embedded-service"));
    }

    @Test
    void theDatabaseAndRetentionTravelOnlyWhenSet() {
        Config c = config("--db=jdbc:h2:mem:x", "--retention.hours=6");

        assertThat(c.toServerArgs()).contains("--db=jdbc:h2:mem:x", "--retention.hours=6");
        assertThat(config("--retention-hours=3").toServerArgs())
                .as("the dashed spelling is accepted too").contains("--retention.hours=3");
    }

    @Test
    void theDatabaseIsDescribedAsTheFileOrTheUrl() {
        assertThat(Config.defaults().databaseDescription()).isEqualTo("~/db/spider-sense/sense.mv.db");
        assertThat(Config.defaults().withDb("~/db/other/sense").databaseDescription())
                .isEqualTo("~/db/other/sense.mv.db");
        assertThat(Config.defaults().withDb("jdbc:h2:mem:it").databaseDescription())
                .isEqualTo("jdbc:h2:mem:it");
    }

    @Test
    void serverArgumentsRoundTripThroughFromArgs() {
        Config c = config(
                "--port=4321", "--host=0.0.0.0", "--service=orders",
                "--db=jdbc:h2:mem:it", "--retention.hours=6",
                "--slow.request.ms=13", "--slow.query.ms=14").withMode("agent");

        Config again = config(c.toServerArgs().toArray(new String[0]));

        assertThat(again).isEqualTo(c);
    }

    @Test
    void environmentVariableNamesFollowTheOpenTelemetrySpelling() {
        assertThat(Config.envName("otel.exporter.otlp.endpoint")).isEqualTo("OTEL_EXPORTER_OTLP_ENDPOINT");
        assertThat(Config.envName("otel.service.name")).isEqualTo("OTEL_SERVICE_NAME");
        assertThat(Config.envName("otel.javaagent.exclude-class-loaders"))
                .isEqualTo("OTEL_JAVAAGENT_EXCLUDE_CLASS_LOADERS");
        assertThat(Config.envName("otel.instrumentation.runtime-telemetry.enabled"))
                .isEqualTo("OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED");
        assertThat(Config.envName("otel.metric.export.interval")).isEqualTo("OTEL_METRIC_EXPORT_INTERVAL");
    }

    @Test
    void everyEnvironmentVariableWeConsultMapsBackToItsProperty() {
        // The names SpiderSenseAgent sets defaults for; the mapping is what decides whether we
        // leave a user's environment alone.
        Properties expected = new Properties();
        expected.setProperty("otel.exporter.otlp.protocol", "OTEL_EXPORTER_OTLP_PROTOCOL");
        expected.setProperty("otel.exporter.otlp.endpoint", "OTEL_EXPORTER_OTLP_ENDPOINT");
        expected.setProperty("otel.bsp.schedule.delay", "OTEL_BSP_SCHEDULE_DELAY");
        expected.setProperty("otel.blrp.schedule.delay", "OTEL_BLRP_SCHEDULE_DELAY");
        expected.setProperty("otel.traces.exporter", "OTEL_TRACES_EXPORTER");
        expected.setProperty("otel.metrics.exporter", "OTEL_METRICS_EXPORTER");
        expected.setProperty("otel.logs.exporter", "OTEL_LOGS_EXPORTER");
        expected.forEach((property, env) ->
                assertThat(Config.envName((String) property)).isEqualTo(env));
    }

    /**
     * The server's keys are handed back as the properties the caller sets, and set nothing
     * themselves.
     */
    @Test
    void serverOwnedArgumentsBecomeServerProperties() {
        Config.Parsed parsed = parse("--app.packages=com.acme,org.acme", "--ignore.endpoints=",
                "--retention.spans=250000", "--ingest.max-spans-per-second=5000", "--port=4100");

        assertThat(parsed.serverProperties()).containsOnly(
                Map.entry("spidersense.app.packages", "com.acme,org.acme"),
                // Kept empty, not dropped: an empty list means "ignore nothing" (configuration.adoc#ignored-endpoints).
                Map.entry("spidersense.ignore.endpoints", ""),
                Map.entry("spidersense.retention.spans", "250000"),
                Map.entry("spidersense.ingest.max-spans-per-second", "5000"));
        assertThat(parsed.config()).isEqualTo(Config.defaults().withPort(4100));
        assertThat(System.getProperty("spidersense.app.packages")).as("nothing set").isNull();
    }

    @Test
    void aMalformedNumberFallsBackAloneAndKeepsEveryOtherKey() {
        Map<String, String> properties = Map.of(
                "spidersense.collector", "http://127.0.0.1:4000",
                "spidersense.service", "orders",
                "spidersense.slow.query.ms", "1x",
                "spidersense.port", "abc",
                "spidersense.retention.hours", "a day",
                "spidersense.slow.request.ms", "250");

        Config.Parsed parsed = Config.parse(null, properties::get, NONE::get);
        Config c = parsed.config();

        assertThat(c.collector()).as("still forwarding").isEqualTo("http://127.0.0.1:4000");
        assertThat(c.service()).isEqualTo("orders");
        assertThat(c.slowQueryMs()).as("the bad key alone takes its default").isEqualTo(100);
        assertThat(c.port()).isEqualTo(4000);
        assertThat(c.retentionHours()).as("the default, passed on so the server does not warn again").isEqualTo(24);
        assertThat(c.slowRequestMs()).isEqualTo(250);
        assertThat(parsed.warnings()).containsExactlyInAnyOrder(
                "spidersense.port=abc is not a number; using 4000",
                "spidersense.retention.hours=a day is not a number; using 24",
                "spidersense.slow.query.ms=1x is not a number; using 100");
    }

    /** The one lookup over this JVM's own properties and environment, read and never written. */
    @Test
    void theSystemPropertyAndTheEnvironmentAreWhatTheLookupReads() {
        assertThat(Config.propertyOrEnv("java.version")).isEqualTo(System.getProperty("java.version"));
        assertThat(Config.propertyOrEnv("spidersense.definitely.not.set.anywhere")).isNull();
        Map<String, String> properties = Map.of("otel.exporter.otlp.protocol", "grpc");
        Map<String, String> env = Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        assertThat(Config.propertyOrEnv("otel.exporter.otlp.protocol", properties::get, env::get))
                .as("the property wins over the variable").isEqualTo("grpc");
    }

    @Test
    void anEnvironmentVariableIsFoundWithoutASystemProperty() {
        // PATH is set in every environment this can run in, and its property spelling is free.
        String value = System.getenv("PATH");
        assertThat(value).isNotNull();
        assertThat(Config.propertyOrEnv("path")).isEqualTo(value);
    }
}
