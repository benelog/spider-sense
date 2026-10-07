package net.benelog.spidersense.query;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.benelog.spidersense.store.Marks;
import org.jspecify.annotations.Nullable;

/**
 * The time selectors of the agent interface: {@code since=15m}, {@code until=now},
 * {@code since=before}, {@code since=start}, {@code since=2026-10-08T05:50:00}.
 *
 * <p>An agent thinks in "since I changed the code", not in epoch milliseconds, so
 * every agent-facing endpoint takes a selector where the UI takes {@code from} and
 * {@code to} (marks-and-compare.adoc#time-selectors). The two kinds of failure are told
 * apart on purpose:
 * something that is not a selector at all is the caller's mistake ({@code 400}),
 * while a well-formed name that matches no mark is a question about data
 * ({@code 404}), and an agent reacts differently to the two.
 */
public final class Selectors {

    /** What {@code since} means when nobody said. */
    public static final String DEFAULT_SINCE = "15m";

    /**
     * The three moments a {@code compare} splits into two windows: {@code [before, after)} and
     * {@code [after, until)}.
     */
    public record CompareBounds(long before, long after, long until) {
    }

    /**
     * A selector that is not one of the forms marks-and-compare.adoc#time-selectors lists: a
     * {@code 400}.
     */
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

    /**
     * What begins as an ISO-8601 date-time, which no mark name can be: a colon is not
     * one of its characters, so the time a heading prints can be pasted back.
     */
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*");

    /** How a refusal names an instant: the form a heading prints, which a selector reads back. */
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final Marks marks;
    private final LongSupplier clock;

    /** Selectors whose {@code now} is the wall clock. */
    public Selectors(Marks marks) {
        this(marks, System::currentTimeMillis);
    }

    /**
     * @param clock what {@code now} is, in epoch milliseconds; a window reads it once,
     *        so its {@code until=now} and the anchor its {@code since} counts back from
     *        are the same instant
     */
    public Selectors(Marks marks, LongSupplier clock) {
        this.marks = marks;
        this.clock = clock;
    }

    /**
     * One selector as an instant.
     *
     * @param anchor what a duration counts back from ({@code until} for a
     *        {@code since}, now for an {@code until})
     * @param service the service a {@code start} (or any other mark) is preferred
     *        from, or null
     */
    public long resolve(@Nullable String selector, long anchor, @Nullable String service) {
        return resolve(selector, anchor, clock.getAsLong(), service);
    }

    /** The same, with {@code now} already read, as a window reads it once for both ends. */
    private long resolve(@Nullable String selector, long anchor, long now, @Nullable String service) {
        String value = selector == null ? null : selector.trim();
        if (value == null || value.isEmpty()) {
            throw new BadSelector("An empty time selector: expected a duration, a date-time,"
                    + " epoch milliseconds, now, start or a mark name");
        }
        if ("now".equals(value)) {
            return now;
        }
        Matcher duration = DURATION.matcher(value);
        if (duration.matches()) {
            return anchor - Long.parseLong(duration.group(1)) * unitMillis(duration.group(2).charAt(0));
        }
        if (EPOCH.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new BadSelector("Epoch milliseconds out of range: " + value);
            }
        }
        if (DATE_TIME.matcher(value).matches()) {
            return dateTime(value);
        }
        if (Marks.NUMBER_LIKE.matcher(value).matches()) {
            // 5min, 1w, 30sec, 0: a duration mistyped, which a mark lookup would answer as "no
            // mark named 5min", a not-found that sends the caller looking for a mark.
            throw new BadSelector("Not a duration: " + value + " (a duration is 30s, 5m, 2h or 1d"
                    + (value.chars().allMatch(Character::isDigit)
                            ? ", and epoch milliseconds have 13 or more digits" : "")
                    + "; `marks` lists the mark names)");
        }
        if (!Marks.NAME.matcher(value).matches()) {
            throw new BadSelector("Not a time selector: " + value
                    + " (expected a duration like 5m, a date-time like 2026-10-08T05:50:00,"
                    + " epoch milliseconds, now, start or a mark name)");
        }
        Marks.Mark mark = marks.newest(value, service);
        if (mark == null) {
            throw new UnknownMark(Marks.START.equals(value) ? NO_START_MARK
                    : "No mark named " + value + (service == null ? "" : " for service " + service)
                            + "; `mark " + value + "` records one, `marks` lists them");
        }
        return mark.at();
    }

    /**
     * What {@code since=start} answers before any start mark exists: what writes one, and what
     * to ask instead, since "No mark named start" reads as a typo of a mark nobody chose
     * (marks-and-compare.adoc#start-marks).
     */
    static final String NO_START_MARK = "No start mark yet: Spider Sense writes one when a service"
            + " reports a process id it has not seen, which an application under"
            + " -javaagent:spider-sense.jar does as it starts; a service sending from its own SDK"
            + " writes one only when the SDK reports process.pid. Use --since=15m, or name a moment"
            + " with `mark before` and use --since=before";

    /**
     * An ISO-8601 date-time as an instant: with an offset or {@code Z} as written, and
     * without one in this process's time zone, which is the zone the text renderings print
     * their times in (marks-and-compare.adoc#time-selectors).
     *
     * <p>A space is read as the plus of an offset, because an unencoded {@code +09:00} in a
     * query string arrives as {@code  09:00}, and a date-time has no space of its own.
     */
    private static long dateTime(String written) {
        String value = written.replace(' ', '+');
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException withOffset) {
            try {
                return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException local) {
                throw new BadSelector("Not a date-time: " + written + " (a date-time is 2026-10-08T05:50:00,"
                        + " with an offset such as +09:00 or Z, or without one in this machine's time zone)");
            }
        }
    }

    /**
     * A selector duration as a length of time rather than as an instant:
     * {@code tail --timeout=30s} is how long to watch, not when to start
     * (marks-and-compare.adoc#time-selectors).
     *
     * <p>Only the duration form is one: a mark or {@code now} names a moment, and
     * a moment is not a timeout.
     */
    public static long durationMillis(@Nullable String selector) {
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
     * builds keeps working unchanged (api.adoc#time-selectors).
     */
    public Window window(@Nullable Long from, @Nullable Long to, @Nullable String since,
            @Nullable String until, @Nullable String service) {
        long now = clock.getAsLong();
        long end = to != null ? to : (until == null ? now : resolve(until, now, now, service));
        long start = from != null ? from
                : resolve(since == null ? DEFAULT_SINCE : since, end, now, service);
        if (start > end) {
            throw new BadSelector("since resolves to " + local(start) + ", which is after until " + local(end));
        }
        return Window.of(start, end);
    }

    /**
     * The moments of a {@code compare}, resolved the one way every transport resolves them
     * (marks-and-compare.adoc#compare): the end first, {@code now} when {@code until} is unsaid,
     * then {@code after} counted back from it, then {@code before} counted back from that, so
     * {@code --before=10m --after=5m} reads left to right. {@code now} is read once.
     *
     * <p>Whether the moments are in order is the caller's to judge, since a window that is
     * empty or backwards is refused in the words of the answer it would have been.
     */
    public CompareBounds compareBounds(@Nullable String before, @Nullable String after,
            @Nullable String until, @Nullable String service) {
        long now = clock.getAsLong();
        long untilAt = until == null ? now : resolve(until, now, now, service);
        long afterAt = resolve(after, untilAt, now, service);
        long beforeAt = resolve(before, afterAt, now, service);
        return new CompareBounds(beforeAt, afterAt, untilAt);
    }

    /**
     * The mark a selector names, or null when the selector is another form or no mark has the
     * name: what a refusal shows beside the instant, such as which service a {@code start} is of.
     */
    public Marks.@Nullable Mark mark(@Nullable String selector, @Nullable String service) {
        String value = selector == null ? "" : selector.trim();
        if (value.isEmpty() || "now".equals(value) || DURATION.matcher(value).matches()
                || Marks.NUMBER_LIKE.matcher(value).matches() || DATE_TIME.matcher(value).matches()
                || !Marks.NAME.matcher(value).matches()) {
            return null;
        }
        return marks.newest(value, service);
    }

    /** {@code 2026-10-08T05:50:00+09:00}: an instant in this machine's zone, as a heading prints it. */
    public static String local(long at) {
        return LOCAL.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
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
