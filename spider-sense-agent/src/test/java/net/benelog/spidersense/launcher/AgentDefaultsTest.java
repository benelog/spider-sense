package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The OpenTelemetry defaults, filled into a map rather than this JVM's system properties. */
class AgentDefaultsTest {

    @TempDir
    Path dir;

    /** The properties the defaults are written to. */
    private final Map<String, String> properties = new HashMap<>();

    /** The environment behind them. */
    private final Map<String, String> env = new HashMap<>();

    private final Settings settings = Settings.of(properties::get, env::get, properties::put);

    /** Tests run from exploded classes; production finds the extension with NestedJar.extensionJar. */
    private Path extensionJar() {
        return dir.resolve("extension.jar");
    }

    private void applyWithoutExtension(Config config) {
        SpiderSenseAgent.applyOtelDefaults(config, settings, () -> null);
    }

    private void applyWithExtension(Config config) {
        SpiderSenseAgent.applyOtelDefaults(config, settings, this::extensionJar);
    }

    /**
     * The Gradle plugin always passes spidersense.service, and a build may set otel.service.name
     * as well: the exporter names the latter, so the embedded collector must too.
     */
    @Test
    void theEmbeddedServiceIsTheOneTheExporterNames() {
        Config config = Config.defaults().withService("orders-project");
        assertThat(SpiderSenseAgent.effectiveServiceName(config, settings)).isEqualTo("orders-project");

        env.put("OTEL_SERVICE_NAME", "orders-worker");
        assertThat(SpiderSenseAgent.effectiveServiceName(config, settings)).isEqualTo("orders-worker");

        properties.put("otel.service.name", "orders-api");
        assertThat(SpiderSenseAgent.effectiveServiceName(config, settings)).isEqualTo("orders-api");
    }

    /** Only a Spider Sense on the port is worth exporting to; anything else is foreign. */
    @Test
    void aPortIsAnotherSpiderSensesOnlyWhenItsStatusSaysSo() throws IOException {
        com.sun.net.httpserver.HttpServer other = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        String[] status = {"{\"name\": \"Spider Sense\", \"mode\": \"agent\"}"};
        other.createContext("/", exchange -> {
            byte[] body = status[0].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        other.start();
        try {
            String base = "http://127.0.0.1:" + other.getAddress().getPort();
            assertThat(SpiderSenseAgent.spiderSenseAt(base)).isTrue();

            status[0] = "<html>a web server of some other kind</html>";
            assertThat(SpiderSenseAgent.spiderSenseAt(base)).isFalse();
        } finally {
            other.stop(0);
        }
        assertThat(SpiderSenseAgent.portInUse(
                new IllegalStateException("Failed to start Jetty", new java.net.BindException("in use")))).isTrue();
        assertThat(SpiderSenseAgent.portInUse(new IllegalStateException("no"))).isFalse();
    }

    @Test
    void fillsInTheDefaultsForALocalTool() {
        applyWithoutExtension(Config.defaults().withPort(4010));

        assertThat(properties).containsExactlyInAnyOrderEntriesOf(Map.of(
                "otel.exporter.otlp.protocol", "http/protobuf",
                "otel.exporter.otlp.endpoint", "http://127.0.0.1:4010",
                "otel.bsp.schedule.delay", "1000",
                "otel.blrp.schedule.delay", "1000",
                "otel.metric.export.interval", "5000",
                "otel.traces.exporter", "otlp",
                "otel.metrics.exporter", "otlp",
                "otel.logs.exporter", "otlp",
                "otel.instrumentation.runtime-telemetry.enabled", "true",
                "otel.javaagent.exclude-class-loaders", "net.benelog.spidersense.launcher.SenseClassLoader"));
        assertThat(properties).as("otel.service.name is left to the agent's own default, and with no"
                + " extension to point at, otel.javaagent.extensions is not an error but unset")
                .doesNotContainKeys("otel.service.name", "otel.javaagent.extensions");
    }

    @Test
    void pointsTheAgentAtOurOwnExtension() {
        applyWithExtension(Config.defaults());

        assertThat(properties).containsEntry("otel.javaagent.extensions", extensionJar().toString());
    }

    @Test
    void aMissingExtensionIsAWarningAndTheOtherDefaultsStand() {
        SpiderSenseAgent.applyOtelDefaults(Config.defaults(), settings, () -> {
            throw new IOException("spidersense.extensionJar points at a file that does not exist");
        });

        assertThat(properties).doesNotContainKey("otel.javaagent.extensions")
                .containsEntry("otel.traces.exporter", "otlp");
    }

    @Test
    void addsToAUserExtensionListInsteadOfReplacingIt() {
        properties.put("otel.javaagent.extensions", "/opt/acme/their-extension.jar");

        applyWithExtension(Config.defaults());

        assertThat(properties).containsEntry("otel.javaagent.extensions",
                "/opt/acme/their-extension.jar," + extensionJar());

        // And is idempotent.
        applyWithExtension(Config.defaults());
        assertThat(properties).containsEntry("otel.javaagent.extensions",
                "/opt/acme/their-extension.jar," + extensionJar());
    }

    @Test
    void exportsToTheCollectorWhenForwarding() {
        applyWithoutExtension(
                Config.parse(new String[] {"--collector=http://box:4000/", "--service=orders"},
                        key -> null, key -> null).config());

        assertThat(properties).containsEntry("otel.exporter.otlp.endpoint", "http://box:4000")
                .containsEntry("otel.service.name", "orders");
    }

    @Test
    void neverOverridesWhatTheUserSet() {
        properties.put("otel.exporter.otlp.protocol", "grpc");
        env.put("OTEL_TRACES_EXPORTER", "none");

        applyWithoutExtension(Config.defaults());

        assertThat(properties).containsEntry("otel.exporter.otlp.protocol", "grpc")
                .as("the variable is what the agent reads").doesNotContainKey("otel.traces.exporter");
    }

    @Test
    void addsToAUserExclusionListInsteadOfReplacingIt() {
        env.put("OTEL_JAVAAGENT_EXCLUDE_CLASS_LOADERS", "com.example.Loader");

        applyWithoutExtension(Config.defaults());

        assertThat(properties).containsEntry("otel.javaagent.exclude-class-loaders",
                "com.example.Loader,net.benelog.spidersense.launcher.SenseClassLoader");

        // And is idempotent.
        applyWithoutExtension(Config.defaults());
        assertThat(properties).containsEntry("otel.javaagent.exclude-class-loaders",
                "com.example.Loader,net.benelog.spidersense.launcher.SenseClassLoader");
    }
}
