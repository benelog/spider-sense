package net.benelog.spidersense.query;

import java.math.BigDecimal;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The way a number is said, in one place.
 *
 * <p>cli.adoc#text-rendering fixes it: durations are milliseconds with one decimal and a
 * thousands separator ({@code 1,532.4 ms}), counts are integers, rates are
 * percentages with one decimal. A finding's {@code why} and the Markdown
 * renderings are read side by side, so they must not each have an opinion —
 * and the locale is always {@code US}, because the text has to be byte-identical
 * between two runs on two machines.
 */
public final class Numbers {

    /** The most decimals a limit, or a value judged against one, is said with. */
    private static final int MOST_DECIMALS = 12;

    private Numbers() {
    }

    /** Whether a value is there and is a number JSON and a reader can say: not null, NaN or infinite. */
    public static boolean finite(@Nullable Double value) {
        return value != null && Double.isFinite(value);
    }

    /** {@code 1,532.4 ms}; an absent duration is a dash. */
    public static String millis(@Nullable Double value) {
        return finite(value) ? number(value) + " ms" : "—";
    }

    /** {@code 1,532.4}: one decimal and a thousands separator. */
    public static String number(@Nullable Double value) {
        return finite(value) ? String.format(Locale.US, "%,.1f", value) : "—";
    }

    /** {@code 1,532}: a count is whole. */
    public static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** {@code 43.0%} from a fraction of one; an absent rate is a dash. */
    public static String percent(@Nullable Double fraction) {
        // The null test NullAway can see; finite() makes the same one.
        return fraction != null && finite(fraction)
                ? String.format(Locale.US, "%,.1f%%", fraction * 100) : "—";
    }

    /** {@code 1 call}, {@code 3 calls}: a count with the noun it counts. */
    public static String plural(long count, String singular) {
        return count(count) + " " + (count == 1 ? singular : singular + "s");
    }

    /** {@code 0.931}, the way an Apdex is written; {@code null} is a dash. */
    public static String score(@Nullable Double value) {
        return finite(value) ? String.format(Locale.US, "%.3f", value) : "—";
    }

    /**
     * {@code 1,532.45}: a value with {@code decimals} decimals and a thousands separator, and with
     * as many more as it takes, up to twelve, to read differently from {@code limit} when it is not
     * equal to it. 26 calls over 25 requests is {@code 1.04} beside a limit of 1, never the
     * {@code 1.0} that would read as the limit beside a fail (check.adoc#text-rendering).
     */
    public static String apart(double value, double limit, int decimals) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        int places = decimals;
        while (value != limit && places < MOST_DECIMALS && fixed(value, places).equals(fixed(limit, places))) {
            places++;
        }
        return fixed(value, places);
    }

    /**
     * A limit as it was given: at least {@code decimals} decimals, and as many more as the value
     * has, up to twelve, so a limit of {@code 0.0005} is never said as {@code 0.001}.
     */
    public static String exactly(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        int places = Math.min(MOST_DECIMALS,
                Math.max(decimals, BigDecimal.valueOf(value).stripTrailingZeros().scale()));
        return fixed(value, places);
    }

    /**
     * A fraction as the percentage it was given: the decimal point moves two places in the
     * decimal the fraction reads as, not in the double it is, so {@code 0.07} is {@code 7.0}
     * and never the {@code 7.000000000000} of {@code 0.07 * 100}; at least {@code decimals}
     * decimals and as many more as the percentage has, up to twelve, as {@link #exactly} says.
     */
    public static String exactlyPercent(double fraction, int decimals) {
        if (!Double.isFinite(fraction)) {
            return "—";
        }
        BigDecimal percent = BigDecimal.valueOf(fraction).movePointRight(2);
        int places = Math.min(MOST_DECIMALS, Math.max(decimals, percent.stripTrailingZeros().scale()));
        return String.format(Locale.US, "%,." + places + "f", percent);
    }

    private static String fixed(double value, int places) {
        return String.format(Locale.US, "%,." + places + "f", value);
    }
}
