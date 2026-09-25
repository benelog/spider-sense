package net.benelog.spidersense.extension;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * The extension's one entry point, found through
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 * when the agent loads {@code spider-sense/extension.jar} ({@code design.adoc#extension}).
 *
 * <p>It adds one span processor to the tracer provider and wraps the configured sampler in
 * {@link SchemaLookupSampler}, and changes nothing else: no exporter, no property. The sampler is
 * a wrapper rather than a replacement, so whatever the agent was configured to sample with still
 * decides everything except the extension's own catalog queries, which it never hears about.
 */
public final class SpiderSenseExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration
                .addTracerProviderCustomizer(
                        (builder, config) -> builder.addSpanProcessor(new CallSiteSpanProcessor()))
                .addSamplerCustomizer((sampler, config) -> new SchemaLookupSampler(sampler));
    }
}
