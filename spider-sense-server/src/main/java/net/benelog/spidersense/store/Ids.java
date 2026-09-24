package net.benelog.spidersense.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Stable short identifiers for the groups the API exposes (endpoints, queries,
 * error groups).
 *
 * <p>The UI needs an id it can put in a URL and hand back, and the group key
 * itself ("service + SQL statement") is neither short nor URL-safe. A hash of
 * the key gives an id that is stable for the life of the process <em>and</em>
 * across restarts, which a counter would not be: a link to a query survives the
 * page reload that follows a redeploy of the monitored application.
 */
public final class Ids {

    private Ids() {
    }

    /** The first 12 hex characters of the SHA-256 of {@code key}. */
    public static String shortHash(String key) {
        byte[] digest = sha256(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
            hex.append(Character.forDigit(digest[i] & 0xf, 16));
        }
        return hex.toString();
    }

    /** The id of the endpoint named {@code name} in {@code service}. */
    public static String endpointId(String service, String name) {
        return shortHash(service + " " + name);
    }

    /** The id of one SQL (or other database) statement as one service issues it. */
    public static String queryId(String service, String system, String statement) {
        return shortHash(service + "\0" + system + "\0" + statement);
    }

    /** The id of an error group without an application frame: a type and a message with its literals removed. */
    public static String errorId(String service, String type, String normalisedMessage) {
        return shortHash(service + "\0" + type + "\0" + normalisedMessage);
    }

    /**
     * The id of the error group a failed span belongs to (design.adoc#errors).
     *
     * <p>The key is the root cause's type and the innermost application frame of
     * the chain, read from the root cause outwards: the same line throwing the same
     * exception is one error, whatever the wrapper around it says. The frame is the
     * default heuristic's, never {@code spidersense.app.packages}, so the id does not
     * change with a setting of whichever process wrote the span, and it loses its
     * file position and the numbers of synthetic names ({@code lambda$load$0},
     * {@code Orders$1}), so moving a line or adding a lambda above it keeps the
     * group. A trace without an application frame, or no trace at all, falls back
     * to {@link #errorId(String, String, String)} over the outer type and the
     * normalised message.
     */
    public static String errorId(String service, String type, @Nullable String message,
            @Nullable String stacktrace) {
        ExceptionChain chain = ExceptionChain.parse(stacktrace);
        String frame = chain.innermost(ExceptionChain::notFramework);
        if (frame == null) {
            return errorId(service, type, normaliseMessage(message));
        }
        String rootType = chain.rootType();
        return shortHash(service + "\0" + (rootType == null ? type : rootType) + "\0" + frameKey(frame));
    }

    /** A frame without its {@code (File.java:41)} and with {@code $<digits>} made {@code $?}. */
    static String frameKey(String frame) {
        int parenthesis = frame.indexOf('(');
        String method = parenthesis < 0 ? frame : frame.substring(0, parenthesis);
        return SYNTHETIC.matcher(method).replaceAll("\\$?");
    }

    private static final Pattern SYNTHETIC = Pattern.compile("\\$\\d+");

    /**
     * A message with its literals removed, so {@code Order 42 is already shipped}
     * and {@code Order 43 is already shipped} are one error group: runs of digits
     * become {@code ?} and quoted strings become {@code '?'}.
     */
    public static String normaliseMessage(@Nullable String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("'[^']*'", "'?'").replaceAll("\\d+", "?");
    }

    /**
     * A line with the numbers a loop varies taken out of it: every run of digits
     * becomes {@code ?}.
     *
     * <p>It is what makes {@code /api/books/155} and {@code /api/books/87} one
     * thing in three places — the trace diff aligns on it, the {@code
     * n-plus-one-http} rule groups on it and the aggregated hot spans do
     * (findings.adoc#hot-span) — so it is written here once rather than three times.
     */
    public static String normaliseDigits(@Nullable String line) {
        return line == null ? "" : DIGITS.matcher(line).replaceAll("?");
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
