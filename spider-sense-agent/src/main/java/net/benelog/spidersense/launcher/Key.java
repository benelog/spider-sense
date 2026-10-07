package net.benelog.spidersense.launcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The configuration keys the jar takes, one row each: the table in
 * {@code configuration.adoc#properties}, and nothing else. The mode is not a key: the launcher
 * decides it, so {@code --mode=agent} on the command line is as unknown as {@code --prot=4001}.
 *
 * <p>Everything that lists the keys is derived from here: the properties file's known keys
 * ({@link ConfigFile#KNOWN_KEYS}), which {@code --key=value} arguments {@link Config#parse} accepts
 * and how it checks them, and the option lines of {@code --help}.
 */
enum Key {

    PORT("port", Kind.INT, Owner.LAUNCHER, String.valueOf(Config.DEFAULT_PORT),
            "UI and OTLP/HTTP port"),
    HOST("host", Kind.STRING, Owner.LAUNCHER, Config.DEFAULT_HOST,
            "bind address; 0.0.0.0 to reach it from elsewhere"),
    COLLECTOR("collector", Kind.STRING, Owner.LAUNCHER, "<url>",
            "agent mode: forward instead of starting the UI"),
    SERVICE("service", Kind.STRING, Owner.LAUNCHER, "<name>",
            "agent mode: sets otel.service.name"),
    DB("db", Kind.STRING, Owner.LAUNCHER, Config.DEFAULT_DB,
            "H2 database path or jdbc:h2: URL"),
    // Both spellings: the property is retention.hours, but a dashed flag reads better.
    RETENTION_HOURS("retention.hours", Kind.INT, Owner.LAUNCHER,
            String.valueOf(Config.DEFAULT_RETENTION_HOURS),
            "rows older than this are swept", "retention-hours"),
    RETENTION_SPANS("retention.spans", Kind.LONG, Owner.SERVER, "1000000",
            "the most spans kept; 0 for no cap"),
    INGEST_MAX_SPANS_PER_SECOND("ingest.max-spans-per-second", Kind.LONG, Owner.SERVER, "",
            "above this, new traces are dropped; unset for no cap"),
    SLOW_REQUEST_MS("slow.request.ms", Kind.LONG, Owner.LAUNCHER,
            String.valueOf(Config.DEFAULT_SLOW_REQUEST_MS),
            "a server span slower than this is a tingle"),
    SLOW_QUERY_MS("slow.query.ms", Kind.LONG, Owner.LAUNCHER,
            String.valueOf(Config.DEFAULT_SLOW_QUERY_MS),
            "a DB span slower than this is a tingle"),
    APP_PACKAGES("app.packages", Kind.STRING, Owner.SERVER, "",
            "package prefixes that count as application code"),
    IGNORE_ENDPOINTS("ignore.endpoints", Kind.STRING, Owner.SERVER,
            "/actuator/**,/health,/healthz,/livez,/readyz",
            "endpoints that are not requests; empty for none"),
    SOURCE_DIRS("source.dirs", Kind.STRING, Owner.SERVER, "",
            "source roots for code frames; the default is\n"
                    + "src/main/java and src/main/kotlin here and one level down"),
    OPEN("open", Kind.BOOLEAN, Owner.LAUNCHER, "false",
            "agent mode: open the browser at startup");

    /**
     * How a value is checked: a number that does not parse, or a boolean that is neither
     * {@code true} nor {@code false}, is a usage error on the command line.
     */
    enum Kind { INT, LONG, BOOLEAN, STRING }

    /**
     * Who reads the key. The launcher's own become fields of {@link Config}; the server's are
     * forwarded as the {@code spidersense.*} system property of the same name, which the server,
     * running in this JVM, reads.
     */
    enum Owner { LAUNCHER, SERVER }

    /** The prefix of every key's system property. */
    static final String PROPERTY_PREFIX = "spidersense.";

    private final String name;
    private final Kind kind;
    private final Owner owner;
    private final String shown;
    private final String help;
    private final @Nullable String alias;

    /**
     * @param name    the key without the {@code spidersense.} prefix, as {@code --name=value} takes it
     * @param shown   what {@code --help} shows after the {@code =}: the default, the server's for the
     *                keys whose default the launcher leaves to it, empty for unset, or a placeholder
     * @param help    the help text, one line per line
     */
    Key(String name, Kind kind, Owner owner, String shown, String help) {
        this(name, kind, owner, shown, help, null);
    }

    /** @param alias the other spelling the command line accepts */
    Key(String name, Kind kind, Owner owner, String shown, String help, @Nullable String alias) {
        this.name = name;
        this.kind = kind;
        this.owner = owner;
        this.shown = shown;
        this.help = help;
        this.alias = alias;
    }

    String argument() {
        return name;
    }

    String property() {
        return PROPERTY_PREFIX + name;
    }

    Kind kind() {
        return kind;
    }

    Owner owner() {
        return owner;
    }

    String shown() {
        return shown;
    }

    /** The key a {@code --name=value} argument names, by its name or its alias; null for none. */
    static @Nullable Key ofArgument(String name) {
        for (Key key : values()) {
            if (key.name.equals(name) || name.equals(key.alias)) {
                return key;
            }
        }
        return null;
    }

    /** The properties of the keys, which are the rows of configuration.adoc's table. */
    static Set<String> documentedProperties() {
        Set<String> properties = new HashSet<>();
        for (Key key : values()) {
            properties.add(key.property());
        }
        return Set.copyOf(properties);
    }

    /**
     * The {@code spidersense.*} properties that are read but are no row of the table: the properties
     * file's name, and the jar paths the launcher hands the CLI or a test hands the launcher.
     */
    static final Set<String> OTHER_PROPERTIES = Set.of(ConfigFile.PROPERTY, SpiderSenseMain.JAR_PROPERTY,
            NestedJar.SERVER_JAR_PROPERTY, NestedJar.EXTENSION_JAR_PROPERTY);

    /**
     * One warning for each of {@code names} that starts with {@code spidersense.} and is not a key,
     * in name order, naming the nearest key when one is close: {@code spidersense.prot} is not a
     * key, and {@code spidersense.port} is one transposition away. A typo under
     * {@code -javaagent} would otherwise leave its key at the default without a word.
     *
     * @param jar this jar as a command names it, for the line that has no key to suggest
     */
    static List<String> unknownProperties(Collection<String> names, String jar) {
        List<String> warnings = new ArrayList<>();
        Set<String> known = documentedProperties();
        for (String name : new TreeSet<>(names)) {
            if (!name.startsWith(PROPERTY_PREFIX) || known.contains(name) || OTHER_PROPERTIES.contains(name)) {
                continue;
            }
            String nearest = nearest(name);
            warnings.add(name + " is not a Spider Sense key"
                    + (nearest != null ? " (did you mean " + nearest + "?)" : "; java -jar " + jar + " --help lists them"));
        }
        return warnings;
    }

    /**
     * The key closest to a {@code spidersense.*} name, or {@code null} when none is close: at most
     * one edit for every three characters after the prefix, and never more than three, a swap of
     * two neighbours counting as one.
     */
    static @Nullable String nearest(String property) {
        String typed = property.startsWith(PROPERTY_PREFIX) ? property.substring(PROPERTY_PREFIX.length()) : property;
        int allowed = Math.max(1, Math.min(3, typed.length() / 3));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Key key : values()) {
            int distance = distance(typed, key.name);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = key.property();
            }
        }
        return bestDistance <= allowed ? best : null;
    }

    /** The optimal string alignment distance: insertions, deletions, substitutions and swaps of neighbours. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Where the help text starts: the option is padded to this column. */
    private static final int HELP_COLUMN = 34;

    /**
     * The documented keys as {@code --help} lists them: {@code --name=shown}, the help text at
     * {@link #HELP_COLUMN}, and on a line of its own when the option is too long to leave room.
     */
    static String helpLines() {
        StringBuilder out = new StringBuilder();
        String indent = " ".repeat(HELP_COLUMN);
        for (Key key : values()) {
            String option = "  --" + key.name + "=" + key.shown;
            List<String> lines = key.help.lines().toList();
            if (option.length() < HELP_COLUMN) {
                out.append(option).append(" ".repeat(HELP_COLUMN - option.length())).append(lines.get(0));
            } else {
                out.append(option).append('\n').append(indent).append(lines.get(0));
            }
            out.append('\n');
            for (String more : lines.subList(1, lines.size())) {
                out.append(indent).append(more).append('\n');
            }
        }
        return out.toString();
    }
}
