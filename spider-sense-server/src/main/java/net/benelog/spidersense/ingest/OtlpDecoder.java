package net.benelog.spidersense.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

import net.benelog.spidersense.store.Batch;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Store;

/**
 * Turns OTLP protobuf messages into store records.
 *
 * <p>This is the only place that knows the wire format, so the store and the API
 * never see a protobuf class. Decoding runs on the request thread and produces a
 * {@link Batch}; the database is the writer's business. Decoding is total:
 * anything decodable is stored and nothing is reported back as partially
 * rejected, which is what the API contract promises.
 */
public final class OtlpDecoder {

    /** The name used when a resource carries no {@code service.name}, as the spec prescribes. */
    public static final String UNKNOWN_SERVICE = "unknown_service";

    private final Store store;
    private final IntSupplier ownPort;

    /**
     * @param ownPort the port this server is bound to, read late because
     *                {@code --port=0} is only resolved once Jetty has started
     */
    public OtlpDecoder(Store store, IntSupplier ownPort) {
        this.store = store;
        this.ownPort = ownPort;
    }

    // --- traces ---

    /** @return the batch that was queued, for tests that want to see what was decoded */
    public Batch accept(ExportTraceServiceRequest request) {
        long now = System.currentTimeMillis();
        Batch batch = new Batch();
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            Map<String, Object> resource = Attrs.toMap(resourceSpans.getResource().getAttributesList());
            String service = serviceName(resource);
            store.sawService(batch, service, resource, now);
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                String scope = scopeSpans.getScope().getName();
                for (Span span : scopeSpans.getSpansList()) {
                    SpanRecord record = toRecord(span, service, scope);
                    if (isOurOwnTraffic(record)) {
                        continue;
                    }
                    batch.add(record);
                    batch.addTingles(store.tingles().of(record));
                }
            }
        }
        store.submit(batch);
        return batch;
    }

    /**
     * Belt and braces against the UI monitoring itself: the OpenTelemetry agent is
     * already told to ignore the server's class loader, but a misconfiguration
     * would otherwise fill the dashboard with our own requests. A span aimed at our
     * own port, from the process we live in, is ours.
     */
    private boolean isOurOwnTraffic(SpanRecord span) {
        Long port = span.serverPort();
        if (port == null || port.intValue() != ownPort.getAsInt()) {
            return false;
        }
        String embedded = store.services().embeddedService();
        return embedded == null || embedded.equals(span.service());
    }

    private static SpanRecord toRecord(Span span, String service, String scope) {
        List<SpanRecord.SpanEvent> events = new ArrayList<>(span.getEventsCount());
        for (Span.Event event : span.getEventsList()) {
            events.add(new SpanRecord.SpanEvent(event.getName(), event.getTimeUnixNano(),
                    Attrs.toMap(event.getAttributesList())));
        }
        Status status = span.getStatus();
        return new SpanRecord(
                Attrs.hex(span.getTraceId()),
                Attrs.hex(span.getSpanId()),
                Attrs.hex(span.getParentSpanId()),
                service,
                span.getName(),
                kind(span.getKind()),
                span.getStartTimeUnixNano(),
                span.getEndTimeUnixNano(),
                statusCode(status.getCode()),
                status.getMessage().isEmpty() ? null : status.getMessage(),
                Attrs.toMap(span.getAttributesList()),
                List.copyOf(events),
                scope);
    }

    private static String kind(Span.SpanKind kind) {
        return switch (kind) {
            case SPAN_KIND_SERVER -> "SERVER";
            case SPAN_KIND_CLIENT -> "CLIENT";
            case SPAN_KIND_PRODUCER -> "PRODUCER";
            case SPAN_KIND_CONSUMER -> "CONSUMER";
            default -> "INTERNAL";
        };
    }

    private static String statusCode(Status.StatusCode code) {
        return switch (code) {
            case STATUS_CODE_OK -> "OK";
            case STATUS_CODE_ERROR -> "ERROR";
            default -> "UNSET";
        };
    }

    // --- metrics ---

    public Batch accept(ExportMetricsServiceRequest request) {
        long now = System.currentTimeMillis();
        Batch batch = new Batch();
        for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
            Map<String, Object> resource = Attrs.toMap(resourceMetrics.getResource().getAttributesList());
            String service = serviceName(resource);
            store.sawService(batch, service, resource, now);
            for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
                for (Metric metric : scopeMetrics.getMetricsList()) {
                    accept(batch, service, metric);
                }
            }
        }
        store.submit(batch);
        return batch;
    }

    private void accept(Batch batch, String service, Metric metric) {
        String name = metric.getName();
        String unit = metric.getUnit();
        String description = metric.getDescription();
        switch (metric.getDataCase()) {
            case GAUGE -> {
                for (NumberDataPoint point : metric.getGauge().getDataPointsList()) {
                    batch.add(new Batch.MetricSample(service, name, "gauge", unit, description, false,
                            "UNSPECIFIED", Attrs.toMap(point.getAttributesList()), number(point)));
                }
            }
            case SUM -> {
                var sum = metric.getSum();
                String temporality = temporality(sum.getAggregationTemporality());
                for (NumberDataPoint point : sum.getDataPointsList()) {
                    batch.add(new Batch.MetricSample(service, name, "sum", unit, description,
                            sum.getIsMonotonic(), temporality, Attrs.toMap(point.getAttributesList()),
                            number(point)));
                }
            }
            case HISTOGRAM -> {
                var histogram = metric.getHistogram();
                String temporality = temporality(histogram.getAggregationTemporality());
                for (HistogramDataPoint point : histogram.getDataPointsList()) {
                    batch.add(new Batch.MetricSample(service, name, "histogram", unit, description, false,
                            temporality, Attrs.toMap(point.getAttributesList()), histogram(point)));
                }
            }
            case EXPONENTIAL_HISTOGRAM -> {
                var histogram = metric.getExponentialHistogram();
                String temporality = temporality(histogram.getAggregationTemporality());
                for (ExponentialHistogramDataPoint point : histogram.getDataPointsList()) {
                    batch.add(new Batch.MetricSample(service, name, "histogram", unit, description, false,
                            temporality, Attrs.toMap(point.getAttributesList()), exponential(point)));
                }
            }
            default -> {
                // Summary and the development signals are not drawn anywhere; ignore them.
            }
        }
    }

    private static MetricPoint number(NumberDataPoint point) {
        double value = point.getValueCase() == NumberDataPoint.ValueCase.AS_INT
                ? point.getAsInt()
                : point.getAsDouble();
        return MetricPoint.number(millis(point.getTimeUnixNano()), value);
    }

    private static MetricPoint histogram(HistogramDataPoint point) {
        long[] counts = new long[point.getBucketCountsCount()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = point.getBucketCounts(i);
        }
        double[] bounds = new double[point.getExplicitBoundsCount()];
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = point.getExplicitBounds(i);
        }
        return MetricPoint.histogram(millis(point.getTimeUnixNano()), point.getCount(), point.getSum(),
                point.hasMin() ? point.getMin() : 0, point.hasMax() ? point.getMax() : 0,
                counts.length > 0 ? counts : null, counts.length > 0 ? bounds : null);
    }

    /**
     * An exponential histogram keeps count, sum, min and max only. Rebuilding its
     * buckets on the explicit scale would be a page of code for a number the UI
     * draws as a mean and a maximum anyway.
     */
    private static MetricPoint exponential(ExponentialHistogramDataPoint point) {
        return MetricPoint.histogram(millis(point.getTimeUnixNano()), point.getCount(), point.getSum(),
                point.hasMin() ? point.getMin() : 0, point.hasMax() ? point.getMax() : 0, null, null);
    }

    private static String temporality(AggregationTemporality temporality) {
        return switch (temporality) {
            case AGGREGATION_TEMPORALITY_DELTA -> "DELTA";
            case AGGREGATION_TEMPORALITY_CUMULATIVE -> "CUMULATIVE";
            default -> "UNSPECIFIED";
        };
    }

    // --- logs ---

    public Batch accept(ExportLogsServiceRequest request) {
        long now = System.currentTimeMillis();
        Batch batch = new Batch();
        for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
            Map<String, Object> resource = Attrs.toMap(resourceLogs.getResource().getAttributesList());
            String service = serviceName(resource);
            store.sawService(batch, service, resource, now);
            for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
                String logger = scopeLogs.getScope().getName();
                for (io.opentelemetry.proto.logs.v1.LogRecord record : scopeLogs.getLogRecordsList()) {
                    batch.add(toRecord(record, service, logger, now));
                }
            }
        }
        store.submit(batch);
        return batch;
    }

    /** The id is 0 here: the {@code log} table assigns it, and the reads report what it assigned. */
    private static LogRecord toRecord(io.opentelemetry.proto.logs.v1.LogRecord record, String service,
            String logger, long now) {
        long at = record.getTimeUnixNano() != 0
                ? millis(record.getTimeUnixNano())
                : record.getObservedTimeUnixNano() != 0 ? millis(record.getObservedTimeUnixNano()) : now;
        int severityNumber = record.getSeverityNumberValue();
        return new LogRecord(
                0,
                at,
                service,
                LogRecord.severityText(severityNumber),
                severityNumber,
                Attrs.bodyText(record.getBody()),
                logger,
                Attrs.hex(record.getTraceId()),
                Attrs.hex(record.getSpanId()),
                Attrs.toMap(record.getAttributesList()));
    }

    // --- shared ---

    private static String serviceName(Map<String, Object> resource) {
        Object name = resource.get("service.name");
        return name == null ? UNKNOWN_SERVICE : String.valueOf(name);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
