package net.benelog.spidersense.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import net.benelog.spidersense.store.Writer;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The OTLP/HTTP endpoints: {@code POST /v1/traces}, {@code /v1/metrics},
 * {@code /v1/logs}.
 *
 * <p>Both encodings the OpenTelemetry exporters use are accepted, gzipped or not,
 * and the answer is the empty {@code Export*ServiceResponse} in the request's own
 * encoding — which is what an exporter checks before it considers the batch
 * delivered. There is no gRPC receiver; see design.adoc#rejected.
 */
public final class OtlpReceiver {

    private static final String PROTOBUF = "application/x-protobuf";
    private static final String JSON = "application/json";

    /**
     * Set by the tests: ingest is write-behind, so a test that POSTs and then GETs
     * would otherwise race the writer thread. Never set in production, where the
     * whole point of the queue is that the request does not wait for the disk.
     */
    private static final String SYNC_PROPERTY = "spidersense.sync";

    private final OtlpDecoder decoder;
    private final Writer writer;

    public OtlpReceiver(OtlpDecoder decoder, Writer writer) {
        this.decoder = decoder;
        this.writer = writer;
    }

    public void register(App app) {
        app.post("/v1/traces", "OTLP trace ingest", this::traces);
        app.post("/v1/metrics", "OTLP metric ingest", this::metrics);
        app.post("/v1/logs", "OTLP log ingest", this::logs);
    }

    public WebResponse traces(WebRequest req) {
        String encoding = encodingOf(req);
        if (encoding == null) {
            return unsupported(req);
        }
        ExportTraceServiceRequest.Builder request = ExportTraceServiceRequest.newBuilder();
        try {
            parse(req, encoding, request);
        } catch (InvalidProtocolBufferException e) {
            return undecodable(e);
        }
        decoder.accept(request.build());
        flushIfSynchronous();
        return ok(encoding, ExportTraceServiceResponse.getDefaultInstance());
    }

    public WebResponse metrics(WebRequest req) {
        String encoding = encodingOf(req);
        if (encoding == null) {
            return unsupported(req);
        }
        ExportMetricsServiceRequest.Builder request = ExportMetricsServiceRequest.newBuilder();
        try {
            parse(req, encoding, request);
        } catch (InvalidProtocolBufferException e) {
            return undecodable(e);
        }
        decoder.accept(request.build());
        flushIfSynchronous();
        return ok(encoding, ExportMetricsServiceResponse.getDefaultInstance());
    }

    public WebResponse logs(WebRequest req) {
        String encoding = encodingOf(req);
        if (encoding == null) {
            return unsupported(req);
        }
        ExportLogsServiceRequest.Builder request = ExportLogsServiceRequest.newBuilder();
        try {
            parse(req, encoding, request);
        } catch (InvalidProtocolBufferException e) {
            return undecodable(e);
        }
        decoder.accept(request.build());
        flushIfSynchronous();
        return ok(encoding, ExportLogsServiceResponse.getDefaultInstance());
    }

    private void flushIfSynchronous() {
        if (Boolean.getBoolean(SYNC_PROPERTY)) {
            writer.awaitIdle(5_000);
        }
    }

    private void parse(WebRequest req, String encoding, Message.Builder builder)
            throws InvalidProtocolBufferException {
        byte[] body = body(req);
        if (PROTOBUF.equals(encoding)) {
            builder.mergeFrom(body);
        } else {
            OtlpJson.merge(new String(body, StandardCharsets.UTF_8), builder);
        }
    }

    private static byte[] body(WebRequest req) {
        try (InputStream in = gzipped(req) ? new GZIPInputStream(req.bodyStream()) : req.bodyStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean gzipped(WebRequest req) {
        String encoding = req.header("Content-Encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
    }

    /** The request's encoding, or null when it is one we do not speak. */
    private static @Nullable String encodingOf(WebRequest req) {
        String contentType = req.contentType();
        if (contentType == null) {
            return null;
        }
        int parameters = contentType.indexOf(';');
        String type = (parameters < 0 ? contentType : contentType.substring(0, parameters))
                .trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case PROTOBUF, "application/protobuf" -> PROTOBUF;
            case JSON -> JSON;
            default -> null;
        };
    }

    private static WebResponse ok(String encoding, Message response) {
        if (PROTOBUF.equals(encoding)) {
            return WebResponse.bytes(PROTOBUF, response.toByteArray());
        }
        try {
            return WebResponse.json(JsonFormat.printer().omittingInsignificantWhitespace().print(response));
        } catch (InvalidProtocolBufferException e) {
            // An empty message always prints; this cannot happen.
            return WebResponse.json("{}");
        }
    }

    private static WebResponse unsupported(WebRequest req) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Send OTLP as application/x-protobuf or application/json, not " + req.contentType());
    }

    private static WebResponse undecodable(InvalidProtocolBufferException e) {
        return error(HttpStatus.BAD_REQUEST, "Undecodable OTLP body: " + e.getMessage());
    }

    private static WebResponse error(HttpStatus status, String message) {
        return WebResponse.json(Json.obj().put("error", message)).status(status);
    }
}
