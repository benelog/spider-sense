package net.benelog.spidersense.server;

import java.util.HashMap;
import java.util.Map;

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
 * @param embeddedService  the {@code service.name} of the JVM the server runs inside, or null
 *                         when nobody knows it yet — see {@code ServiceRegistry}
 * @param appPackages      comma-separated package prefixes that count as application code in a
 *                         finding's {@code code} frames; empty means "everything that is not a
 *                         known framework" (agent.md)
 */
public record Config(
        String host,
        int port,
        String mode,
        String db,
        int retentionHours,
        long slowRequestMs,
        long slowQueryMs,
        String embeddedService,
        String appPackages) {

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
                number(values, "slow.request.ms", 500L),
                number(values, "slow.query.ms", 100L),
                embeddedService(values),
                string(values, "app.packages", ""));
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
    public java.nio.file.Path databaseFile() {
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
        return java.nio.file.Path.of(expandHome(path) + ".mv.db");
    }

    private static String expandHome(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static String embeddedService(Map<String, String> values) {
        String named = values.get("embedded-service");
        if (named != null) {
            return named;
        }
        String property = System.getProperty("spidersense.embedded-service");
        // design.md names the launcher's own property spidersense.service; when the
        // launcher was told the name that way, it is the same answer.
        return property != null ? property : System.getProperty("spidersense.service");
    }

    private static String string(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        if (value == null) {
            value = System.getProperty("spidersense." + key);
        }
        return value == null ? fallback : value;
    }

    private static Long number(Map<String, String> values, String key, long fallback) {
        String value = string(values, key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number for --" + key + ": " + value, e);
        }
    }
}
