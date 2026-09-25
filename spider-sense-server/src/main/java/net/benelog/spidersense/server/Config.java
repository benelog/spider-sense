package net.benelog.spidersense.server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import net.benelog.spidersense.store.IgnoredEndpoints;
import net.benelog.spidersense.store.IngestCap;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Sweeper;
import org.jspecify.annotations.Nullable;

/**
 * Everything the server is told at startup.
 *
 * <p>Two channels, because there are two ways to start: {@code --key=value}
 * arguments for the standalone jar, and {@code spidersense.<key>} system
 * properties for agent mode, where the launcher runs inside someone else's JVM
 * and has no command line of its own. An argument wins over a property, since
 * the argument is the more specific statement.
 *
 * @param mode             {@code agent} or {@code standalone}
 * @param db               an H2 path or a full {@code jdbc:h2:} URL; see {@link #jdbcUrl()}
 * @param retentionHours   rows older than this are swept
 * @param retentionSpans   the most {@code span} rows kept; the sweeper deletes the oldest hour of
 *                         everything until the count is under it, {@code 0} for no cap
 *                         (storage.adoc#retention)
 * @param maxSpansPerSecond above this many spans accepted in one wall-clock second the receiver
 *                         drops the spans of traces it has not seen yet; null when unset, which is
 *                         no cap at all (storage.adoc#ingest-cap)
 * @param embeddedService  the {@code service.name} of the JVM the server runs inside, or null
 *                         when nobody knows it yet — see {@code ServiceRegistry} — and always
 *                         null in standalone mode, which runs inside nothing
 * @param appPackages      comma-separated package prefixes that count as application code in a
 *                         finding's code frames; empty means "everything that is not a
 *                         known framework" (findings.adoc#code)
 * @param ignoreEndpoints  comma-separated glob patterns; an entry span whose endpoint matches one
 *                         of them is written with {@code entry} false and is therefore not a
 *                         request (configuration.adoc#ignored-endpoints). An empty value ignores
 *                         nothing, which is why {@code Settings.string} only falls back on {@code null}.
 * @param sourceDirs       comma-separated source roots a code frame is resolved under, or null
 *                         for the default: {@code src/main/java} and {@code src/main/kotlin} of
 *                         the working directory and of each immediate subdirectory
 *                         (configuration.adoc#source-dirs)
 * @param jar              the absolute path of the distributable jar the server was started
 *                         from, which the launcher passes as {@code --jar} and the CLI finds in
 *                         {@code spidersense.jar}; null when nobody knows it (exploded classes,
 *                         a test), and only ever shown, as {@code /api/status.jar} (api.adoc#status)
 * @param awaitWrites      whether an OTLP request waits for the writer to store what it sent before
 *                         it is answered; {@code --await-writes}, for tests that POST and then read,
 *                         and never set in production, where the point of the queue is that a
 *                         request does not wait for the disk
 * @param home             the user's home directory, which a {@code ~} in {@link #db} stands for
 */
public record Config(
        String host,
        int port,
        String mode,
        String db,
        int retentionHours,
        long retentionSpans,
        @Nullable Long maxSpansPerSecond,
        long slowRequestMs,
        long slowQueryMs,
        @Nullable String embeddedService,
        String appPackages,
        String ignoreEndpoints,
        @Nullable String sourceDirs,
        @Nullable String jar,
        boolean awaitWrites,
        Path home) {

    public static final String AGENT = "agent";
    public static final String STANDALONE = "standalone";
    public static final String DEFAULT_DB = "~/db/spider-sense/sense";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 4000;

    /** The configuration this JVM was given: its arguments, system properties and environment. */
    public static Config parse(String[] args) {
        return parse(args, System::getProperty, System::getenv,
                Path.of(System.getProperty("user.home", "")), System.err::println);
    }

    /**
     * The same with every outside source given, for a test: a key the arguments leave unsaid is
     * read from its {@code spidersense.*} property, and a key neither says from its
     * {@code SPIDERSENSE_*} variable (configuration.adoc).
     *
     * @param property the system properties, by name
     * @param env      the environment variables, by name
     * @param home     what {@code ~} in a database path means
     * @param warn     where a malformed property or variable is reported
     */
    static Config parse(String[] args, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env, Path home, Consumer<String> warn) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (equals > 2) {
                values.put(arg.substring(2, equals), arg.substring(equals + 1));
            } else {
                values.put(arg.substring(2), "true");
            }
        }
        Set<String> given = Set.copyOf(values.keySet());
        for (String key : KEYS) {
            if (!values.containsKey(key)) {
                String setting = propertyOrEnv("spidersense." + key, property, env);
                if (setting != null) {
                    values.put(key, setting);
                }
            }
        }
        Settings settings = new Settings(values, given, warn);
        NumericSettings numbers = new NumericSettings(settings);
        boolean agent = AGENT.equalsIgnoreCase(settings.string("mode", STANDALONE));
        return new Config(
                settings.string("host", DEFAULT_HOST),
                numbers.number("port", DEFAULT_PORT).intValue(),
                agent ? AGENT : STANDALONE,
                settings.string("db", DEFAULT_DB),
                numbers.number("retention.hours", 24L).intValue(),
                numbers.number("retention.spans", Sweeper.DEFAULT_RETENTION_SPANS),
                numbers.optionalNumber("ingest.max-spans-per-second"),
                numbers.number("slow.request.ms", 500L),
                numbers.number("slow.query.ms", 100L),
                // A standalone server runs inside no application, whatever service a
                // properties file or a build tool names for the applications beside it.
                agent ? embeddedService(values, property, env) : null,
                settings.string("app.packages", ""),
                settings.string("ignore.endpoints", IgnoredEndpoints.DEFAULT),
                settings.stringOrNull("source.dirs"),
                settings.stringOrNull("jar"),
                Boolean.parseBoolean(settings.string("await-writes", "false")),
                home);
    }

    /**
     * The keys {@link #parse} reads, each also from its property and its environment variable.
     * {@code await-writes} is not among them: it is an argument only, so no property or variable
     * of a running application can make ingest wait for the disk.
     */
    private static final List<String> KEYS = List.of("host", "port", "mode", "db", "retention.hours",
            "retention.spans", "ingest.max-spans-per-second", "slow.request.ms", "slow.query.ms",
            "embedded-service", "app.packages", "ignore.endpoints", "source.dirs", "jar");

    /**
     * The keys whose readers tell an empty value from an unset one: an empty
     * {@code spidersense.ignore.endpoints} ignores nothing, an empty {@code spidersense.source.dirs}
     * names no root (configuration.adoc).
     */
    static final Set<String> EMPTY_IS_A_VALUE =
            Set.of("spidersense.ignore.endpoints", "spidersense.source.dirs");

    /**
     * The {@code spidersense.*} setting outside the arguments: the system property,
     * else the environment variable of the same name, else null.
     */
    public static @Nullable String propertyOrEnv(String property) {
        return propertyOrEnv(property, System::getProperty, System::getenv);
    }

    /**
     * The same with the properties and the environment given, by configuration.adoc's rule: an
     * empty property, such as the {@code -Dspidersense.port=} an unset shell variable leaves, is
     * unset and the variable is next, except for the keys of {@link #EMPTY_IS_A_VALUE}, where the
     * empty value is the answer; an empty variable is unset for every key.
     */
    static @Nullable String propertyOrEnv(String name, Function<String, @Nullable String> property,
            Function<String, @Nullable String> env) {
        String value = property.apply(name);
        if (value != null && (!value.isEmpty() || EMPTY_IS_A_VALUE.contains(name))) {
            return value;
        }
        String variable = env.apply(envName(name));
        return variable == null || variable.isEmpty() ? null : variable;
    }

    /** {@code spidersense.slow.query.ms} is {@code SPIDERSENSE_SLOW_QUERY_MS}. */
    public static String envName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    public boolean agentMode() {
        return AGENT.equals(mode);
    }

    /**
     * The base URL to print and to advertise in {@code /api/status}.
     *
     * <p>A wildcard bind is not an address to connect to, from another machine or on Windows,
     * so it is advertised as {@code 127.0.0.1}, as the launcher's banner does.
     */
    public String baseUrl(int boundPort) {
        return "http://" + callableHost(host) + ":" + boundPort;
    }

    /**
     * Whether a bind address means every interface: {@code 0.0.0.0}, {@code ::}, {@code [::]}, or
     * none said.
     */
    public static boolean isWildcard(String host) {
        String h = host.trim();
        return h.isEmpty() || h.equals("0.0.0.0") || h.equals("::") || h.equals("[::]");
    }

    /**
     * The host to connect to for a bind address, as a URL writes it: a wildcard is
     * {@link #DEFAULT_HOST}, and an IPv6 address goes in brackets ({@code http://[::1]:4000}).
     */
    public static String callableHost(String host) {
        String h = isWildcard(host) ? DEFAULT_HOST : host;
        return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h;
    }

    /**
     * The JDBC URL {@link #db} means.
     *
     * <p>A value that already starts with {@code jdbc:} is taken as written, so a
     * test can ask for {@code jdbc:h2:mem:…} and a user can pass any H2 option.
     * Anything else is a file path: {@code ~} is expanded (the JDBC driver would
     * resolve it too, but then the status page could not report the file), and
     * {@code AUTO_SERVER=TRUE} is appended so that several Spider Sense processes
     * share one database.
     *
     * <p>{@code NON_KEYWORDS=KEY,VALUE} is appended to every URL: {@code KEY} and
     * {@code VALUE} are reserved words in H2 2.x and storage.adoc#schema uses both as
     * column names.
     */
    public String jdbcUrl() {
        String url = db.startsWith("jdbc:")
                ? db
                : "jdbc:h2:" + expandHome(db, home) + ";AUTO_SERVER=TRUE";
        if (!url.toUpperCase(java.util.Locale.ROOT).contains("NON_KEYWORDS")) {
            url = url + ";NON_KEYWORDS=KEY,VALUE";
        }
        return url;
    }

    /** The database file, or null for an in-memory database. */
    public @Nullable Path databaseFile() {
        String url = jdbcUrl();
        if (url.startsWith("jdbc:h2:mem:")) {
            return null;
        }
        String rest = url.substring("jdbc:h2:".length());
        int options = rest.indexOf(';');
        String path = options < 0 ? rest : rest.substring(0, options);
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        return Path.of(expandHome(path, home) + ".mv.db");
    }

    /** What the server's store is opened with: this configuration, read with {@code clock}. */
    public Store.Settings storeSettings(LongSupplier clock) {
        return new Store.Settings(jdbcUrl(), databaseFile(), retentionHours, slowRequestMs, slowQueryMs,
                embeddedService, ignoreEndpoints, retentionSpans, IngestCap.of(maxSpansPerSecond), clock);
    }

    /** The path with a leading {@code ~} read as this JVM's home directory. */
    public static String expandHome(String path) {
        return expandHome(path, Path.of(System.getProperty("user.home", "")));
    }

    /** The path with a leading {@code ~} read as {@code home}. */
    public static String expandHome(String path, Path home) {
        if (path.startsWith("~/") || path.equals("~")) {
            return home + path.substring(1);
        }
        return path;
    }

    private static @Nullable String embeddedService(Map<String, String> values,
            Function<String, @Nullable String> property, Function<String, @Nullable String> env) {
        String named = values.get("embedded-service");
        if (named != null) {
            return named;
        }
        // configuration.adoc#properties names the launcher's own property spidersense.service; when the
        // launcher was told the name that way, it is the same answer.
        return propertyOrEnv("spidersense.service", property, env);
    }

    /**
     * The arguments, with the properties and the environment already folded in below them.
     *
     * @param given the keys that came as {@code --key=value} arguments
     */
    private record Settings(Map<String, String> values, Set<String> given, Consumer<String> warn) {

        /**
         * The argument, else the property or variable, else the fallback — and the fallback only
         * when none was given at all. An explicitly empty value stays empty where it means
         * something, which is what {@code -Dspidersense.ignore.endpoints=} is.
         */
        String string(String key, String fallback) {
            String value = stringOrNull(key);
            return value == null ? fallback : value;
        }

        /** The same with no fallback: null when no channel said anything. */
        @Nullable String stringOrNull(String key) {
            return values.get(key);
        }
    }

    /**
     * The numeric keys, read with the rule configuration.adoc gives: a malformed argument is a
     * usage error, because the command line is the caller's own statement and the launcher has
     * already refused a bad one; a malformed system property or environment variable is a
     * warning on stderr, and that key alone takes its default, so a typo in one key never stops
     * the embedded UI of an application that is otherwise unaffected.
     */
    private record NumericSettings(Settings settings) {

        /** The number, else null when nobody said anything: an unset cap is not a cap of zero. */
        @Nullable Long optionalNumber(String key) {
            String value = settings.stringOrNull(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return parse(key, value, null);
        }

        Long number(String key, long fallback) {
            String value = settings.stringOrNull(key);
            if (value == null || (value.isBlank() && !settings.given().contains(key))) {
                return fallback;
            }
            Long parsed = parse(key, value, fallback);
            return parsed == null ? fallback : parsed;
        }

        private @Nullable Long parse(String key, String value, @Nullable Long fallback) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                if (settings.given().contains(key)) {
                    throw new IllegalArgumentException("Not a number for --" + key + ": " + value, e);
                }
                settings.warn().accept("[spider-sense] spidersense." + key + "=" + value
                        + " is not a number; using " + (fallback == null ? "no cap" : fallback));
                return null;
            }
        }
    }
}
