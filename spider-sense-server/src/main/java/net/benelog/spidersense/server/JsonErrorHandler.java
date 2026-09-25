package net.benelog.spidersense.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.benelog.spidersense.ingest.ErrorBody;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.jspecify.annotations.Nullable;

/**
 * What Jetty answers for a request it refuses before any route sees it: a path segment it deems
 * ambiguous ({@code %5C}, {@code %2E%2E}) or a request line it cannot parse.
 *
 * <p>Jetty's own handler writes an HTML page; the API answers every error as
 * {@code {"error": "<message>"}} (api.adoc#conventions), and the UI and an agent read that shape,
 * so this one writes it too, with Jetty's status and Jetty's reason as the message.
 */
final class JsonErrorHandler extends ErrorHandler {

    @Override
    protected void generateResponse(Request request, Response response, int code,
            @Nullable String message, @Nullable Throwable cause, Callback callback) throws IOException {
        String body = ErrorBody.json(message, "HTTP " + code).toJson();
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8");
        response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
    }
}
