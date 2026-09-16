package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentDefaultsTest {

    private static final List<String> KEYS = List.of(
            "otel.exporter.otlp.protocol",
            "otel.exporter.otlp.endpoint",
            "otel.service.name",
            "otel.bsp.schedule.delay",
            "otel.blrp.schedule.delay",
            "otel.metric.export.interval",
            "otel.traces.exporter",
            "otel.metrics.exporter",
            "otel.logs.exporter",
            "otel.instrumentation.runtime-telemetry.enabled",
            "otel.javaagent.exclude-class-loaders");

    @AfterEach
    void clear() {
        KEYS.forEach(System::clearProperty);
    }

    @Test
    void fillsInTheDefaultsForALocalTool() {
        SpiderSenseAgent.applyOtelDefaults(Config.defaults().withPort(4010));

        assertThat(System.getProperty("otel.exporter.otlp.protocol")).isEqualTo("http/protobuf");
        assertThat(System.getProperty("otel.exporter.otlp.endpoint")).isEqualTo("http://127.0.0.1:4010");
        assertThat(System.getProperty("otel.bsp.schedule.delay")).isEqualTo("1000");
        assertThat(System.getProperty("otel.blrp.schedule.delay")).isEqualTo("1000");
        assertThat(System.getProperty("otel.metric.export.interval")).isEqualTo("5000");
        assertThat(System.getProperty("otel.traces.exporter")).isEqualTo("otlp");
        assertThat(System.getProperty("otel.metrics.exporter")).isEqualTo("otlp");
        assertThat(System.getProperty("otel.logs.exporter")).isEqualTo("otlp");
        assertThat(System.getProperty("otel.instrumentation.runtime-telemetry.enabled")).isEqualTo("true");
        assertThat(System.getProperty("otel.javaagent.exclude-class-loaders"))
                .isEqualTo("net.benelog.spidersense.launcher.SenseClassLoader");
        assertThat(System.getProperty("otel.service.name")).as("left to the agent's own default").isNull();
    }

    @Test
    void exportsToTheCollectorWhenForwarding() {
        SpiderSenseAgent.applyOtelDefaults(
                Config.fromArgs(new String[] {"--collector=http://box:4000/", "--service=orders"}));

        assertThat(System.getProperty("otel.exporter.otlp.endpoint")).isEqualTo("http://box:4000");
        assertThat(System.getProperty("otel.service.name")).isEqualTo("orders");
    }

    @Test
    void neverOverridesWhatTheUserSet() {
        System.setProperty("otel.exporter.otlp.protocol", "grpc");
        System.setProperty("otel.traces.exporter", "none");

        SpiderSenseAgent.applyOtelDefaults(Config.defaults());

        assertThat(System.getProperty("otel.exporter.otlp.protocol")).isEqualTo("grpc");
        assertThat(System.getProperty("otel.traces.exporter")).isEqualTo("none");
    }

    @Test
    void addsToAUserExclusionListInsteadOfReplacingIt() {
        System.setProperty("otel.javaagent.exclude-class-loaders", "com.example.Loader");

        SpiderSenseAgent.applyOtelDefaults(Config.defaults());

        assertThat(System.getProperty("otel.javaagent.exclude-class-loaders"))
                .isEqualTo("com.example.Loader,net.benelog.spidersense.launcher.SenseClassLoader");

        // And is idempotent.
        SpiderSenseAgent.applyOtelDefaults(Config.defaults());
        assertThat(System.getProperty("otel.javaagent.exclude-class-loaders"))
                .isEqualTo("com.example.Loader,net.benelog.spidersense.launcher.SenseClassLoader");
    }
}
