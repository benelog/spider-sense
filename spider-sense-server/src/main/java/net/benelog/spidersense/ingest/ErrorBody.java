package net.benelog.spidersense.ingest;

import java.util.Locale;

import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebResponse;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * The {@code {"error": "<message>"}} body every failure answers with (api.adoc#conventions).
 *
 * <p>It sits beside {@link RequestBody}, where the OTLP receiver, the API handlers and the server's
 * own filters can all reach it without the ingest and server packages reading the API's codecs.
 * A message that is null or blank says nothing, so the caller's fallback is said instead.
 */
public final class ErrorBody {

    private ErrorBody() {
    }

    /** The message, or the fallback when it is null or blank. */
    public static String message(@Nullable String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    public static JsonObject json(@Nullable String message, String fallback) {
        return Json.object().put("error", message(message, fallback));
    }

    /**
     * What a status is called, the fallback of an error that said nothing:
     * {@code CONTENT_TOO_LARGE} is {@code Content too large}.
     */
    public static String reason(HttpStatus status) {
        String words = status.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The body as a JSON response with its status. */
    public static WebResponse response(HttpStatus status, @Nullable String message, String fallback) {
        return WebResponse.json(json(message, fallback)).status(status);
    }
}
