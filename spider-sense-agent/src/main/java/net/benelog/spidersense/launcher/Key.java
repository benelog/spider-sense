package net.benelog.spidersense.launcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The configuration keys the jar takes, one row each: the table in
 * {@code configuration.adoc#properties}, plus the mode.
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
            "agent mode: sets otel.service.name", "embedded-service"),
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
            "agent mode: open the browser at startup"),
    /**
     * Not a user's key: the launcher decides the mode, and the argument exists so that
     * {@link Config#toServerArgs()} reads back through {@link Config#parse}.
     */
    MODE("mode", Kind.STRING, Owner.LAUNCHER, Config.AGENT, null);

    /** How a value is checked: a number that does not parse is a usage error on the command line. */
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
    private final @Nullable String help;
    private final @Nullable String alias;

    /**
     * @param name    the key without the {@code spidersense.} prefix, as {@code --name=value} takes it
     * @param shown   what {@code --help} shows after the {@code =}: the default, the server's for the
     *                keys whose default the launcher leaves to it, empty for unset, or a placeholder
     * @param help    the help text, one line per line; null for a key the help does not list
     */
    Key(String name, Kind kind, Owner owner, String shown, @Nullable String help) {
        this(name, kind, owner, shown, help, null);
    }

    /** @param alias the other spelling the command line accepts */
    Key(String name, Kind kind, Owner owner, String shown, @Nullable String help, @Nullable String alias) {
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

    /** Whether the key is in configuration.adoc's table, and so in the help and the file's keys. */
    boolean documented() {
        return help != null;
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

    /** The properties of the documented keys. */
    static Set<String> documentedProperties() {
        Set<String> properties = new HashSet<>();
        for (Key key : values()) {
            if (key.documented()) {
                properties.add(key.property());
            }
        }
        return Set.copyOf(properties);
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
            if (key.help == null) {
                continue;
            }
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
