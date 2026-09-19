package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The one rule the sampler adds: a span started inside a catalog lookup is not part of the trace. */
class SchemaLookupSamplerTest {

    private final Sampler sampler = new SchemaLookupSampler(Sampler.alwaysOn());

    @Test
    void aSpanStartedInsideALookupIsDropped() {
        Context lookup = Baggage.empty()
                .toBuilder()
                .put("spidersense.schema.lookup", "1")
                .build()
                .storeInContext(Context.root());

        assertThat(shouldSample(lookup).getDecision()).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void everyOtherSpanIsTheDelegatesDecision() {
        assertThat(shouldSample(Context.root()).getDecision())
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void theDescriptionSaysWhatItWraps() {
        assertThat(sampler.getDescription()).isEqualTo("SpiderSense{" + Sampler.alwaysOn().getDescription() + "}");
    }

    private SamplingResult shouldSample(Context parent) {
        return sampler.shouldSample(
                parent,
                "00000000000000000000000000000001",
                "SELECT items",
                SpanKind.CLIENT,
                Attributes.empty(),
                Collections.emptyList());
    }
}
