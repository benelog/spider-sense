package net.benelog.spidersense.extension;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * The extension's one entry point, found through
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 * when the agent loads {@code spider-sense/extension.jar} ({@code design.adoc#extension}).
 *
 * <p>It adds one span processor to the tracer provider and wraps the configured sampler in
 * {@link SchemaLookupSampler}, and changes nothing else: no exporter, no property. The sampler is
 * a wrapper rather than a replacement, so whatever the agent was configured to sample with still
 * decides everything except the extension's own catalog queries, which it never hears about.
 *
 * <p>It also notes the service name the resource ended up with, unchanged, for the launcher's
 * start-up line ({@link #serviceName()}).
 */
public final class SpiderSenseExtension implements AutoConfigurationCustomizerProvider {

    private static volatile @Nullable String serviceName;

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration
                .addTracerProviderCustomizer(
                        (builder, config) -> builder.addSpanProcessor(new CallSiteSpanProcessor()))
                .addSamplerCustomizer((sampler, config) -> new SchemaLookupSampler(sampler))
                .addResourceCustomizer((resource, config) -> noteServiceName(resource));
    }

    /** Notes the resource's {@code service.name} and returns the resource as it was. */
    static Resource noteServiceName(Resource resource) {
        serviceName = resource.getAttribute(ServiceNameDefault.SERVICE_NAME);
        return resource;
    }

    /**
     * The {@code service.name} of the resource the agent built, once it has: every detector and
     * every property has had its say by then, so it is the name the telemetry carries. The
     * launcher reads it reflectively through the agent's extension class loader, after the agent's
     * {@code premain} has returned, to name the service on its start-up line; {@code null} before.
     */
    public static @Nullable String serviceName() {
        return serviceName;
    }
}
