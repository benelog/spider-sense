package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

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
    void unknownArgumentsAndBareFlagsAreIgnored() {
        Config c = Config.fromArgs(new String[] {"--help", "-x", "--nonsense=1", "--port=4100", ""});
        assertThat(c.port()).isEqualTo(4100);
        assertThat(c).isEqualTo(Config.defaults().withPort(4100));
    }

    @Test
    void zeroZeroZeroZeroIsNotAnAddressToConnectTo() {
        assertThat(Config.defaults().baseUrl()).isEqualTo("http://127.0.0.1:4000");
        assertThat(Config.fromArgs(new String[] {"--host=0.0.0.0", "--port=4010"}).baseUrl())
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

        assertThat(c.withService("orders").toServerArgs()).contains("--embedded-service=orders");
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
                "--slow.request.ms=13", "--slow.query.ms=14"}).withMode("standalone");

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

        Config.fromArgs(new String[] {"--app.packages=com.acme,org.acme", "--ignore.endpoints="});

        assertThat(System.getProperty("spidersense.app.packages")).isEqualTo("com.acme,org.acme");
        // Kept empty, not dropped: an empty list means "ignore nothing" (design.md).
        assertThat(System.getProperty("spidersense.ignore.endpoints")).isEmpty();
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
