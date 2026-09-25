package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AttrJsonTest {

    @Test
    void attributesThatFitAreEncodedAsTheyAre() {
        Map<String, Object> attributes = Map.of("http.route", "/orders/{id}");

        assertThat(AttrJson.encode(attributes, 100)).isEqualTo(AttrJson.encode(attributes));
    }

    @Test
    void theLongestValueIsCutUntilTheTextFits() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("http.route", "/orders/{id}");
        attributes.put("code.stacktrace", "\tat a.B.c(B.java:1)\n".repeat(500));

        String json = AttrJson.encode(attributes, 1000);

        assertThat(json.length()).isLessThanOrEqualTo(1000);
        Map<String, Object> decoded = AttrJson.decode(json);
        assertThat(decoded.get("http.route")).isEqualTo("/orders/{id}");
        assertThat((String) decoded.get("code.stacktrace")).startsWith("\tat a.B.c(B.java:1)")
                .endsWith(AttrJson.CUT_MARK);
    }

    /** An object too long for the column is cut as its text, not dropped with the attributes beside it. */
    @Test
    void aNestedObjectTooLongForTheColumnIsCutAsItsText() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("http.route", "/orders/{id}");
        attributes.put("payload", Map.of("body", "x".repeat(5_000)));

        String json = AttrJson.encode(attributes, 1000);

        assertThat(json.length()).isLessThanOrEqualTo(1000);
        Map<String, Object> decoded = AttrJson.decode(json);
        assertThat(decoded.get("http.route")).isEqualTo("/orders/{id}");
        assertThat((String) decoded.get("payload")).startsWith("{\"body\":\"xxx").endsWith(AttrJson.CUT_MARK);
    }

    @Test
    void textThatCannotBeCutFallsBackToAnEmptyObject() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            attributes.put("key." + i, (long) i);
        }

        assertThat(AttrJson.encode(attributes, 50)).isEqualTo(AttrJson.EMPTY_OBJECT);
    }

    @Test
    void eventsAreCutAsAttributesAre() {
        List<SpanRecord.SpanEvent> events = List.of(new SpanRecord.SpanEvent("exception", 1,
                Map.of("exception.type", "java.lang.StackOverflowError",
                        "exception.stacktrace", "\tat a.B.c(B.java:1)\n".repeat(500))));

        String json = AttrJson.encodeEvents(events, 1000);

        assertThat(json.length()).isLessThanOrEqualTo(1000);
        SpanRecord.SpanEvent event = AttrJson.decodeEvents(json).get(0);
        assertThat(event.name()).isEqualTo("exception");
        assertThat(event.attributes().get("exception.type")).isEqualTo("java.lang.StackOverflowError");
        assertThat((String) event.attributes().get("exception.stacktrace")).endsWith(AttrJson.CUT_MARK);
    }

    @Test
    void theStoreKeepsANonFiniteDoubleAsItsTextAndAnAnswerWritesNull() {
        assertThat(AttrJson.toJson(Double.NaN).toJson()).isEqualTo("\"NaN\"");
        assertThat(AttrJson.toJson(Double.POSITIVE_INFINITY, AttrJson.Rules.STORE).toJson())
                .isEqualTo("\"Infinity\"");
        assertThat(AttrJson.toJson(Double.NaN, AttrJson.Rules.ANSWER).toJson()).isEqualTo("null");
        assertThat(AttrJson.toJson(1.5, AttrJson.Rules.ANSWER).toJson()).isEqualTo("1.5");
    }

    @Test
    void wholeNumbersAreIntegersAndAListKeepsItsElementsTypes() {
        assertThat(AttrJson.toJson(List.of(1L, 2, (short) 3, "four", true), AttrJson.Rules.ANSWER).toJson())
                .isEqualTo("[1,2,3,\"four\",true]");
    }

    @Test
    void theRulesRoundAFiniteDoubleAndCanWriteAMapAsItsStoredText() {
        AttrJson.Rules rounded = new AttrJson.Rules(AttrJson.NonFinite.NULL,
                value -> Math.round(value * 10.0) / 10.0, true);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1.25);

        assertThat(AttrJson.toJson(List.of(1.26, Double.NaN), rounded).toJson()).isEqualTo("[1.3,null]");
        assertThat(AttrJson.toJson(map, rounded).toJson()).isEqualTo("\"{\\\"a\\\":1.25}\"");
        assertThat(AttrJson.toJson(map, AttrJson.Rules.ANSWER).toJson()).isEqualTo("{\"a\":1.25}");
    }
}
