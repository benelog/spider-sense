package net.benelog.spidersense.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Spider Sense configuration, exactly the table in {@code docs/design.md} plus the mode.
 *
 * <p>In agent mode system properties are the only channel there is, because {@code premain} runs
 * before the application's {@code main}; the standalone jar takes the same keys without the
 * {@code spidersense.} prefix as {@code --key=value} arguments, which win over the properties.
 *
 * <p>{@code db} and {@code retentionHours} are {@code null} when the user said nothing, and are
 * then left out of {@link #toServerArgs()} entirely: their defaults belong to the server, which
 * owns the database (see {@code docs/storage.md}), and repeating them here would mean two places
 * to change.
 */
public record Config(
        int port,
        String host,
        @Nullable String collector,
        @Nullable String service,
        @Nullable String db,
        @Nullable Integer retentionHours,
        long slowRequestMs,
        long slowQueryMs,
        boolean open,
        String mode) {

    public static final String AGENT = "agent";
    public static final String STANDALONE = "standalone";

    public static final int DEFAULT_PORT = 4000;
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final long DEFAULT_SLOW_REQUEST_MS = 500;
    public static final long DEFAULT_SLOW_QUERY_MS = 100;
    /** What the server opens when {@code spidersense.db} is unset; shown, never passed. */
    public static final String DEFAULT_DB = "~/db/spider-sense/sense";
    /** What the server sweeps to when {@code spidersense.retention.hours} is unset; shown, never passed. */
    public static final int DEFAULT_RETENTION_HOURS = 24;

    /** Every value at its documented default, mode {@code agent}. */
    public static Config defaults() {
        return new Config(DEFAULT_PORT, DEFAULT_HOST, null, null, null, null,
                DEFAULT_SLOW_REQUEST_MS, DEFAULT_SLOW_QUERY_MS, false, AGENT);
    }

    /** Reads the {@code spidersense.*} system properties, falling back to the defaults. */
    public static Config fromSystemProperties() {
        Config d = defaults();
        return new Config(
                intProperty("spidersense.port", d.port()),
                stringProperty("spidersense.host", d.host()),
                optionalProperty("spidersense.collector"),
                optionalProperty("spidersense.service"),
                optionalProperty("spidersense.db"),
                integerProperty("spidersense.retention.hours"),
                longProperty("spidersense.slow.request.ms", d.slowRequestMs()),
                longProperty("spidersense.slow.query.ms", d.slowQueryMs()),
                booleanProperty("spidersense.open", d.open()),
                stringProperty("spidersense.mode", d.mode()));
    }

    /**
     * The system properties overridden by {@code --key=value} arguments; the keys are those of the
     * table without the {@code spidersense.} prefix, e.g. {@code --port=4001}.
     * Unknown keys and bare flags are ignored so {@code --help} and {@code --version} can be
     * handled by the caller. {@code --app.packages}, {@code --ignore.endpoints},
     * {@code --retention.spans} and {@code --ingest.max-spans-per-second} belong to the server
     * alone and are forwarded as the {@code spidersense.*} system property of the same name,
     * which the server reads from this JVM.
     */
    public static Config fromArgs(String[] args) {
        Config c = fromSystemProperties();
        if (args == null) {
            return c;
        }
        int port = c.port();
        String host = c.host();
        String collector = c.collector();
        String service = c.service();
        String db = c.db();
        Integer retentionHours = c.retentionHours();
        long slowRequest = c.slowRequestMs();
        long slowQuery = c.slowQueryMs();
        boolean open = c.open();
        String mode = c.mode();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = arg.substring(2, eq).trim();
            String value = arg.substring(eq + 1).trim();
            switch (key) {
                case "port" -> port = Integer.parseInt(value);
                case "host" -> host = value;
                case "collector" -> collector = emptyToNull(value);
                case "service", "embedded-service" -> service = emptyToNull(value);
                case "db" -> db = emptyToNull(value);
                // Both spellings: the property is retention.hours, but a dashed flag reads better.
                case "retention.hours", "retention-hours" -> retentionHours = Integer.valueOf(value);
                case "slow.request.ms" -> slowRequest = Long.parseLong(value);
                case "slow.query.ms" -> slowQuery = Long.parseLong(value);
                case "open" -> open = Boolean.parseBoolean(value);
                case "mode" -> mode = value;
                // Server-owned keys the launcher never interprets: the server runs in this JVM
                // and reads them as spidersense.* properties, so the argument becomes the
                // property. The value is kept as written, because an empty
                // spidersense.ignore.endpoints means "ignore nothing" (design.md).
                case "app.packages", "ignore.endpoints", "retention.spans",
                        "ingest.max-spans-per-second" ->
                        System.setProperty("spidersense." + key, value);
                default -> { /* unknown keys are ignored */ }
            }
        }
        return new Config(port, host, collector, service, db, retentionHours,
                slowRequest, slowQuery, open, mode);
    }

    public Config withMode(String newMode) {
        return new Config(port, host, collector, service, db, retentionHours,
                slowRequestMs, slowQueryMs, open, newMode);
    }

    public Config withService(@Nullable String newService) {
        return new Config(port, host, collector, newService, db, retentionHours,
                slowRequestMs, slowQueryMs, open, mode);
    }

    public Config withPort(int newPort) {
        return new Config(newPort, host, collector, service, db, retentionHours,
                slowRequestMs, slowQueryMs, open, mode);
    }

    public Config withDb(@Nullable String newDb) {
        return new Config(port, host, collector, service, newDb, retentionHours,
                slowRequestMs, slowQueryMs, open, mode);
    }

    /** The arguments handed to {@code SpiderSenseServer.main}. */
    public List<String> toServerArgs() {
        List<String> args = new ArrayList<>();
        args.add("--port=" + port);
        args.add("--host=" + host);
        args.add("--mode=" + mode);
        if (db != null && !db.isEmpty()) {
            args.add("--db=" + db);
        }
        if (retentionHours != null) {
            args.add("--retention.hours=" + retentionHours);
        }
        args.add("--slow.request.ms=" + slowRequestMs);
        args.add("--slow.query.ms=" + slowQueryMs);
        if (service != null && !service.isEmpty()) {
            args.add("--embedded-service=" + service);
        }
        return args;
    }

    /** The base URL to reach this Spider Sense on; {@code 0.0.0.0} is not an address to connect to. */
    public String baseUrl() {
        String h = host;
        if (h == null || h.isEmpty() || h.equals("0.0.0.0") || h.equals("::") || h.equals("[::]")) {
            h = DEFAULT_HOST;
        }
        return "http://" + h + ":" + port;
    }

    /** Where the agent should export to: the forwarding collector if given, else our own UI. */
    public String otlpEndpoint() {
        if (collector == null || collector.isEmpty()) {
            return baseUrl();
        }
        String c = collector.trim();
        while (c.endsWith("/")) {
            c = c.substring(0, c.length() - 1);
        }
        return c;
    }

    /** What to print for the database: the H2 file, or the JDBC URL when one was given. */
    public String databaseDescription() {
        String value = db == null || db.isEmpty() ? DEFAULT_DB : db;
        return value.startsWith("jdbc:") ? value : value + ".mv.db";
    }

    // --- property / environment lookup -------------------------------------------------------

    /**
     * The OpenTelemetry environment-variable spelling of a property name: upper case, dots and
     * dashes replaced by underscores ({@code otel.exporter.otlp.endpoint} becomes
     * {@code OTEL_EXPORTER_OTLP_ENDPOINT}).
     */
    public static String envName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** The system property if set, else the matching environment variable, else {@code null}. */
    public static @Nullable String propertyOrEnv(String property) {
        String v = System.getProperty(property);
        if (v == null) {
            v = System.getenv(envName(property));
        }
        return emptyToNull(v);
    }

    private static @Nullable String emptyToNull(@Nullable String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String stringProperty(String name, String fallback) {
        String v = emptyToNull(System.getProperty(name));
        return v != null ? v : fallback;
    }

    /** A property whose absence is itself the value: the launcher passes it on only when set. */
    private static @Nullable String optionalProperty(String name) {
        return emptyToNull(System.getProperty(name));
    }

    private static int intProperty(String name, int fallback) {
        String v = emptyToNull(System.getProperty(name));
        return v != null ? Integer.parseInt(v.trim()) : fallback;
    }

    private static @Nullable Integer integerProperty(String name) {
        String v = emptyToNull(System.getProperty(name));
        return v != null ? Integer.valueOf(v.trim()) : null;
    }

    private static long longProperty(String name, long fallback) {
        String v = emptyToNull(System.getProperty(name));
        return v != null ? Long.parseLong(v.trim()) : fallback;
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String v = emptyToNull(System.getProperty(name));
        return v != null ? Boolean.parseBoolean(v.trim()) : fallback;
    }
}
