package net.benelog.spidersense.api;

import java.util.List;

import net.benelog.spidersense.query.JvmView;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The metric endpoints: the catalog, one metric's series, and the curated JVM
 * view.
 *
 * <p>{@code step} is accepted and ignored. The exporter's own interval already
 * decides the resolution, and resampling it a second time would blur a chart that
 * a person is reading to spot a spike.
 */
public final class MetricsApi {

    private final MetricQueries metrics;
    private final ServiceRegistry services;
    private final Params params;

    public MetricsApi(MetricQueries metrics, ServiceRegistry services,
            net.benelog.spidersense.query.Selectors selectors) {
        this.metrics = metrics;
        this.services = services;
        this.params = new Params(selectors);
    }

    public void register(App app) {
        app.get("/api/metrics", "The metric catalog", this::catalog);
        app.get("/api/metrics/series", "The points of one metric", this::series);
        app.get("/api/jvm", "The curated JVM view of one service", this::jvm);
    }

    public WebResponse catalog(WebRequest req) {
        return WebResponse.json(Json.obj()
                .put("metrics", Codecs.metricCatalog(metrics.catalog(Params.service(req)))));
    }

    public WebResponse series(WebRequest req) {
        String name = req.queryParam("name");
        Window window = params.window(req);
        boolean rate = Params.flag(req, "rate");
        List<MetricQueries.SeriesData> series = metrics.series(name, Params.service(req),
                Params.attributeFilters(req), window);

        Json.JsonArray rendered = Json.arr();
        series.forEach(data -> rendered.add(Codecs.series(data, rate)));
        String type = series.isEmpty() ? "gauge" : series.get(0).type();
        String unit = series.isEmpty() ? "" : series.get(0).unit();
        return WebResponse.json(Json.obj()
                .put("name", name)
                .put("type", type)
                .put("unit", unit)
                .put("series", rendered));
    }

    public WebResponse jvm(WebRequest req) {
        Window window = params.window(req);
        String name = Params.service(req);
        if (name == null) {
            name = firstServiceWithJvm();
        }
        if (name == null) {
            return WebResponse.json(Codecs.jvm(JvmView.empty(null)));
        }
        return WebResponse.json(Codecs.jvm(JvmView.of(metrics, services.get(name), name, window)));
    }

    /** With no {@code service} the page shows whichever JVM is there, which is usually the only one. */
    private @Nullable String firstServiceWithJvm() {
        for (MetricQueries.MetricMeta metric : metrics.catalog(null)) {
            if (metric.name().startsWith("jvm.") && !metric.services().isEmpty()) {
                return metric.services().get(0);
            }
        }
        return null;
    }
}
