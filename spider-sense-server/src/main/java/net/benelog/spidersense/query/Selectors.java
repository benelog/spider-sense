package net.benelog.spidersense.query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.benelog.spidersense.store.Marks;

/**
 * The time selectors of the agent interface: {@code since=15m}, {@code until=now},
 * {@code since=before}, {@code since=start}.
 *
 * <p>An agent thinks in "since I changed the code", not in epoch milliseconds, so
 * every agent-facing endpoint takes a selector where the UI takes {@code from} and
 * {@code to} (agent.md). The two kinds of failure are told apart on purpose:
 * something that is not a selector at all is the caller's mistake ({@code 400}),
 * while a well-formed name that matches no mark is a question about data
 * ({@code 404}), and an agent reacts differently to the two.
 */
public final class Selectors {

    /** What {@code since} means when nobody said. */
    public static final String DEFAULT_SINCE = "15m";

    /** A selector that is not one of the forms agent.md lists: a {@code 400}. */
    public static final class BadSelector extends RuntimeException {
        public BadSelector(String message) {
            super(message);
        }
    }

    /** A well-formed name that matches no mark: a {@code 404}. */
    public static final class UnknownMark extends RuntimeException {
        public UnknownMark(String message) {
            super(message);
        }
    }

    private static final Pattern DURATION = Pattern.compile("(\\d{1,9})([smhd])");
    private static final Pattern EPOCH = Pattern.compile("\\d{13,}");

    private final Marks marks;

    public Selectors(Marks marks) {
        this.marks = marks;
    }

    /**
     * One selector as an instant.
     *
     * @param anchor what a duration counts back from ({@code until} for a
     *        {@code since}, now for an {@code until})
     * @param service the service a {@code start} (or any other mark) is preferred
     *        from, or null
     */
    public long resolve(String selector, long anchor, String service) {
        String value = selector == null ? null : selector.trim();
        if (value == null || value.isEmpty()) {
            throw new BadSelector("An empty time selector: expected a duration, epoch milliseconds,"
                    + " now, start or a mark name");
        }
        if ("now".equals(value)) {
            return System.currentTimeMillis();
        }
        Matcher duration = DURATION.matcher(value);
        if (duration.matches()) {
            return anchor - Long.parseLong(duration.group(1)) * unitMillis(duration.group(2).charAt(0));
        }
        if (EPOCH.matcher(value).matches()) {
            return Long.parseLong(value);
        }
        if (!Marks.NAME.matcher(value).matches()) {
            throw new BadSelector("Not a time selector: " + value
                    + " (expected a duration like 5m, epoch milliseconds, now, start or a mark name)");
        }
        Marks.Mark mark = marks.newest(value, service);
        if (mark == null) {
            throw new UnknownMark("No mark named " + value
                    + (service == null ? "" : " for service " + service));
        }
        return mark.at();
    }

    /**
     * A selector duration as a length of time rather than as an instant:
     * {@code tail --timeout=30s} is how long to watch, not when to start
     * (agent.md).
     *
     * <p>Only the duration form is one: a mark or {@code now} names a moment, and
     * a moment is not a timeout.
     */
    public static long durationMillis(String selector) {
        String value = selector == null ? null : selector.trim();
        Matcher duration = value == null ? null : DURATION.matcher(value);
        if (duration == null || !duration.matches()) {
            throw new BadSelector("Not a duration: " + selector
                    + " (expected one of 30s, 5m, 2h, 1d)");
        }
        return Long.parseLong(duration.group(1)) * unitMillis(duration.group(2).charAt(0));
    }

    /**
     * The window an agent-facing request asks for.
     *
     * <p>{@code from} and {@code to} win when both are given, so every URL the UI
     * builds keeps working unchanged (api.md).
     */
    public Window window(Long from, Long to, String since, String until, String service) {
        long now = System.currentTimeMillis();
        long end = to != null ? to : (until == null ? now : resolve(until, now, service));
        long start = from != null ? from
                : resolve(since == null ? DEFAULT_SINCE : since, end, service);
        if (start > end) {
            throw new BadSelector("since resolves to " + start + ", which is after until " + end);
        }
        return Window.of(start, end);
    }

    private static long unitMillis(char unit) {
        return switch (unit) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            default -> 86_400_000L;
        };
    }
}
