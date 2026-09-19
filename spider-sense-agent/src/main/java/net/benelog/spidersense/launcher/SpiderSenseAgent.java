package net.benelog.spidersense.launcher;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;

/**
 * The {@code Premain-Class}/{@code Agent-Class} of the distributable jar.
 *
 * <p>It does the four steps of {@code docs/design.md}, in order: read the configuration (the
 * properties file, then the system properties), start the embedded collector + UI unless we are forwarding, fill in the OpenTelemetry defaults a local tool
 * wants, then hand over to the stock agent's own {@code premain}.
 *
 * <p>Nothing here may stop the monitored application from starting, so every step is wrapped: a
 * failure becomes one line on stderr and is swallowed.
 */
public final class SpiderSenseAgent {

    static final String PREFIX = "[spider-sense] ";
    static final String OTEL_AGENT_CLASS = "io.opentelemetry.javaagent.OpenTelemetryAgent";

    private SpiderSenseAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, "agentmain");
    }

    private static synchronized void install(String agentArgs, Instrumentation inst, String phase) {
        Config config = Config.defaults();

        // 1. Configuration: the properties file first, so that the system properties it fills in
        // are read exactly as the ones from the command line.
        try {
            Path file = ConfigFile.apply();
            if (file != null) {
                System.out.println(PREFIX + "configuration: " + file.toAbsolutePath());
            }
            config = Config.fromSystemProperties().withMode(Config.AGENT);
        } catch (Throwable t) {
            warn("could not read the spidersense.* properties, using defaults", t);
        }

        // 2. The embedded collector + UI, unless we forward to one elsewhere.
        try {
            if (config.collector() != null) {
                System.out.println(PREFIX + "forwarding to " + config.otlpEndpoint());
            } else {
                Config serverConfig = config.withService(effectiveServiceName(config));
                if (EmbeddedServer.start(serverConfig)) {
                    System.out.println(PREFIX + "UI: " + config.baseUrl());
                    maybeOpenBrowser(config);
                }
            }
        } catch (Throwable t) {
            warn("the embedded UI did not start; the application is unaffected", t);
        }

        // 3. The OpenTelemetry defaults, never overriding what the user already set.
        try {
            applyOtelDefaults(config);
        } catch (Throwable t) {
            warn("could not set the OpenTelemetry defaults", t);
        }

        // 4. The stock OpenTelemetry agent. Reflectively, so this launcher compiles with nothing on
        // its class path; the class is in the same jar, next to us.
        try {
            Class<?> agent = Class.forName(OTEL_AGENT_CLASS);
            Method entry = agent.getMethod(phase, String.class, Instrumentation.class);
            entry.invoke(null, agentArgs, inst);
        } catch (Throwable t) {
            warn("the OpenTelemetry agent did not install; the application runs uninstrumented", t);
        }
    }

    /**
     * What the embedded collector should call itself: {@code spidersense.service} when given, else
     * whatever the user already told OpenTelemetry. The collector uses it to recognise the service
     * it is embedded in and drop the UI's own traffic.
     */
    private static String effectiveServiceName(Config config) {
        return config.service() != null ? config.service() : Config.propertyOrEnv("otel.service.name");
    }

    static void applyOtelDefaults(Config config) {
        setDefault("otel.exporter.otlp.protocol", "http/protobuf");
        setDefault("otel.exporter.otlp.endpoint", config.otlpEndpoint());
        if (config.service() != null) {
            // Otherwise the agent's own default (unknown_service:java) stands, and the UI shows the
            // main-class hint from the resource attributes instead.
            setDefault("otel.service.name", config.service());
        }
        // A local tool should show a request within a second or two.
        setDefault("otel.bsp.schedule.delay", "1000");
        setDefault("otel.blrp.schedule.delay", "1000");
        setDefault("otel.metric.export.interval", "5000");
        setDefault("otel.traces.exporter", "otlp");
        setDefault("otel.metrics.exporter", "otlp");
        setDefault("otel.logs.exporter", "otlp");
        setDefault("otel.instrumentation.runtime-telemetry.enabled", "true");
        excludeOurClassLoader();
        addOurExtension();
    }

    /**
     * Points the agent at {@code spider-sense/extension.jar}, the one piece of instrumentation that
     * is ours: it gives a slow database span the stack it was issued from (design.md).
     *
     * <p>Not having it is a warning and no more. A finding without a code location is still a
     * finding, and nothing here may stand between the application and its {@code main}.
     */
    private static void addOurExtension() {
        try {
            Path extension = NestedJar.extensionJar();
            if (extension != null) {
                addExtension(extension.toString());
            }
        } catch (Throwable t) {
            warn("the stack-trace extension is not available; findings will have no code location "
                    + "for slow queries", t);
        }
    }

    /**
     * Appends a path to {@code otel.javaagent.extensions}, the same shape as the exclusion list: a
     * user who names extensions of their own keeps them.
     */
    static void addExtension(String path) {
        String key = "otel.javaagent.extensions";
        String existing = Config.propertyOrEnv(key);
        if (existing == null) {
            System.setProperty(key, path);
        } else if (!existing.contains(path)) {
            System.setProperty(key, existing + "," + path);
        }
    }

    /**
     * Keeps the UI's own Jetty out of the data: the agent instruments nothing a
     * {@link SenseClassLoader} defined. A user list is added to rather than replaced, because
     * dropping their exclusions would be a surprise and dropping ours would show the UI monitoring
     * itself.
     */
    private static void excludeOurClassLoader() {
        String key = "otel.javaagent.exclude-class-loaders";
        String ours = SenseClassLoader.class.getName();
        String existing = Config.propertyOrEnv(key);
        if (existing == null) {
            System.setProperty(key, ours);
        } else if (!existing.contains(ours)) {
            System.setProperty(key, existing + "," + ours);
        }
    }

    /** Sets a system property only when neither it nor its environment variable is set already. */
    static void setDefault(String property, String value) {
        if (value != null && Config.propertyOrEnv(property) == null) {
            System.setProperty(property, value);
        }
    }

    private static void maybeOpenBrowser(Config config) {
        if (!config.open()) {
            return;
        }
        String url = config.baseUrl();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1500);
                // Reflectively: java.desktop may not be in the image, and a headless JVM throws.
                Class<?> desktop = Class.forName("java.awt.Desktop");
                Object instance = desktop.getMethod("getDesktop").invoke(null);
                desktop.getMethod("browse", URI.class).invoke(instance, URI.create(url));
            } catch (Throwable ignored) {
                // Best effort, as documented.
            }
        }, "spider-sense-open");
        t.setDaemon(true);
        t.start();
    }

    static void warn(String what, Throwable t) {
        System.err.println(PREFIX + what + ": " + t);
    }
}
