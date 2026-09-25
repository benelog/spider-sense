package net.benelog.spidersense.extension;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import net.benelog.spidersense.extension.schema.IndexCatalog;

/**
 * Keeps the extension's own catalog queries out of the application's trace.
 *
 * <p>Reading the index catalog is a database call like any other: on PostgreSQL
 * {@code DatabaseMetaData.getIndexInfo} is a statement against the catalog tables, and the agent's
 * JDBC instrumentation would make it a database span of the very request the lookup was triggered
 * by — a trace that shows one slow query would show ours beside it, and the N+1 counter would count
 * it. Under the packaged agent the instrumentation's own guard against nested statements already
 * stops it, because the lookup runs inside the slow statement's call; this sampler is what keeps
 * that true whatever order the advice ends up in ({@code DatabaseCatalogIT} checks the result).
 *
 * <p>{@link IndexCatalog} marks the thread for the length
 * of the lookup with the baggage entry {@value #LOOKUP_KEY}, which this sampler is the only reader
 * of: a span started under that entry is dropped, everything else goes to the sampler the agent was
 * configured with. Baggage is what carries the mark because it is already part of the context the
 * SDK hands the sampler, so nothing new has to be threaded through.
 *
 * <p>Dropping rather than not recording is deliberate: the span is never exported and never given a
 * sampled trace flag, so no child of it is either.
 */
public final class SchemaLookupSampler implements Sampler {

    /**
     * The entry the lookup puts in the baggage; see {@code design.adoc#extension}. A compile-time
     * constant of the catalog's, so it is inlined here and loads nothing of the helper package.
     */
    static final String LOOKUP_KEY = IndexCatalog.LOOKUP_KEY;

    private final Sampler delegate;

    public SchemaLookupSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        if (Baggage.fromContext(parentContext).getEntryValue(LOOKUP_KEY) != null) {
            return SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "SpiderSense{" + delegate.getDescription() + "}";
    }
}
