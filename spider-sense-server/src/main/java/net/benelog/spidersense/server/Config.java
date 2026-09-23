package net.benelog.spidersense.server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.benelog.spidersense.store.IgnoredEndpoints;
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
 *                         (storage.md, "Retention")
 * @param maxSpansPerSecond above this many spans accepted in one wall-clock second the receiver
 *                         drops the spans of traces it has not seen yet; null when unset, which is
 *                         no cap at all (storage.md, "The ingest cap")
 * @param embeddedService  the {@code service.name} of the JVM the server runs inside, or null
 *                         when nobody knows it yet — see {@code ServiceRegistry}
 * @param appPackages      comma-separated package prefixes that count as application code in a
 *                         finding's code frames; empty means "everything that is not a
 *                         known framework" (agent.md)
 * @param ignoreEndpoints  comma-separated glob patterns; an entry span whose endpoint matches one
 *                         of them is written with {@code entry} false and is therefore not a
 *                         request (design.md, "Ignored endpoints"). An empty value ignores
 *                         nothing, which is why {@link #string} only falls back on {@code null}.
 * @param sourceDirs       comma-separated source roots a code frame is resolved under, or null
 *                         for the default: {@code src/main/java} and {@code src/main/kotlin} of
 *                         the working directory and of each immediate subdirectory (design.md)
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
        @Nullable String sourceDirs) {

    public static final String AGENT = "agent";
    public static final String STANDALONE = "standalone";
    public static final String DEFAULT_DB = "~/db/spider-sense/sense";

    public static Config parse(String[] args) {
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
        String mode = string(values, "mode", STANDALONE);
        return new Config(
                string(values, "host", "127.0.0.1"),
                number(values, "port", 4000).intValue(),
                AGENT.equalsIgnoreCase(mode) ? AGENT : STANDALONE,
                string(values, "db", DEFAULT_DB),
                number(values, "retention.hours", 24L).intValue(),
                number(values, "retention.spans", Sweeper.DEFAULT_RETENTION_SPANS),
                optionalNumber(values, "ingest.max-spans-per-second"),
                number(values, "slow.request.ms", 500L),
                number(values, "slow.query.ms", 100L),
                embeddedService(values),
                string(values, "app.packages", ""),
                string(values, "ignore.endpoints", IgnoredEndpoints.DEFAULT),
                stringOrNull(values, "source.dirs"));
    }

    public boolean agentMode() {
        return AGENT.equals(mode);
    }

    /** The base URL to print and to advertise in {@code /api/status}. */
    public String endpoint(int boundPort) {
        return "http://" + host + ":" + boundPort;
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
     * {@code VALUE} are reserved words in H2 2.x and storage.md uses both as
     * column names.
     */
    public String jdbcUrl() {
        String url = db.startsWith("jdbc:")
                ? db
                : "jdbc:h2:" + expandHome(db) + ";AUTO_SERVER=TRUE";
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
        return Path.of(expandHome(path) + ".mv.db");
    }

    private static String expandHome(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static @Nullable String embeddedService(Map<String, String> values) {
        String named = values.get("embedded-service");
        if (named != null) {
            return named;
        }
        String property = System.getProperty("spidersense.embedded-service");
        // design.md names the launcher's own property spidersense.service; when the
        // launcher was told the name that way, it is the same answer.
        return property != null ? property : System.getProperty("spidersense.service");
    }

    /**
     * The argument, else the system property, else the fallback — and the fallback only when
     * neither was given at all. An explicitly empty value stays empty, which is what
     * {@code -Dspidersense.ignore.endpoints=} means.
     */
    private static String string(Map<String, String> values, String key, String fallback) {
        String value = stringOrNull(values, key);
        return value == null ? fallback : value;
    }

    /** The same with no fallback: null when neither channel said anything. */
    private static @Nullable String stringOrNull(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? System.getProperty("spidersense." + key) : value;
    }

    /** The same, but {@code null} when nobody said anything: an unset cap is not a cap of zero. */
    private static @Nullable Long optionalNumber(Map<String, String> values, String key) {
        String value = stringOrNull(values, key);
        return value == null || value.isBlank() ? null : parse(key, value);
    }

    private static Long number(Map<String, String> values, String key, long fallback) {
        String value = stringOrNull(values, key);
        if (value == null) {
            return fallback;
        }
        return parse(key, value);
    }

    private static Long parse(String key, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number for --" + key + ": " + value, e);
        }
    }
}
