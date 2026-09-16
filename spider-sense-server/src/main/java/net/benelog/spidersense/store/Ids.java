package net.benelog.spidersense.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** The id of one error group: a type and a message with its literals removed. */
    public static String errorId(String service, String type, String normalisedMessage) {
        return shortHash(service + "\0" + type + "\0" + normalisedMessage);
    }

    /**
     * A message with its literals removed, so {@code Order 42 is already shipped}
     * and {@code Order 43 is already shipped} are one error group: runs of digits
     * become {@code ?} and quoted strings become {@code '?'}.
     */
    public static String normaliseMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("'[^']*'", "'?'").replaceAll("\\d+", "?");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
