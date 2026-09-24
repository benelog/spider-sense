package net.benelog.spidersense.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import net.benelog.spidersilk.WebRequest;

/**
 * A request body read whole, gunzipped when {@code Content-Encoding} says so, up to a cap.
 *
 * <p>The OTLP endpoints and {@code /api/import} read their body as a stream, past
 * Spider Silk's body limit, and in agent mode they read it into the monitored
 * application's heap. The cap is on the bytes after gunzip, so a small
 * compressed body cannot expand into an OutOfMemoryError there.
 */
public final class RequestBody {

    private RequestBody() {
    }

    /** A body past its cap; the endpoints answer it with {@code 413}. */
    public static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int max;

        TooLarge(int max) {
            this.max = max;
        }

        @Override
        public String getMessage() {
            return "The body is larger than " + max / (1024 * 1024) + " MB";
        }
    }

    /**
     * The body's bytes.
     *
     * @throws TooLarge when there are more than {@code max} of them
     */
    public static byte[] read(WebRequest req, int max) {
        try (InputStream in = gzipped(req) ? new GZIPInputStream(req.bodyStream()) : req.bodyStream()) {
            byte[] bytes = in.readNBytes(max);
            if (bytes.length == max && in.read() >= 0) {
                throw new TooLarge(max);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean gzipped(WebRequest req) {
        String encoding = req.header("Content-Encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
    }
}
