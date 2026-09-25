package net.benelog.spidersense.launcher;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The Spider Sense configuration, exactly the table in {@code configuration.adoc#properties} plus
 * the mode.
 *
 * <p>In agent mode system properties are the only channel there is, because {@code premain} runs
 * before the application's {@code main}; the standalone jar takes the same keys without the
 * {@code spidersense.} prefix as {@code --key=value} arguments, which win over the properties.
 *
 * <p>{@code db} and {@code retentionHours} are {@code null} when the user said nothing, and are
 * then left out of {@link #toServerArgs()} entirely: their defaults belong to the server, which
 * owns the database (see {@code storage.adoc}), and repeating them here would mean two places
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

    /**
     * What {@link #parse} makes of the arguments, the properties and the environment.
     *
     * @param config           the launcher's configuration
     * @param serverProperties the server's own keys given as arguments, as the {@code spidersense.*}
     *                         system properties the caller sets for the server in this JVM to read;
     *                         a value is kept as written, because an empty
     *                         {@code spidersense.ignore.endpoints} means "ignore nothing"
     *                         (configuration.adoc#ignored-endpoints)
     * @param warnings         one line per malformed property or variable, which fell back to its
     *                         default; the caller prints them
     */
    public record Parsed(Config config, Map<String, String> serverProperties, List<String> warnings) {
    }

    /**
     * Reads the {@code spidersense.*} system properties and variables, falling back to the
     * defaults, and prints a line on stderr for each malformed one.
     */
    public static Config fromSystemProperties() {
        Parsed parsed = parse(null, System::getProperty, System::getenv);
        printWarnings(parsed);
        return parsed.config();
    }

    /** The same with the properties and the environment given, for a test. */
    static Config fromSystemProperties(Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        return parse(null, property, env).config();
    }

    /** {@link #parse} over this JVM's system properties and environment. */
    public static Parsed fromArgs(String @Nullable [] args) {
        return parse(args, System::getProperty, System::getenv);
    }

    /** Prints each of the warnings on stderr. */
    public static void printWarnings(Parsed parsed) {
        for (String warning : parsed.warnings()) {
            System.err.println(SpiderSenseAgent.PREFIX + warning);
        }
    }

    /**
     * The properties and variables, each read by {@link #propertyOrEnv(String, Function, Function)},
     * overridden by {@code --key=value} arguments; the keys are those of {@link Key}, e.g.
     * {@code --port=4001}.
     *
     * <p>Bare flags are ignored so {@code --help} and {@code --version} can be handled by the
     * caller. An unknown {@code --key=value} is a usage error, as a malformed number is, whoever
     * owns the key: a typo is a usage line, not a stack trace from the server after the banner.
     * A malformed property or variable costs its own key and no other: it falls back to its
     * default with a warning, so a typo in {@code spidersense.slow.query.ms} does not also drop
     * {@code spidersense.collector} and start an embedded UI where the user asked to forward.
     *
     * @throws IllegalArgumentException for an unknown or malformed argument
     */
    static Parsed parse(String @Nullable [] args, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        Map<Key, Argument> given = new EnumMap<>(Key.class);
        Map<String, String> serverProperties = new LinkedHashMap<>();
        for (String arg : args == null ? new String[0] : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (equals < 0) {
                continue;
            }
            Argument argument = new Argument(arg.substring(2, equals).trim(), arg.substring(equals + 1).trim());
            Key key = Key.ofArgument(argument.name());
            if (key == null) {
                // A typo would otherwise be invisible: the key would take its default and
                // nothing would say so.
                throw new IllegalArgumentException("unknown option: --" + argument.name()
                        + "; java -jar spider-sense.jar --help lists them");
            }
            if (key.owner() == Key.Owner.SERVER) {
                argument.check(key.kind());
                serverProperties.put(key.property(), argument.value());
            } else {
                given.put(key, argument);
            }
        }
        List<String> warnings = new ArrayList<>();
        Values values = new Values(given, name -> propertyOrEnv(name, property, env), warnings);
        Config d = defaults();
        Config config = new Config(
                values.integer(Key.PORT, d.port()),
                values.string(Key.HOST, d.host()),
                values.optionalString(Key.COLLECTOR),
                values.optionalString(Key.SERVICE),
                values.optionalString(Key.DB),
                values.optionalInteger(Key.RETENTION_HOURS, DEFAULT_RETENTION_HOURS),
                values.number(Key.SLOW_REQUEST_MS, d.slowRequestMs()),
                values.number(Key.SLOW_QUERY_MS, d.slowQueryMs()),
                values.bool(Key.OPEN, d.open()),
                values.string(Key.MODE, d.mode()));
        return new Parsed(config, Map.copyOf(serverProperties), List.copyOf(warnings));
    }

    /** One {@code --name=value} argument, both trimmed, the name as it was typed. */
    private record Argument(String name, String value) {

        /** A number the key needs, or a usage error naming the argument. */
        void check(Key.Kind kind) {
            try {
                switch (kind) {
                    case INT -> Integer.parseInt(value);
                    case LONG -> {
                        // Empty is allowed: it is unset, which is what an empty cap means.
                        if (!value.isEmpty()) {
                            Long.parseLong(value);
                        }
                    }
                    case BOOLEAN, STRING -> {
                    }
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " is not a number: " + value, e);
            }
        }
    }

    /**
     * The launcher's keys, each the argument if given, which must be well formed, else the
     * property or variable, which falls back with a warning when it is not.
     */
    private record Values(Map<Key, Argument> given, Function<String, @Nullable String> read,
            List<String> warnings) {

        String string(Key key, String fallback) {
            Argument argument = given.get(key);
            if (argument != null) {
                return argument.value();
            }
            String value = read.apply(key.property());
            return value != null ? value : fallback;
        }

        /** Empty is unset, from either channel. */
        @Nullable String optionalString(Key key) {
            Argument argument = given.get(key);
            return emptyToNull(argument != null ? argument.value() : read.apply(key.property()));
        }

        int integer(Key key, int fallback) {
            Integer value = optionalInteger(key, fallback);
            return value != null ? value : fallback;
        }

        /**
         * Null when unset; a malformed property is the fallback rather than null, so that for
         * {@code retention.hours} it is passed on and the server does not read the same malformed
         * property again and warn a second time.
         */
        @Nullable Integer optionalInteger(Key key, int malformedFallback) {
            Argument argument = given.get(key);
            if (argument != null) {
                argument.check(Key.Kind.INT);
                return Integer.valueOf(argument.value());
            }
            String value = read.apply(key.property());
            if (value == null) {
                return null;
            }
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException e) {
                warnMalformed(key, value, String.valueOf(malformedFallback));
                return malformedFallback;
            }
        }

        long number(Key key, long fallback) {
            Argument argument = given.get(key);
            if (argument != null) {
                try {
                    return Long.parseLong(argument.value());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "--" + argument.name() + " is not a number: " + argument.value(), e);
                }
            }
            String value = read.apply(key.property());
            if (value == null) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                warnMalformed(key, value, String.valueOf(fallback));
                return fallback;
            }
        }

        boolean bool(Key key, boolean fallback) {
            Argument argument = given.get(key);
            String value = argument != null ? argument.value() : read.apply(key.property());
            return value != null ? Boolean.parseBoolean(value.trim()) : fallback;
        }

        private void warnMalformed(Key key, String value, String fallback) {
            warnings.add(key.property() + "=" + value + " is not a number; using " + fallback);
        }
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
        // Only an agent is embedded in the service: a standalone server runs inside nothing,
        // even when the properties file or the Gradle plugin names the application's service.
        if (AGENT.equals(mode) && service != null && !service.isEmpty()) {
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
        // An IPv6 address goes into a URL in brackets: http://[::1]:4000.
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]";
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

    /**
     * The keys whose readers tell an empty value from an unset one: an empty
     * {@code spidersense.ignore.endpoints} ignores nothing, an empty {@code spidersense.source.dirs}
     * names no root. For every other key an empty property, such as {@code -Dspidersense.port=}
     * left by an unset shell variable, is unset, so it hides nothing.
     */
    static final Set<String> EMPTY_IS_A_VALUE = Set.of(
            "spidersense.ignore.endpoints",
            "spidersense.source.dirs");

    /** The system property if set, else the matching environment variable, else {@code null}. */
    public static @Nullable String propertyOrEnv(String property) {
        return propertyOrEnv(property, System::getProperty, System::getenv);
    }

    /**
     * The same with the properties and the environment given, by configuration.adoc's rule: an
     * empty property is unset and the variable is next, except for the keys of
     * {@link #EMPTY_IS_A_VALUE}, where the empty value is the answer; an empty variable is unset for
     * every key.
     */
    static @Nullable String propertyOrEnv(String name, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        String value = property.apply(name);
        if (value != null && (!value.isEmpty() || EMPTY_IS_A_VALUE.contains(name))) {
            return value;
        }
        return emptyToNull(env.apply(envName(name)));
    }

    private static @Nullable String emptyToNull(@Nullable String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
