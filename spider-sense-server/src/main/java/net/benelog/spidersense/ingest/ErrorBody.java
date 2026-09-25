package net.benelog.spidersense.ingest;

import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
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

    public static Json.JsonObject json(@Nullable String message, String fallback) {
        return Json.obj().put("error", message(message, fallback));
    }

    /** The body as a JSON response with its status. */
    public static WebResponse response(HttpStatus status, @Nullable String message, String fallback) {
        return WebResponse.json(json(message, fallback)).status(status);
    }
}
