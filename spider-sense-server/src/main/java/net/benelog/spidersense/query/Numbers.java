package net.benelog.spidersense.query;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The way a number is said, in one place.
 *
 * <p>agent.md fixes it: durations are milliseconds with one decimal and a
 * thousands separator ({@code 1,532.4 ms}), counts are integers, rates are
 * percentages with one decimal. A finding's {@code why} and the Markdown
 * renderings are read side by side, so they must not each have an opinion —
 * and the locale is always {@code US}, because the text has to be byte-identical
 * between two runs on two machines.
 */
public final class Numbers {

    private Numbers() {
    }

    /** {@code 1,532.4 ms}; an absent duration is a dash. */
    public static String millis(@Nullable Double value) {
        return value == null || value.isNaN() || value.isInfinite() ? "—" : number(value) + " ms";
    }

    /** {@code 1,532.4}: one decimal and a thousands separator. */
    public static String number(@Nullable Double value) {
        return value == null || value.isNaN() || value.isInfinite()
                ? "—" : String.format(Locale.US, "%,.1f", value);
    }

    /** {@code 1,532}: a count is whole. */
    public static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** {@code 43.0%} from a fraction of one; an absent rate is a dash. */
    public static String percent(@Nullable Double fraction) {
        return fraction == null || fraction.isNaN() || fraction.isInfinite()
                ? "—" : String.format(Locale.US, "%,.1f%%", fraction * 100);
    }

    /** {@code 1 call}, {@code 3 calls}: a count with the noun it counts. */
    public static String plural(long count, String singular) {
        return count(count) + " " + (count == 1 ? singular : singular + "s");
    }

    /** {@code 0.931}, the way an Apdex is written; {@code null} is a dash. */
    public static String score(@Nullable Double value) {
        return value == null || value.isNaN() || value.isInfinite()
                ? "—" : String.format(Locale.US, "%.3f", value);
    }
}
