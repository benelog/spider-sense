package net.benelog.spidersense;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.google.protobuf.ByteString;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

/** Builders for the OTLP messages the tests send, so a test reads as data rather than as protobuf. */
public final class Otlp {

    public static final String SCOPE = "io.opentelemetry.test";

    private Otlp() {
    }

    public static ByteString id(String hex) {
        return ByteString.copyFrom(HexFormat.of().parseHex(hex));
    }

    public static KeyValue attr(String key, String value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    public static KeyValue attr(String key, long value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setIntValue(value)).build();
    }

    public static Resource resource(KeyValue... attributes) {
        return Resource.newBuilder().addAllAttributes(List.of(attributes)).build();
    }

    public static Resource service(String name) {
        return resource(attr("service.name", name), attr("telemetry.sdk.language", "java"));
    }

    public static Span.Builder span(String traceId, String spanId, String name, Span.SpanKind kind,
            long startMs, long durationMs, KeyValue... attributes) {
        return Span.newBuilder()
                .setTraceId(id(traceId))
                .setSpanId(id(spanId))
                .setName(name)
                .setKind(kind)
                .setStartTimeUnixNano(startMs * 1_000_000L)
                .setEndTimeUnixNano((startMs + durationMs) * 1_000_000L)
                .addAllAttributes(List.of(attributes));
    }

    public static Span.Builder child(Span.Builder parent, String spanId, String name,
            Span.SpanKind kind, long startMs, long durationMs, KeyValue... attributes) {
        return span(HexFormat.of().formatHex(parent.getTraceId().toByteArray()), spanId, name, kind,
                startMs, durationMs, attributes)
                .setParentSpanId(parent.getSpanId());
    }

    public static Span.Builder failing(Span.Builder span, String type, String message, String stacktrace) {
        return span
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR)
                        .setMessage(message))
                .addEvents(Span.Event.newBuilder()
                        .setName("exception")
                        .setTimeUnixNano(span.getStartTimeUnixNano())
                        .addAttributes(attr("exception.type", type))
                        .addAttributes(attr("exception.message", message))
                        .addAttributes(attr("exception.stacktrace", stacktrace)));
    }

    public static ExportTraceServiceRequest traces(Resource resource, Span.Builder... spans) {
        return traces(resourceSpans(resource, spans));
    }

    /** One export carrying several resources, as a shared collector receives them. */
    public static ExportTraceServiceRequest traces(ResourceSpans.Builder... resources) {
        ExportTraceServiceRequest.Builder request = ExportTraceServiceRequest.newBuilder();
        for (ResourceSpans.Builder resource : resources) {
            request.addResourceSpans(resource);
        }
        return request.build();
    }

    public static ResourceSpans.Builder resourceSpans(Resource resource, Span.Builder... spans) {
        ScopeSpans.Builder scope = ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE));
        for (Span.Builder span : spans) {
            scope.addSpans(span);
        }
        return ResourceSpans.newBuilder().setResource(resource).addScopeSpans(scope);
    }

    public static ExportMetricsServiceRequest gauge(Resource resource, String name, String unit,
            long at, double value, KeyValue... attributes) {
        NumberDataPoint point = NumberDataPoint.newBuilder()
                .setTimeUnixNano(at * 1_000_000L)
                .setAsDouble(value)
                .addAllAttributes(List.of(attributes))
                .build();
        Metric metric = Metric.newBuilder().setName(name).setUnit(unit)
                .setGauge(Gauge.newBuilder().addDataPoints(point)).build();
        return metrics(resource, metric);
    }

    /** A sum; the connection-pool metrics are non-monotonic ones. */
    public static ExportMetricsServiceRequest sum(Resource resource, String name, String unit,
            long at, double value, boolean monotonic, KeyValue... attributes) {
        NumberDataPoint point = NumberDataPoint.newBuilder()
                .setTimeUnixNano(at * 1_000_000L)
                .setAsDouble(value)
                .addAllAttributes(List.of(attributes))
                .build();
        Metric metric = Metric.newBuilder().setName(name).setUnit(unit)
                .setSum(Sum.newBuilder()
                        .setIsMonotonic(monotonic)
                        .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                        .addDataPoints(point))
                .build();
        return metrics(resource, metric);
    }

    public static ExportMetricsServiceRequest histogram(Resource resource, String name, long at,
            long count, double sum, double[] bounds, long[] counts, KeyValue... attributes) {
        HistogramDataPoint.Builder point = HistogramDataPoint.newBuilder()
                .setTimeUnixNano(at * 1_000_000L)
                .setCount(count)
                .setSum(sum)
                .setMin(0)
                .setMax(sum)
                .addAllAttributes(List.of(attributes));
        for (double bound : bounds) {
            point.addExplicitBounds(bound);
        }
        for (long bucket : counts) {
            point.addBucketCounts(bucket);
        }
        Metric metric = Metric.newBuilder().setName(name)
                .setHistogram(Histogram.newBuilder()
                        .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                        .addDataPoints(point))
                .build();
        return metrics(resource, metric);
    }

    public static ExportMetricsServiceRequest metrics(Resource resource, Metric... metrics) {
        ScopeMetrics.Builder scope = ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE));
        for (Metric metric : metrics) {
            scope.addMetrics(metric);
        }
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().setResource(resource)
                        .addScopeMetrics(scope))
                .build();
    }

    public static ExportLogsServiceRequest logs(Resource resource, String logger,
            io.opentelemetry.proto.logs.v1.LogRecord... records) {
        ScopeLogs.Builder scope = ScopeLogs.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(logger));
        for (io.opentelemetry.proto.logs.v1.LogRecord record : records) {
            scope.addLogRecords(record);
        }
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().setResource(resource).addScopeLogs(scope))
                .build();
    }

    public static io.opentelemetry.proto.logs.v1.LogRecord log(long at, int severity, String body,
            String traceId, String spanId) {
        var record = io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
                .setTimeUnixNano(at * 1_000_000L)
                .setSeverityNumberValue(severity)
                .setBody(AnyValue.newBuilder().setStringValue(body))
                .addAttributes(attr("thread.name", "main"));
        if (traceId != null) {
            record.setTraceId(id(traceId)).setSpanId(id(spanId));
        }
        return record.build();
    }

    public static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
