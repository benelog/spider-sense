package net.benelog.spidersense.extension;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * The extension's one entry point, found through
 * {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 * when the agent loads {@code spider-sense/extension.jar} ({@code docs/design.md}).
 *
 * <p>It adds one span processor to the tracer provider and changes nothing else: no exporter, no
 * sampler, no property. Everything else about the agent stays exactly the stock configuration.
 */
public final class SpiderSenseExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                (builder, config) -> builder.addSpanProcessor(new SlowQuerySpanProcessor()));
    }
}
