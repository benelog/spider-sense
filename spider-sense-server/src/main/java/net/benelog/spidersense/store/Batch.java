package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one OTLP request produced, on its way to the {@link Writer}.
 *
 * <p>Decoding happens on the Jetty thread and writing does not, so this is the
 * hand-over: a plain value with no database in it, small enough that dropping
 * one under load costs a fraction of a second of telemetry.
 */
public final class Batch {

    /** One metric data point with the metadata needed to place it in a series. */
    public record MetricSample(String service, String name, String type, String unit, String description,
            boolean monotonic, String temporality, Map<String, Object> attributes, MetricPoint point) {
    }

    /** A service seen exporting, with the resource attributes of that export. */
    public record Sighting(String name, Map<String, Object> resource, long at) {
    }

    private final List<SpanRecord> spans = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private final List<MetricSample> metrics = new ArrayList<>();
    private final Map<String, Sighting> services = new LinkedHashMap<>();
    private final List<Tingle> tingles = new ArrayList<>();

    public void add(SpanRecord span) {
        spans.add(span);
    }

    public void add(LogRecord log) {
        logs.add(log);
    }

    public void add(MetricSample sample) {
        metrics.add(sample);
    }

    public void saw(Sighting sighting) {
        services.put(sighting.name(), sighting);
    }

    public void addTingles(List<Tingle> raised) {
        tingles.addAll(raised);
    }

    public List<SpanRecord> spans() {
        return spans;
    }

    public List<LogRecord> logs() {
        return logs;
    }

    public List<MetricSample> metrics() {
        return metrics;
    }

    public List<Sighting> services() {
        return List.copyOf(services.values());
    }

    public List<Tingle> tingles() {
        return tingles;
    }

    public int records() {
        return spans.size() + logs.size() + metrics.size();
    }

    public boolean isEmpty() {
        return records() == 0 && services.isEmpty() && tingles.isEmpty();
    }
}
