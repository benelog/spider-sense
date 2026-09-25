package net.benelog.spidersense.launcher;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Premain-Class}/{@code Agent-Class} of the distributable jar.
 *
 * <p>It does the four steps of {@code design.adoc#premain}, in order: read the configuration (the
 * properties file, then the system properties), start the embedded collector + UI unless we are forwarding, fill in the OpenTelemetry defaults a local tool
 * wants, then hand over to the stock agent's own {@code premain}.
 *
 * <p>Nothing here may stop the monitored application from starting, so every step is wrapped: a
 * failure becomes one line on stderr and is swallowed.
 */
public final class SpiderSenseAgent {

    static final String LOG_PREFIX = "[spider-sense] ";
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
        Settings settings = Settings.SYSTEM;

        // 1. Configuration: the properties file first, so that the system properties it fills in
        // are read exactly as the ones from the command line.
        try {
            Path file = ConfigFile.apply();
            if (file != null) {
                System.out.println(LOG_PREFIX + "configuration: " + file.toAbsolutePath());
            }
            config = Config.fromSystemProperties().withMode(Config.AGENT);
        } catch (Throwable t) {
            warn("could not read the spidersense.* properties, using defaults", t);
        }

        // 2. The embedded collector + UI, unless we forward to one elsewhere.
        boolean exportNowhere = false;
        try {
            if (config.collector() != null) {
                System.out.println(LOG_PREFIX + "forwarding to " + config.otlpEndpoint());
            } else {
                Config serverConfig = config.withService(effectiveServiceName(config, settings));
                if (EmbeddedServer.start(serverConfig)) {
                    System.out.println(LOG_PREFIX + "UI: " + config.baseUrl());
                    maybeOpenBrowser(config);
                }
            }
        } catch (Throwable t) {
            warn("the embedded UI did not start; the application is unaffected", t);
            try {
                exportNowhere = portInUse(t) && !spiderSenseAt(config.baseUrl());
                if (exportNowhere) {
                    System.err.println(LOG_PREFIX + "port " + config.port() + " is held by something that is not"
                            + " Spider Sense; telemetry is not exported. Set -Dspidersense.port= to a free port.");
                }
            } catch (Throwable probe) {
                warn("could not tell what holds the port", probe);
            }
        }

        // 3. The OpenTelemetry defaults, never overriding what the user already set.
        try {
            if (exportNowhere) {
                // Set first, so the otlp defaults below find them set: exporting to a foreign
                // port would fail on every interval for the life of the process.
                setDefault(settings, "otel.traces.exporter", "none");
                setDefault(settings, "otel.metrics.exporter", "none");
                setDefault(settings, "otel.logs.exporter", "none");
            }
            applyOtelDefaults(config, settings, NestedJar::extensionJar);
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
     * What the embedded collector should call itself: the service the exporter will name, which is
     * {@code otel.service.name} when the user set it, since {@code spidersense.service} is only its
     * default, and {@code spidersense.service} otherwise. The collector uses it to recognise the
     * service it is embedded in, drop the UI's own traffic and mark its starts.
     */
    static @Nullable String effectiveServiceName(Config config, Settings settings) {
        String told = settings.get("otel.service.name");
        return told != null ? told : config.service();
    }

    /**
     * Fills in the OpenTelemetry defaults a local tool wants, each only where {@code settings} has
     * nothing yet, and adds our class loader and our extension to the agent's lists.
     *
     * @param extensionJar where our extension is on disk, or {@code null} when there is none; in
     *                     production {@link NestedJar#extensionJar()}
     */
    static void applyOtelDefaults(Config config, Settings settings, Callable<@Nullable Path> extensionJar) {
        setDefault(settings, "otel.exporter.otlp.protocol", "http/protobuf");
        setDefault(settings, "otel.exporter.otlp.endpoint", config.otlpEndpoint());
        if (config.service() != null) {
            // Otherwise the agent's own default (unknown_service:java) stands, and the UI shows the
            // main-class hint from the resource attributes instead.
            setDefault(settings, "otel.service.name", config.service());
        }
        // A local tool should show a request within a second or two.
        setDefault(settings, "otel.bsp.schedule.delay", "1000");
        setDefault(settings, "otel.blrp.schedule.delay", "1000");
        setDefault(settings, "otel.metric.export.interval", "5000");
        setDefault(settings, "otel.traces.exporter", "otlp");
        setDefault(settings, "otel.metrics.exporter", "otlp");
        setDefault(settings, "otel.logs.exporter", "otlp");
        setDefault(settings, "otel.instrumentation.runtime-telemetry.enabled", "true");
        excludeOurClassLoader(settings);
        addOurExtension(settings, extensionJar);
    }

    /**
     * Points the agent at {@code spider-sense/extension.jar}, the one piece of instrumentation that
     * is ours: it gives a slow database span the stack it was issued from (design.adoc#extension).
     *
     * <p>Not having it is a warning and no more. A finding without a code location is still a
     * finding, and nothing here may stand between the application and its {@code main}.
     */
    private static void addOurExtension(Settings settings, Callable<@Nullable Path> extensionJar) {
        try {
            Path extension = extensionJar.call();
            if (extension != null) {
                addExtension(settings, extension.toString());
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
    static void addExtension(Settings settings, String path) {
        appendToList(settings, "otel.javaagent.extensions", path);
    }

    /**
     * Keeps the UI's own Jetty out of the data: the agent instruments nothing a
     * {@link SenseClassLoader} defined. A user list is added to rather than replaced, because
     * dropping their exclusions would be a surprise and dropping ours would show the UI monitoring
     * itself.
     */
    private static void excludeOurClassLoader(Settings settings) {
        appendToList(settings, "otel.javaagent.exclude-class-loaders", SenseClassLoader.class.getName());
    }

    /**
     * Adds {@code item} to the comma-separated list in {@code key}, read from the property or its
     * environment variable, unless it is there already; an unset list becomes {@code item} alone.
     */
    static void appendToList(Settings settings, String key, String item) {
        String existing = settings.get(key);
        if (existing == null) {
            settings.set(key, item);
        } else if (!existing.contains(item)) {
            settings.set(key, existing + "," + item);
        }
    }

    /** Sets a property only when neither it nor its environment variable is set already. */
    static void setDefault(Settings settings, String property, @Nullable String value) {
        if (value != null && settings.get(property) == null) {
            settings.set(property, value);
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
        Throwable cause = t.getCause();
        // The cause says why: "Failed to start Jetty on port 4000" alone does not name the BindException.
        System.err.println(LOG_PREFIX + what + ": " + t + (cause != null ? " (" + cause + ")" : ""));
    }

    /** Whether the embedded server failed because its port was taken. */
    static boolean portInUse(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a Spider Sense answers at {@code baseUrl}: then the port is another application's
     * embedded one, and exporting to it is what the user wants (modes.adoc#troubleshooting).
     */
    static boolean spiderSenseAt(String baseUrl) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    URI.create(baseUrl + "/api/status").toURL().openConnection();
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(1_000);
            try (java.io.InputStream in = connection.getInputStream()) {
                String body = new String(in.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
                return connection.getResponseCode() == 200 && body.replace(" ", "").contains("\"name\":\"SpiderSense\"");
            } finally {
                connection.disconnect();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }
}
