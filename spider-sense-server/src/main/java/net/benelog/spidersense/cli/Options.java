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

    /** A message for stderr, followed by the help table, and exit 2. */
    static final class Usage extends RuntimeException {
        Usage(String message) {
            super(message);
        }
    }

    static final String HELP = "help";
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
     * Options every command that reads a window takes: where to read from, and how to print it.
     *
     * <p>Here and not in {@link Command}, whose rows are built before a static field of its own is
     * set; nothing in this class's initialisation refers to {@code Command}, so either may load first.
     */
    private static final Set<String> COMMON = Set.of("url", "db", "json", "service",
            "slow.request.ms", "slow.query.ms", "app.packages");

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

    static Options parse(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()
                || args[0].startsWith("-")) {
            throw new Usage("a command is needed");
        }
        String command = args[0];
        Command row = Command.named(command);
        if (row == null) {
            throw new Usage("unknown command: " + command);
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
                    throw new Usage("unknown option for " + command + ": --" + key);
                }
                values.put(key, equals < 0 ? "true" : arg.substring(equals + 1));
            } else if (argument == null && label != null) {
                argument = arg;
            } else {
                throw new Usage("unexpected argument: " + arg);
            }
        }
        if (label != null && argument == null) {
            throw new Usage(command + " needs " + label);
        }
        if (COMPARE.equals(command) && !(values.containsKey("before") && values.containsKey("after"))) {
            throw new Usage("compare needs both --before and --after, as marks or time selectors");
        }
        return new Options(command, argument, values);
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

    /** The limit of a list, clamped the way the server clamps it (api.adoc). */
    int limit(int fallback, int max) {
        return Limits.clamp(has("limit") ? (int) number("limit") : null, fallback, max);
    }

    @Nullable Long optionalLong(String key) {
        return has(key) ? (long) number(key) : null;
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

    /** The common options and the command's own. */
    static Set<String> with(String... names) {
        Set<String> set = new LinkedHashSet<>(COMMON);
        set.addAll(List.of(names));
        return Set.copyOf(set);
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
