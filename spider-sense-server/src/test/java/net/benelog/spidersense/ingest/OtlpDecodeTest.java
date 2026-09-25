package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.store.Batch;
import net.benelog.spidersense.store.SpanRecord;

/**
 * The pure half of {@link OtlpDecoder}: a request and the time in, records out, with no store,
 * no ingest cap and no writer.
 */
class OtlpDecodeTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void everySpanWithBothIdsIsDecodedAndNothingIsFilteredOrRaised() {
        Span.Builder slowQuery = Otlp.span(TRACE, "00f067aa0ba902b7", "SELECT orders",
                Span.SpanKind.SPAN_KIND_CLIENT, NOW - 1_000, 5_000, Otlp.attr("db.system", "h2"));
        Span.Builder ownRequest = Otlp.span(TRACE, "00f067aa0ba902b8", "GET /api/status",
                Span.SpanKind.SPAN_KIND_SERVER, NOW - 900, 1, Otlp.attr("server.port", 4000));
        Span.Builder noSpanId = Otlp.span(TRACE, "00f067aa0ba902b9", "lost",
                Span.SpanKind.SPAN_KIND_INTERNAL, NOW - 800, 1).setSpanId(ByteString.EMPTY);

        Batch batch = OtlpDecoder.decode(Otlp.traces(
                Otlp.resourceSpans(Otlp.service("orders"), slowQuery, ownRequest, noSpanId),
                Otlp.resourceSpans(Otlp.resource())), NOW);

        assertThat(batch.spans()).extracting(SpanRecord::name).containsExactly("SELECT orders", "GET /api/status");
        assertThat(batch.tingles()).as("tingles are the ingest's to raise").isEmpty();
        assertThat(batch.services()).extracting(Batch.Sighting::name)
                .containsExactly("orders", OtlpDecoder.UNKNOWN_SERVICE);
        assertThat(batch.services()).extracting(Batch.Sighting::at).containsOnly(NOW);
    }

    @Test
    void aLogRecordWithNoTimeOfItsOwnIsPlacedAtNow() {
        LogRecord timed = Otlp.log(NOW - 5_000, 9, "timed", null, null);
        LogRecord observed = timed.toBuilder().setTimeUnixNano(0)
                .setObservedTimeUnixNano((NOW - 3_000) * 1_000_000L).build();
        LogRecord untimed = timed.toBuilder().setTimeUnixNano(0).build();

        Batch batch = OtlpDecoder.decode(Otlp.logs(Otlp.service("orders"), "app", timed, observed, untimed), NOW);

        assertThat(batch.logs()).extracting(net.benelog.spidersense.store.LogRecord::at)
                .containsExactly(NOW - 5_000, NOW - 3_000, NOW);
    }

    @Test
    void aMetricsExportIsDecodedWithItsSighting() {
        Batch batch = OtlpDecoder.decode(Otlp.gauge(Otlp.service("orders"), "jvm.threads", "{thread}", NOW, 12), NOW);

        assertThat(batch.metrics()).extracting(Batch.MetricSample::name).containsExactly("jvm.threads");
        assertThat(batch.services()).extracting(Batch.Sighting::name).containsExactly("orders");
    }

    @Test
    void ourOwnTrafficIsAServerSpanOnOurPortOfTheEmbeddedServiceOrOfAnyWhileItIsUnknown() {
        SpanRecord served = record(Span.SpanKind.SPAN_KIND_SERVER, 4000, "orders");

        assertThat(OtlpDecoder.isOurOwnTraffic(served, 4000, "orders")).isTrue();
        assertThat(OtlpDecoder.isOurOwnTraffic(served, 4000, null)).as("embedded not known yet").isTrue();
        assertThat(OtlpDecoder.isOurOwnTraffic(served, 4000, "billing")).as("another service's port").isFalse();
        assertThat(OtlpDecoder.isOurOwnTraffic(served, 4001, "orders")).as("another port").isFalse();
        assertThat(OtlpDecoder.isOurOwnTraffic(record(Span.SpanKind.SPAN_KIND_CLIENT, 4000, "orders"), 4000,
                "orders")).as("an application calling Spider Sense").isFalse();
        assertThat(OtlpDecoder.isOurOwnTraffic(record(Span.SpanKind.SPAN_KIND_SERVER, null, "orders"), 4000,
                "orders")).as("no port at all").isFalse();
    }

    private static SpanRecord record(Span.SpanKind kind, Integer port, String service) {
        Span.Builder span = port == null
                ? Otlp.span(TRACE, "00f067aa0ba902b7", "GET /x", kind, NOW, 1)
                : Otlp.span(TRACE, "00f067aa0ba902b7", "GET /x", kind, NOW, 1, Otlp.attr("server.port", port));
        return OtlpDecoder.decode(Otlp.traces(Otlp.service(service), span), NOW).spans().get(0);
    }
}
