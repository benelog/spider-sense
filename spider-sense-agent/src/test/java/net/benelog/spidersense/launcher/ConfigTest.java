package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private final List<String> touched = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        touched.forEach(System::clearProperty);
        touched.clear();
    }

    private void set(String key, String value) {
        touched.add(key);
        System.setProperty(key, value);
    }

    /** A malformed number on the command line is one usage line, not a stack trace. */
    @Test
    void aMalformedNumberArgumentSaysWhichAndWhat() {
        assertThatThrownBy(() -> Config.fromArgs(new String[] {"--port=abc"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--port is not a number: abc");
        assertThatThrownBy(() -> Config.fromArgs(new String[] {"--slow.query.ms=1x"}))
                .hasMessage("--slow.query.ms is not a number: 1x");
        assertThatThrownBy(() -> Config.fromArgs(new String[] {"--retention.spans=1x"}))
                .as("a key the server owns is checked before it is forwarded")
                .hasMessage("--retention.spans is not a number: 1x");
        assertThatThrownBy(() -> Config.fromArgs(new String[] {"--ingest.max-spans-per-second=abc"}))
                .hasMessage("--ingest.max-spans-per-second is not a number: abc");
        assertThat(System.getProperty("spidersense.retention.spans")).as("nothing forwarded").isNull();
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
        assertThat(Config.fromSystemProperties()).isEqualTo(Config.defaults());
    }

    @Test
    void environmentVariablesAreReadBelowSystemProperties() {
        java.util.Map<String, String> env = java.util.Map.of(
                "SPIDERSENSE_PORT", "4001",
                "SPIDERSENSE_COLLECTOR", "http://127.0.0.1:4000",
                "SPIDERSENSE_SLOW_QUERY_MS", "25");
        java.util.Map<String, String> properties = java.util.Map.of("spidersense.slow.query.ms", "30");

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
        java.util.Map<String, String> properties = java.util.Map.of(
                "spidersense.slow.query.ms", "", "spidersense.port", "");
        java.util.Map<String, String> env = java.util.Map.of("SPIDERSENSE_SLOW_QUERY_MS", "50");

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
            java.util.Map<String, String> properties = new java.util.HashMap<>();
            if (row.property() != null) {
                properties.put(row.key(), row.property());
            }
            java.util.Map<String, String> env = new java.util.HashMap<>();
            if (row.variable() != null) {
                env.put(Config.envName(row.key()), row.variable());
            }
            assertThat(Config.propertyOrEnv(row.key(), properties::get, env::get)).as("%s", row)
                    .isEqualTo(row.expected());
        }
    }

    @Test
    void systemPropertiesAreRead() {
        set("spidersense.port", "4321");
        set("spidersense.host", "0.0.0.0");
        set("spidersense.collector", "http://elsewhere:4000/");
        set("spidersense.service", "orders");
        set("spidersense.db", "~/db/other/sense");
        set("spidersense.retention.hours", "6");
        set("spidersense.slow.request.ms", "13");
        set("spidersense.slow.query.ms", "14");
        set("spidersense.open", "true");

        Config c = Config.fromSystemProperties();

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
        set("spidersense.port", "4321");
        set("spidersense.slow.query.ms", "14");

        Config c = Config.fromArgs(new String[] {"--port=4005", "--host=0.0.0.0", "--open=true"});

        assertThat(c.port()).isEqualTo(4005);
        assertThat(c.host()).isEqualTo("0.0.0.0");
        assertThat(c.open()).isTrue();
        assertThat(c.slowQueryMs()).as("untouched by arguments").isEqualTo(14);
    }

    @Test
    void bareFlagsAreIgnored() {
        Config c = Config.fromArgs(new String[] {"--help", "-x", "--port=4100", ""});
        assertThat(c.port()).isEqualTo(4100);
        assertThat(c).isEqualTo(Config.defaults().withPort(4100));
    }

    /** A mistyped key would otherwise take its default with nothing to say so. */
    @Test
    void anUnknownKeyIsAUsageError() {
        assertThatThrownBy(() -> Config.fromArgs(new String[] {"--prot=4100"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown option: --prot; java -jar spider-sense.jar --help lists them");
    }

    /**
     * {@code --help} lists every key the standalone jar takes, each of which it accepts, and points
     * at the CLI's own {@code help} for the commands rather than repeating a subset of them.
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
                .results().map(m -> m.group(1)).toList();

        assertThat(help).contains("java -jar spider-sense.jar help");
        assertThat(keys).containsExactlyInAnyOrder("port", "host", "collector", "service", "db",
                "retention.hours", "retention.spans", "ingest.max-spans-per-second",
                "slow.request.ms", "slow.query.ms", "app.packages", "ignore.endpoints",
                "source.dirs", "open");
        for (String key : keys) {
            touched.add("spidersense." + key);
            String value = key.equals("open") ? "true" : key.contains(".ms") || key.startsWith("retention")
                    || key.startsWith("ingest") || key.equals("port") ? "1" : "x";
            Config.fromArgs(new String[] {"--" + key + "=" + value});
        }
    }

    @Test
    void zeroZeroZeroZeroIsNotAnAddressToConnectTo() {
        assertThat(Config.defaults().baseUrl()).isEqualTo("http://127.0.0.1:4000");
        assertThat(Config.fromArgs(new String[] {"--host=0.0.0.0", "--port=4010"}).baseUrl())
                .isEqualTo("http://127.0.0.1:4010");
        assertThat(Config.fromArgs(new String[] {"--host=::1", "--port=4010"}).baseUrl())
                .as("an IPv6 address in brackets").isEqualTo("http://[::1]:4010");
        assertThat(Config.fromArgs(new String[] {"--host=::", "--port=4010"}).baseUrl())
                .isEqualTo("http://127.0.0.1:4010");
    }

    @Test
    void theOtlpEndpointIsTheCollectorWhenForwarding() {
        assertThat(Config.defaults().otlpEndpoint()).isEqualTo("http://127.0.0.1:4000");
        assertThat(Config.fromArgs(new String[] {"--collector=http://box:4000//"}).otlpEndpoint())
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
        Config c = Config.fromArgs(new String[] {"--db=jdbc:h2:mem:x", "--retention.hours=6"});

        assertThat(c.toServerArgs()).contains("--db=jdbc:h2:mem:x", "--retention.hours=6");
        assertThat(Config.fromArgs(new String[] {"--retention-hours=3"}).toServerArgs())
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
        Config c = Config.fromArgs(new String[] {
                "--port=4321", "--host=0.0.0.0", "--service=orders",
                "--db=jdbc:h2:mem:it", "--retention.hours=6",
                "--slow.request.ms=13", "--slow.query.ms=14"}).withMode("agent");

        Config again = Config.fromArgs(c.toServerArgs().toArray(new String[0]));

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

    @Test
    void serverOwnedArgumentsBecomeSystemProperties() {
        touched.add("spidersense.app.packages");
        touched.add("spidersense.ignore.endpoints");
        touched.add("spidersense.retention.spans");
        touched.add("spidersense.ingest.max-spans-per-second");

        Config.fromArgs(new String[] {"--app.packages=com.acme,org.acme", "--ignore.endpoints=",
                "--retention.spans=250000", "--ingest.max-spans-per-second=5000"});

        assertThat(System.getProperty("spidersense.app.packages")).isEqualTo("com.acme,org.acme");
        // Kept empty, not dropped: an empty list means "ignore nothing" (configuration.adoc#ignored-endpoints).
        assertThat(System.getProperty("spidersense.ignore.endpoints")).isEmpty();
        assertThat(System.getProperty("spidersense.retention.spans")).isEqualTo("250000");
        assertThat(System.getProperty("spidersense.ingest.max-spans-per-second")).isEqualTo("5000");
    }

    @Test
    void aMalformedNumberFallsBackAloneAndKeepsEveryOtherKey() {
        set("spidersense.collector", "http://127.0.0.1:4000");
        set("spidersense.service", "orders");
        set("spidersense.slow.query.ms", "1x");
        set("spidersense.port", "abc");
        set("spidersense.retention.hours", "a day");
        set("spidersense.slow.request.ms", "250");

        Config c = Config.fromSystemProperties();

        assertThat(c.collector()).as("still forwarding").isEqualTo("http://127.0.0.1:4000");
        assertThat(c.service()).isEqualTo("orders");
        assertThat(c.slowQueryMs()).as("the bad key alone takes its default").isEqualTo(100);
        assertThat(c.port()).isEqualTo(4000);
        assertThat(c.retentionHours()).as("the default, passed on so the server does not warn again").isEqualTo(24);
        assertThat(c.slowRequestMs()).isEqualTo(250);
    }

    @Test
    void theSystemPropertyWinsOverTheEnvironmentVariable() {
        set("otel.exporter.otlp.protocol", "grpc");
        assertThat(Config.propertyOrEnv("otel.exporter.otlp.protocol")).isEqualTo("grpc");
        assertThat(Config.propertyOrEnv("spidersense.definitely.not.set.anywhere")).isNull();
    }

    @Test
    void anEnvironmentVariableIsFoundWithoutASystemProperty() {
        // PATH is set in every environment this can run in, and its property spelling is free.
        String value = System.getenv("PATH");
        assertThat(value).isNotNull();
        assertThat(Config.propertyOrEnv("path")).isEqualTo(value);
    }
}
