package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.Sql;
import org.jspecify.annotations.Nullable;

/**
 * Metric reads: the catalog, and the points of one metric's series over a window.
 *
 * <p>Points come back as {@link MetricPoint} rather than as raw rows so the
 * histogram arithmetic — the mean, the interpolated p95 — stays in one place, and
 * the JVM page and the generic series endpoint agree about what a histogram point
 * means.
 */
public final class MetricQueries {

    /** One metric name, as the catalog shows it. */
    public record MetricMeta(String name, String type, String unit, String description,
            List<String> services, int seriesCount) {
    }

    /** One series with its points inside the window, oldest first. */
    public record SeriesData(String service, String name, String type, String unit, boolean monotonic,
            String temporality, Map<String, Object> attributes, List<MetricPoint> points) {

        public @Nullable String attribute(String key) {
            Object value = attributes.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    private final Sql sql;

    public MetricQueries(Sql sql) {
        this.sql = sql;
    }

    public List<MetricMeta> catalog(@Nullable String service) {
        Map<String, List<String>> servicesByName = new LinkedHashMap<>();
        Map<String, Integer> seriesByName = new LinkedHashMap<>();
        String seriesSql = "SELECT name, service FROM metric_series"
                + (service == null ? "" : " WHERE service = ?") + " ORDER BY name, service";
        sql.forEach(seriesSql, service == null ? List.of() : List.of(service), rs -> {
            String name = rs.getString("name");
            List<String> names = servicesByName.computeIfAbsent(name, n -> new ArrayList<>());
            if (!names.contains(rs.getString("service"))) {
                names.add(rs.getString("service"));
            }
            seriesByName.merge(name, 1, Integer::sum);
        });
        if (servicesByName.isEmpty()) {
            return List.of();
        }
        // Metadata is a service's own (storage.adoc#schema); a name several services
        // export is described by the first of them, in the order the list names them.
        Map<String, String[]> metadata = new LinkedHashMap<>();
        sql.forEach("SELECT service, name, type, unit, description FROM metric"
                + (service == null ? "" : " WHERE service = ?") + " ORDER BY name, service",
                service == null ? List.of() : List.of(service), rs -> {
                    metadata.putIfAbsent(rs.getString("name"), new String[]{rs.getString("type"),
                            rs.getString("unit"), rs.getString("description")});
                });
        List<MetricMeta> catalog = new ArrayList<>(servicesByName.size());
        servicesByName.forEach((name, names) -> {
            String[] meta = metadata.getOrDefault(name, new String[]{"gauge", "", ""});
            catalog.add(new MetricMeta(name, meta[0], meta[1], meta[2], List.copyOf(names),
                    seriesByName.getOrDefault(name, 0)));
        });
        catalog.sort((a, b) -> a.name().compareTo(b.name()));
        return catalog;
    }

    /**
     * Every series of one metric, with the points in the window.
     *
     * @param attributeFilters every entry must match exactly; applied in Java
     *        because the attributes are one JSON column, not rows of their own
     */
    public List<SeriesData> series(String name, @Nullable String service,
            Map<String, String> attributeFilters, Window window) {
        // Each service's series are read by that service's own metadata: one exporting
        // the name as a DELTA sum must not turn another's CUMULATIVE points into rates
        // of their totals.
        Map<String, Meta> metaByService = new LinkedHashMap<>();
        List<Object> metaParams = new ArrayList<>(List.of(name));
        if (service != null) {
            metaParams.add(service);
        }
        sql.forEach("SELECT service, type, unit, monotonic, temporality FROM metric WHERE name = ?"
                + (service == null ? "" : " AND service = ?"), metaParams,
                rs -> metaByService.put(rs.getString("service"), new Meta(rs.getString("type"),
                        rs.getString("unit"), rs.getBoolean("monotonic"), rs.getString("temporality"))));
        if (metaByService.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>(List.of(name));
        StringBuilder query = new StringBuilder(
                "SELECT s.id, s.service, s.attributes, p.at_ms, p.value, p.count, p.sum, p.min, p.max,"
                        + " p.buckets FROM metric_series s JOIN metric_point p ON p.series_id = s.id"
                        + " WHERE s.name = ?");
        if (service != null) {
            query.append(" AND s.service = ?");
            params.add(service);
        }
        query.append(" AND p.at_ms BETWEEN ? AND ? ORDER BY s.id, p.at_ms");
        params.add(window.from());
        params.add(window.to());

        Map<Long, SeriesBuilder> builders = new LinkedHashMap<>();
        sql.forEach(query.toString(), params, rs -> {
            long id = rs.getLong("id");
            SeriesBuilder builder = builders.get(id);
            if (builder == null) {
                builder = new SeriesBuilder(rs.getString("service"),
                        AttrJson.decode(rs.getString("attributes")));
                builders.put(id, builder);
            }
            builder.points.add(point(rs));
        });

        List<SeriesData> data = new ArrayList<>();
        for (SeriesBuilder builder : builders.values()) {
            Meta meta = metaByService.get(builder.service);
            if (meta != null && matches(builder.attributes, attributeFilters)) {
                data.add(new SeriesData(builder.service, name, meta.type(), meta.unit(), meta.monotonic(),
                        meta.temporality(), builder.attributes, List.copyOf(builder.points)));
            }
        }
        return data;
    }

    /** One service's description of one metric name. */
    private record Meta(String type, String unit, boolean monotonic, String temporality) {
    }

    /** The newest point of a metric for a service, whatever its attributes. */
    public @Nullable MetricPoint latest(String name, @Nullable String service, Window window) {
        List<SeriesData> series = series(name, service, Map.of(), window);
        MetricPoint newest = null;
        for (SeriesData data : series) {
            for (MetricPoint point : data.points()) {
                if (newest == null || point.at() > newest.at()) {
                    newest = point;
                }
            }
        }
        return newest;
    }

    /**
     * A monotonic sum as a rate per second: the first point has no predecessor, so
     * it is {@link Double#NaN} and the wire writes {@code null}.
     *
     * <p>A cumulative point is the difference from the one before it, and a point
     * below the one before it is a restart of the process that reset the counter:
     * it has no rate either, rather than one hugely negative value that flattens
     * the chart. A delta point is already the increment since the one before it,
     * so it is divided rather than differenced.
     *
     * @param delta whether the sum has {@code DELTA} temporality
     */
    public static double[] rate(List<MetricPoint> points, boolean delta) {
        double[] rates = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            if (i == 0) {
                rates[i] = Double.NaN;
                continue;
            }
            double seconds = (points.get(i).at() - points.get(i - 1).at()) / 1000.0;
            double increment = delta ? points.get(i).value()
                    : points.get(i).value() - points.get(i - 1).value();
            rates[i] = seconds <= 0 || increment < 0 ? Double.NaN : increment / seconds;
        }
        return rates;
    }

    private static boolean matches(Map<String, Object> attributes, Map<String, String> filters) {
        for (var filter : filters.entrySet()) {
            Object value = attributes.get(filter.getKey());
            if (value == null || !String.valueOf(value).equals(filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static MetricPoint point(java.sql.ResultSet rs) throws java.sql.SQLException {
        String buckets = rs.getString("buckets");
        long[] counts = null;
        double[] bounds = null;
        if (buckets != null) {
            var object = net.benelog.spidersilk.json.Json.parse(buckets).asObject();
            var boundsArray = object.optArray("bounds");
            var countsArray = object.optArray("counts");
            if (boundsArray != null && countsArray != null) {
                bounds = new double[boundsArray.size()];
                for (int i = 0; i < bounds.length; i++) {
                    bounds[i] = boundsArray.get(i).asDouble();
                }
                counts = new long[countsArray.size()];
                for (int i = 0; i < counts.length; i++) {
                    counts[i] = countsArray.get(i).asLong();
                }
            }
        }
        return new MetricPoint(rs.getLong("at_ms"), rs.getDouble("value"), rs.getLong("count"),
                rs.getDouble("sum"), doubleOrNaN(rs, "min"), doubleOrNaN(rs, "max"), counts, bounds);
    }

    /** A histogram's min or max, NaN when the sender did not report it. */
    private static double doubleOrNaN(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    private static final class SeriesBuilder {
        private final String service;
        private final Map<String, Object> attributes;
        private final List<MetricPoint> points = new ArrayList<>();

        private SeriesBuilder(String service, Map<String, Object> attributes) {
            this.service = service;
            this.attributes = attributes;
        }
    }
}
