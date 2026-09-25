package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The hex-to-base64 pre-pass, over the tree and over the text alike. */
class OtlpJsonTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";
    private static final String TRACE_BASE64 = "S/kvNXezTaajzpKdDg5HNg==";
    private static final String SPAN_BASE64 = "APBnqgupArc=";

    @Test
    void everyIdSpellingIsRewrittenByItsOwnLengthInBothPasses() {
        String json = "{\"traceId\":\"" + TRACE + "\",\"span_id\":\"" + SPAN + "\","
                + "\"parentSpanId\":\"" + SPAN + "\",\"trace_id\":\"" + SPAN + "\",\"name\":\"" + SPAN + "\"}";
        String expected = "{\"traceId\":\"" + TRACE_BASE64 + "\",\"span_id\":\"" + SPAN_BASE64 + "\","
                + "\"parentSpanId\":\"" + SPAN_BASE64 + "\",\"trace_id\":\"" + SPAN + "\",\"name\":\"" + SPAN + "\"}";

        assertThat(OtlpJson.hexIdsToBase64(json)).as("a 16-character trace id is not hex, and a name is no id")
                .isEqualTo(expected);
        assertThat(OtlpJson.hexIdsToBase64InText(json)).isEqualTo(expected);
    }

    @Test
    void aBase64IdIsLeftAlone() {
        String json = "{\"traceId\":\"" + TRACE_BASE64 + "\",\"spanId\":\"" + SPAN_BASE64 + "\"}";

        assertThat(OtlpJson.hexIdsToBase64(json)).isEqualTo(json);
        assertThat(OtlpJson.hexIdsToBase64InText(json)).isEqualTo(json);
    }
}
