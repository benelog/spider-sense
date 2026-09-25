package net.benelog.spidersense.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.store.AttrJson;

/** OTLP values as the store keeps them. */
class AttrsTest {

    private static AnyValue text(String value) {
        return AnyValue.newBuilder().setStringValue(value).build();
    }

    private static AnyValue array(AnyValue... values) {
        return AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder().addAllValues(java.util.List.of(values)))
                .build();
    }

    /** A body that is not a string is its JSON rendering, lists included, never Java's {@code [a, b]}. */
    @Test
    void aListBodyAndAListInsideAKeyValueListAreJson() {
        assertThat(Attrs.bodyText(array(text("a"), text("b")))).isEqualTo("[\"a\",\"b\"]");

        AnyValue kvlist = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                .addValues(KeyValue.newBuilder().setKey("tags").setValue(array(text("x"), text("y"))))
                .addValues(KeyValue.newBuilder().setKey("n").setValue(AnyValue.newBuilder().setIntValue(3))))
                .build();
        assertThat(Attrs.bodyText(kvlist)).isEqualTo("{\"tags\":[\"x\",\"y\"],\"n\":3}");
    }

    private static AnyValue kvlist(String key, AnyValue value) {
        return AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                .addValues(KeyValue.newBuilder().setKey(key).setValue(value))).build();
    }

    /**
     * A key/value list one level down is an object inside the object, and inside an
     * array, not a string holding its escaped JSON.
     */
    @Test
    void aKeyValueListNestedInAKeyValueListOrAnArrayIsAnObject() {
        AnyValue id = AnyValue.newBuilder().setIntValue(1).build();

        assertThat(Attrs.bodyText(kvlist("user", kvlist("id", id)))).isEqualTo("{\"user\":{\"id\":1}}");
        assertThat(Attrs.bodyText(array(kvlist("id", id)))).isEqualTo("[{\"id\":1}]");

        java.util.Map<String, Object> attributes = Attrs.toMap(java.util.List.of(
                KeyValue.newBuilder().setKey("user").setValue(kvlist("id", id)).build(),
                KeyValue.newBuilder().setKey("users").setValue(array(kvlist("id", id))).build()));
        assertThat(AttrJson.encodeSorted(attributes)).isEqualTo("{\"user\":{\"id\":1},\"users\":[{\"id\":1}]}");
    }
}
