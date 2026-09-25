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
import io.opentelemetry.proto.metrics.v1.DataPointFlags;
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
import org.jspecify.annotations.Nullable;

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

    /**
     * Decodes one trace export and queues it.
     *
     * @return the batch that was queued, for tests that want to see what was decoded
     */
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
                    if (record == null || isOurOwnTraffic(record)) {
                        continue;
                    }
                    // The ingest cap decides before the writer sees anything (storage.adoc#ingest-cap).
                    if (!store.ingestCap().accept(record.traceId())) {
                        continue;
                    }
                    batch.add(record);
                    batch.addTingles(store.tingles().raisedBy(record));
                }
            }
        }
        store.submit(batch);
        return batch;
    }

    /**
     * Belt and braces against the UI monitoring itself: the OpenTelemetry agent is
     * already told to ignore the server's class loader, but a misconfiguration
     * would otherwise fill the dashboard with our own requests.
     *
     * <p>Only {@code SERVER} spans are dropped — one of <em>our</em> requests being
     * served. A {@code CLIENT} span aimed at the same port is an application
     * genuinely calling Spider Sense (posting OTLP, reading the API), and that call
     * belongs in its trace.
     *
     * <p>When the embedded service is not known yet, any service counts: a span
     * served on our own port cannot be anyone else's work.
     */
    private boolean isOurOwnTraffic(SpanRecord span) {
        if (!"SERVER".equals(span.kind())) {
            return false;
        }
        Long port = span.serverPort();
        if (port == null || port.intValue() != ownPort.getAsInt()) {
            return false;
        }
        String embedded = store.services().embeddedService();
        return embedded == null || embedded.equals(span.service());
    }

    /**
     * The record of a span, or null for a span without a valid trace or span id.
     *
     * <p>OTLP requires both ids of a span; they are the store's key, and a span
     * without them could not be written or joined to anything. Such a span is
     * skipped rather than refused, so the rest of its export is still stored.
     * A parent id that is not a valid span id is read as none.
     */
    private static @Nullable SpanRecord toRecord(Span span, String service, String scope) {
        String traceId = Attrs.traceId(span.getTraceId());
        String spanId = Attrs.spanId(span.getSpanId());
        if (traceId == null || spanId == null) {
            return null;
        }
        List<SpanRecord.SpanEvent> events = new ArrayList<>(span.getEventsCount());
        for (Span.Event event : span.getEventsList()) {
            events.add(new SpanRecord.SpanEvent(event.getName(), event.getTimeUnixNano(),
                    Attrs.toMap(event.getAttributesList())));
        }
        Status status = span.getStatus();
        return new SpanRecord(
                traceId,
                spanId,
                Attrs.spanId(span.getParentSpanId()),
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
        String name = fit(metric.getName());
        String unit = metric.getUnit();
        String description = metric.getDescription();
        switch (metric.getDataCase()) {
            case GAUGE -> {
                for (NumberDataPoint point : metric.getGauge().getDataPointsList()) {
                    if (noValue(point)) {
                        continue;
                    }
                    batch.add(new Batch.MetricSample(service, name, "gauge", unit, description, false,
                            "UNSPECIFIED", Attrs.toMap(point.getAttributesList()), number(point)));
                }
            }
            case SUM -> {
                var sum = metric.getSum();
                String temporality = temporality(sum.getAggregationTemporality());
                for (NumberDataPoint point : sum.getDataPointsList()) {
                    if (noValue(point)) {
                        continue;
                    }
                    batch.add(new Batch.MetricSample(service, name, "sum", unit, description,
                            sum.getIsMonotonic(), temporality, Attrs.toMap(point.getAttributesList()),
                            number(point)));
                }
            }
            case HISTOGRAM -> {
                var histogram = metric.getHistogram();
                String temporality = temporality(histogram.getAggregationTemporality());
                for (HistogramDataPoint point : histogram.getDataPointsList()) {
                    if (noRecordedValue(point.getFlags())) {
                        continue;
                    }
                    batch.add(new Batch.MetricSample(service, name, "histogram", unit, description, false,
                            temporality, Attrs.toMap(point.getAttributesList()), histogram(point)));
                }
            }
            case EXPONENTIAL_HISTOGRAM -> {
                var histogram = metric.getExponentialHistogram();
                String temporality = temporality(histogram.getAggregationTemporality());
                for (ExponentialHistogramDataPoint point : histogram.getDataPointsList()) {
                    if (noRecordedValue(point.getFlags())) {
                        continue;
                    }
                    batch.add(new Batch.MetricSample(service, name, "histogram", unit, description, false,
                            temporality, Attrs.toMap(point.getAttributesList()), exponential(point)));
                }
            }
            default -> {
                // Summary and the development signals are not drawn anywhere; ignore them.
            }
        }
    }

    /**
     * A point that carries no value: flagged {@code NO_RECORDED_VALUE}, as a
     * collector forwards a Prometheus staleness marker, or with neither
     * {@code asDouble} nor {@code asInt} set. Read as it stands it would be a 0,
     * which a chart draws and a counter reads as a reset, so it is left out.
     */
    private static boolean noValue(NumberDataPoint point) {
        return noRecordedValue(point.getFlags())
                || point.getValueCase() == NumberDataPoint.ValueCase.VALUE_NOT_SET;
    }

    private static boolean noRecordedValue(int flags) {
        return (flags & DataPointFlags.DATA_POINT_FLAGS_NO_RECORDED_VALUE_MASK_VALUE) != 0;
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
        // Min and max are optional in OTLP; an absent one is unknown (NaN, stored as NULL), not 0.
        return MetricPoint.histogram(millis(point.getTimeUnixNano()), point.getCount(), point.getSum(),
                point.hasMin() ? point.getMin() : Double.NaN, point.hasMax() ? point.getMax() : Double.NaN,
                counts.length > 0 ? counts : null, counts.length > 0 ? bounds : null);
    }

    /**
     * An exponential histogram keeps count, sum, min and max only. Rebuilding its
     * buckets on the explicit scale would be a page of code for a number the UI
     * draws as a mean and a maximum anyway.
     */
    private static MetricPoint exponential(ExponentialHistogramDataPoint point) {
        return MetricPoint.histogram(millis(point.getTimeUnixNano()), point.getCount(), point.getSum(),
                point.hasMin() ? point.getMin() : Double.NaN, point.hasMax() ? point.getMax() : Double.NaN,
                null, null);
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
                    Map<String, Object> attributes = Attrs.toMap(record.getAttributesList());
                    Batch.Catalog catalog = toCatalog(attributes, service, at(record, now));
                    if (catalog != null) {
                        batch.add(catalog);
                    } else {
                        batch.add(toRecord(record, service, logger, now, attributes));
                    }
                }
            }
        }
        store.submit(batch);
        return batch;
    }

    /** The attribute that makes a log record the index catalog of a table (design.adoc#index-catalog). */
    private static final String SCHEMA_TABLE = "spidersense.schema.table";
    private static final String SCHEMA_SCHEMA = "spidersense.schema.schema";
    private static final String SCHEMA_PRODUCT = "spidersense.schema.product";
    private static final String SCHEMA_INDEXES = "spidersense.schema.indexes";

    /**
     * The catalog row a schema record is, or null for an ordinary log record.
     *
     * <p>The extension has no channel of its own to the server, so it says what it
     * read through the one every OpenTelemetry agent already has: a log record. It
     * is not a log line, though — nothing happened at that moment — so it never
     * reaches the {@code log} table (storage.adoc, api.adoc).
     *
     * <p>The {@code indexes} text is kept exactly as it arrived: the store is not
     * the place to reformat JSON it will hand back unchanged.
     */
    private static Batch.@Nullable Catalog toCatalog(Map<String, Object> attributes, String service,
            long at) {
        Object table = attributes.get(SCHEMA_TABLE);
        if (table == null) {
            return null;
        }
        Object schema = attributes.get(SCHEMA_SCHEMA);
        Object product = attributes.get(SCHEMA_PRODUCT);
        Object indexes = attributes.get(SCHEMA_INDEXES);
        return new Batch.Catalog(service,
                schema == null ? "" : String.valueOf(schema),
                String.valueOf(table),
                product == null ? null : String.valueOf(product),
                indexes == null ? "[]" : String.valueOf(indexes),
                at);
    }

    /** When the record says it happened, falling back to when it was observed. */
    private static long at(io.opentelemetry.proto.logs.v1.LogRecord record, long now) {
        return record.getTimeUnixNano() != 0
                ? millis(record.getTimeUnixNano())
                : record.getObservedTimeUnixNano() != 0 ? millis(record.getObservedTimeUnixNano()) : now;
    }

    /**
     * The id is 0 here: the {@code log} table assigns it, and the reads report what it assigned.
     * A trace or span id that is not a valid one is read as none, so the line is kept uncorrelated.
     */
    private static LogRecord toRecord(io.opentelemetry.proto.logs.v1.LogRecord record, String service,
            String logger, long now, Map<String, Object> attributes) {
        int severityNumber = record.getSeverityNumberValue();
        return new LogRecord(
                0,
                at(record, now),
                service,
                LogRecord.severityText(severityNumber),
                severityNumber,
                Attrs.bodyText(record.getBody()),
                logger,
                Attrs.traceId(record.getTraceId()),
                Attrs.spanId(record.getSpanId()),
                attributes);
    }

    // --- shared ---

    /** The service name, cut to the width of every {@code service} column so each one stores the same name. */
    private static String serviceName(Map<String, Object> resource) {
        Object name = resource.get("service.name");
        return name == null ? UNKNOWN_SERVICE : fit(String.valueOf(name));
    }

    /** The width of the {@code service} columns and of a metric's {@code name}. */
    private static final int NAME_MAX = 255;

    private static String fit(String name) {
        return name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
