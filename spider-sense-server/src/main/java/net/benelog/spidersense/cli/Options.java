package net.benelog.spidersense.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.benelog.spidersense.api.Limits;
import net.benelog.spidersense.query.Check;
import org.jspecify.annotations.Nullable;

/**
 * One command line, parsed: {@code <command> [argument] [--options]}.
 *
 * <p>What each command accepts is its row of {@link Command}, which both modes —
 * HTTP and the H2 file — answer from, so they read the same options and
 * disagree about nothing. An option a command does not take is a usage
 * error rather than a silently ignored word: an agent that mistyped
 * {@code --sinse} should be told, not answered for the last 15 minutes.
 */
final class Options {

    /**
     * A message for stderr, followed by one line naming the help to read, and exit 2
     * (cli.adoc#help).
     */
    static final class Usage extends RuntimeException {

        /** The command the line named, when it named one, whose own help the second line points at. */
        private final @Nullable String command;

        Usage(String message) {
            this(message, null);
        }

        Usage(String message, @Nullable String command) {
            super(message);
            this.command = command;
        }

        @Nullable String command() {
            return command;
        }
    }

    static final String HELP = "help";
    static final String VERSION = "version";
    static final String STATUS = "status";
    static final String TRACES = "traces";
    static final String ENDPOINTS = "endpoints";
    static final String QUERIES = "queries";
    static final String ERRORS = "errors";
    static final String LOGS = "logs";
    static final String MARKS = "marks";
    static final String INIT = "init";
    static final String MCP = "mcp";
    static final String CHECK = "check";
    static final String FINDINGS = "findings";
    static final String COMPARE = "compare";
    static final String MARK = "mark";
    static final String ACK = "ack";
    static final String UNACK = "unack";
    static final String RESOLVE = "resolve";
    static final String UNRESOLVE = "unresolve";
    static final String TRACE = "trace";
    static final String SQL = "sql";
    static final String TAIL = "tail";
    static final String EXPORT = "export";
    static final String IMPORT = "import";

    /**
     * The common options that are the server's settings, which {@link Local} hands the in-process
     * reader because there is no server to ask: the database and the thresholds.
     */
    static final List<String> SETTINGS = List.of("db", "slow.request.ms", "slow.query.ms", "app.packages");

    /**
     * Options every command that reads a window takes: where to read from, and how to print it.
     *
     * <p>{@link Command}'s rows are built from it, and nothing in this class's initialisation refers
     * to {@code Command}, so either class may load first.
     */
    private static final Set<String> COMMON = common();

    /**
     * The options that are flags, named bare: every other option takes a value, and naming it
     * without one is a usage error.
     */
    private static final Set<String> FLAGS =
            Set.of("json", "full", "hide-acked", "no-git", "no-skill", "mcp", "gradle");

    /** The rule flags of {@code check}, in the order api.adoc#check names their parameters. */
    private static final Map<String, String> RULES = ruleParameters();

    /** Every command the parser takes, which a test runs in both modes. */
    static Set<String> commands() {
        return new LinkedHashSet<>(Command.names());
    }

    private final String command;
    private final @Nullable String argument;
    private final Map<String, String> values;

    private Options(String command, @Nullable String argument, Map<String, String> values) {
        this.command = command;
        this.argument = argument;
        this.values = values;
    }

    /**
     * The command line, or the usage error it is.
     *
     * <p>{@code --help} or {@code -h} anywhere after a command is {@code help <command>}, whatever
     * else the line holds, so a line that is wrong is still a way to ask what is right.
     */
    static Options parse(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()
                || args[0].startsWith("-")) {
            throw new Usage("a command is needed");
        }
        String command = args[0];
        Command row = Command.named(command);
        if (row == null) {
            throw new Usage("unknown command: " + command + didYouMean(command, Command.names(), ""));
        }
        for (int i = 1; i < args.length; i++) {
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                return new Options(HELP, command, Map.of());
            }
        }
        Set<String> allowed = row.options();
        String label = row.argument();
        String argument = null;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i];
            if (arg.startsWith("--")) {
                int equals = arg.indexOf('=');
                String key = equals < 0 ? arg.substring(2) : arg.substring(2, equals);
                if (key.isEmpty() || !allowed.contains(key)) {
                    throw new Usage("unknown option for " + command + ": --" + key
                            + didYouMean(key, allowed, "--"), command);
                }
                if (equals < 0 && !FLAGS.contains(key)) {
                    // A bare --since would otherwise be the value "true", which is a legal mark name.
                    throw new Usage("--" + key + " needs a value: --" + key + "=<value>", command);
                }
                values.put(key, equals < 0 ? "true" : arg.substring(equals + 1));
            } else if (argument == null && (label != null || HELP.equals(command))) {
                argument = arg;
            } else {
                throw new Usage("unexpected argument: " + arg, command);
            }
        }
        if (label != null && argument == null) {
            throw new Usage(command + " needs " + label, command);
        }
        if (HELP.equals(command) && argument != null && Command.named(argument) == null) {
            throw new Usage("unknown command: " + argument + didYouMean(argument, Command.names(), ""));
        }
        if (COMPARE.equals(command) && !(values.containsKey("before") && values.containsKey("after"))) {
            throw new Usage("compare needs both --before and --after, as marks or time selectors", command);
        }
        return new Options(command, argument, values);
    }

    /**
     * {@code "; did you mean --since?"} for the nearest of {@code known} within two edits of
     * {@code typed}, the first in order on a tie; nothing when none is that near.
     */
    static String didYouMean(String typed, Iterable<String> known, String prefix) {
        String nearest = null;
        int best = 3;
        for (String candidate : known) {
            int distance = distance(typed, candidate);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest == null ? "" : "; did you mean " + prefix + nearest + "?";
    }

    /** The Levenshtein distance: the fewest insertions, deletions and substitutions from a to b. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    String command() {
        return command;
    }

    /** The command's own word: a trace id or a mark name. */
    @Nullable String argument() {
        return argument;
    }

    /** The same word, for a command {@link #parse} refuses without one. */
    String requiredArgument() {
        return Objects.requireNonNull(argument, command + " was parsed without its argument");
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    String value(String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    /** The option's value, or null when it was not given. */
    @Nullable String valueOrNull(String key) {
        return values.get(key);
    }

    /** A flag is true when named, unless it was given a value that is not "true". */
    boolean flag(String key) {
        return Boolean.parseBoolean(value(key, "false"));
    }

    /**
     * The limit of a list: a whole number of at least 1, lowered to the list's cap the way the
     * server lowers it (api.adoc, cli.adoc#options).
     *
     * <p>Below 1 is a usage error rather than a clamp to 1, so {@code --limit=0} is refused as the
     * server refuses {@code limit: 0} to {@code /api/sql}, in both modes alike.
     */
    int limit(int fallback, int max) {
        Long asked = optionalLong("limit");
        if (asked == null) {
            return fallback;
        }
        if (asked < 1) {
            throw new Usage("--limit must be at least 1: " + valueOrNull("limit"));
        }
        return Limits.clamp((int) Math.min(asked, max), fallback, max);
    }

    /**
     * A whole number the option must be, or null when it was not given.
     *
     * <p>Checked here, before either mode runs, rather than left to the server: the raw value
     * would travel over HTTP and be refused there, while the file path would truncate
     * {@code 1.5} to {@code 1} and answer for a threshold nobody typed.
     */
    @Nullable Long optionalLong(String key) {
        String value = valueOrNull(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new Usage("--" + key + " is not a whole number: " + value);
        }
    }

    /**
     * The option's value as the one of {@code allowed} it names, case aside, or null when it was
     * not given.
     *
     * <p>Checked before either mode runs, like {@link #optionalLong}: a value the server does not
     * know would otherwise narrow nothing, and {@code --severity=bogus} would print every line.
     */
    @Nullable String oneOf(String key, List<String> allowed) {
        String value = valueOrNull(key);
        if (value == null) {
            return null;
        }
        for (String known : allowed) {
            if (known.equalsIgnoreCase(value.trim())) {
                return known;
            }
        }
        throw new Usage("--" + key + " is one of " + String.join(", ", allowed) + ": " + value);
    }

    private double number(String key) {
        // Every caller asks has(key) first, so there is something to parse.
        String value = Objects.requireNonNull(valueOrNull(key), "--" + key + " was not given");
        double number;
        try {
            number = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new Usage("--" + key + " is not a number: " + value);
        }
        if (!Double.isFinite(number) || !value.trim().matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            throw new Usage("--" + key + " is not a number: " + value);
        }
        return number;
    }

    /**
     * The rules {@code check} was asked for, as the camelCase parameters of api.adoc#check.
     *
     * <p>An empty map means "no rule given", which both the server and
     * {@code Check} read as the default set; the CLI never fills it in itself, so
     * there is one definition of the defaults.
     */
    Map<String, Double> rules() {
        Map<String, Double> asked = new LinkedHashMap<>();
        RULES.forEach((flag, parameter) -> {
            if (has(flag)) {
                asked.put(parameter, number(flag));
            }
        });
        return asked;
    }

    private static Set<String> common() {
        Set<String> common = new LinkedHashSet<>(List.of("url", "json", "service"));
        common.addAll(SETTINGS);
        return Collections.unmodifiableSet(common);
    }

    /** Whether an option is one of those every command that reads a window takes. */
    static boolean isCommon(String key) {
        return COMMON.contains(key);
    }

    /** Whether an option is named bare rather than with a value. */
    static boolean isFlag(String key) {
        return FLAGS.contains(key);
    }

    /** The command's own options and the common ones, in that order, which its help keeps. */
    static Set<String> with(String... names) {
        Set<String> set = new LinkedHashSet<>(List.of(names));
        set.addAll(COMMON);
        return Collections.unmodifiableSet(set);
    }

    /** Exactly these options, in this order, for a command that takes none of the common ones. */
    static Set<String> only(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(names)));
    }

    /** The options of {@code check}: its rule flags, its window and its endpoint. */
    static String[] checkFlags() {
        List<String> flags = new ArrayList<>(RULES.keySet());
        flags.add("since");
        flags.add("until");
        flags.add("endpoint");
        return flags.toArray(new String[0]);
    }

    /** {@code --max-p95-ms} is {@code maxP95Ms}: the flag is the parameter, hyphenated. */
    private static Map<String, String> ruleParameters() {
        Map<String, String> byFlag = new LinkedHashMap<>();
        for (String rule : Check.RULES) {
            byFlag.put(hyphenate(rule), rule);
        }
        return Collections.unmodifiableMap(byFlag);
    }

    private static String hyphenate(String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
