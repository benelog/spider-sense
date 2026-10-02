package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;

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

    @Test
    void aLoneSurrogateBecomesTheReplacementCharacterAndAPairStaysWhole() throws Exception {
        assertThat(OtlpJson.withoutLoneSurrogates("\"a\\ud800x\\uDC00y\\ud83d\\ude00z\\\\ud800\\ud800\""))
                .isEqualTo("\"a\\ufffdx\\ufffdy\\ud83d\\ude00z\\\\ud800\\ufffd\"");

        String logs = "{\"resourceLogs\":[{\"scopeLogs\":[{\"scope\":{\"name\":\"s\\udbff\"},\"logRecords\":["
                + "{\"body\":{\"stringValue\":\"surrogate\\ud800x\"},"
                + "\"attributes\":[{\"key\":\"k\\udc00\",\"value\":{\"stringValue\":\"\\ud83d\\ude00\"}}]}]}]}]}";
        ExportLogsServiceRequest.Builder builder = ExportLogsServiceRequest.newBuilder();
        OtlpJson.merge(logs, builder);

        LogRecord record = builder.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
        assertThat(record.getBody().getStringValue()).isEqualTo("surrogate\ufffdx");
        assertThat(record.getAttributes(0).getKey()).isEqualTo("k\ufffd");
        assertThat(record.getAttributes(0).getValue().getStringValue()).isEqualTo("\ud83d\ude00");
        assertThat(builder.getResourceLogs(0).getScopeLogs(0).getScope().getName()).isEqualTo("s\ufffd");

        // A uint64 past 2^63 sends the document down the text path, which is cleaned the same way.
        ExportLogsServiceRequest.Builder past = ExportLogsServiceRequest.newBuilder();
        OtlpJson.merge(logs.replace("{\"body\"", "{\"timeUnixNano\":18446744073709551615,\"body\""), past);
        assertThat(past.getResourceLogs(0).getScopeLogs(0).getLogRecords(0).getBody().getStringValue())
                .isEqualTo("surrogate\ufffdx");
    }
}
