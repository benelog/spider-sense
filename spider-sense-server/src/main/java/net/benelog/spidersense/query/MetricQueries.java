package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.Sql;

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

        public String attribute(String key) {
            Object value = attributes.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    private final Sql sql;

    public MetricQueries(Sql sql) {
        this.sql = sql;
    }

    public List<MetricMeta> catalog(String service) {
        Map<String, List<String>> servicesByName = new LinkedHashMap<>();
        Map<String, Integer> seriesByName = new LinkedHashMap<>();
        String seriesSql = "SELECT name, service FROM metric_series"
                + (service == null ? "" : " WHERE service = ?") + " ORDER BY name, service";
        sql.query(seriesSql, service == null ? List.of() : List.of(service), rs -> {
            String name = rs.getString("name");
            List<String> names = servicesByName.computeIfAbsent(name, n -> new ArrayList<>());
            if (!names.contains(rs.getString("service"))) {
                names.add(rs.getString("service"));
            }
            seriesByName.merge(name, 1, Integer::sum);
            return null;
        });
        if (servicesByName.isEmpty()) {
            return List.of();
        }
        Map<String, String[]> metadata = new LinkedHashMap<>();
        sql.query("SELECT name, type, unit, description FROM metric", List.of(), rs -> {
            metadata.put(rs.getString("name"), new String[]{rs.getString("type"), rs.getString("unit"),
                    rs.getString("description")});
            return null;
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
    public List<SeriesData> series(String name, String service, Map<String, String> attributeFilters,
            Window window) {
        String[] meta = sql.queryOne("SELECT type, unit, monotonic, temporality FROM metric WHERE name = ?",
                List.of(name), rs -> new String[]{rs.getString("type"), rs.getString("unit"),
                        String.valueOf(rs.getBoolean("monotonic")), rs.getString("temporality")});
        if (meta == null) {
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
        sql.query(query.toString(), params, rs -> {
            long id = rs.getLong("id");
            SeriesBuilder builder = builders.get(id);
            if (builder == null) {
                builder = new SeriesBuilder(rs.getString("service"),
                        AttrJson.decode(rs.getString("attributes")));
                builders.put(id, builder);
            }
            builder.points.add(point(rs));
            return null;
        });

        List<SeriesData> data = new ArrayList<>();
        boolean monotonic = Boolean.parseBoolean(meta[2]);
        for (SeriesBuilder builder : builders.values()) {
            if (matches(builder.attributes, attributeFilters)) {
                data.add(new SeriesData(builder.service, name, meta[0], meta[1], monotonic, meta[3],
                        builder.attributes, List.copyOf(builder.points)));
            }
        }
        return data;
    }

    /** The newest point of a metric for a service, whatever its attributes. */
    public MetricPoint latest(String name, String service, Window window) {
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
     * A cumulative monotonic sum as a rate per second: the first point has no
     * predecessor, so it is {@link Double#NaN} and the wire writes {@code null}.
     */
    public static double[] rate(List<MetricPoint> points) {
        double[] rates = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            if (i == 0) {
                rates[i] = Double.NaN;
                continue;
            }
            double seconds = (points.get(i).at() - points.get(i - 1).at()) / 1000.0;
            rates[i] = seconds <= 0 ? Double.NaN
                    : (points.get(i).value() - points.get(i - 1).value()) / seconds;
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
                rs.getDouble("sum"), rs.getDouble("min"), rs.getDouble("max"), counts, bounds);
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
