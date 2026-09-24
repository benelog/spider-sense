package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;

/** Named moments: the ones a person records, and the {@code start} the writer records. */
class MarksTest {

    private static final long NOW = 1_700_000_000_000L;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);

    @AfterEach
    void close() {
        store.close();
    }

    private void flush() {
        store.writer().awaitIdle(5_000);
    }

    @Test
    void aMarkIsCreatedAndListedNewestFirst() {
        store.marks().create("before", "orders", "the slow version", 1_000L);
        Marks.Mark after = store.marks().create("after-fix", null, null, 2_000L);

        List<Marks.Mark> marks = store.marks().list(50);

        assertThat(marks).hasSize(2);
        assertThat(marks.get(0).name()).isEqualTo("after-fix");
        assertThat(marks.get(0).id()).isEqualTo(after.id());
        assertThat(marks.get(0).service()).isNull();
        assertThat(marks.get(1).name()).isEqualTo("before");
        assertThat(marks.get(1).at()).isEqualTo(1_000L);
        assertThat(marks.get(1).service()).isEqualTo("orders");
        assertThat(marks.get(1).note()).isEqualTo("the slow version");
    }

    @Test
    void aNameOutsideTheAllowedCharactersIsRejected() {
        assertThatThrownBy(() -> store.marks().create("two words", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.marks().create("", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.marks().create("x".repeat(65), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.marks().create("v2.1_rc-3", null, null, null).name()).isEqualTo("v2.1_rc-3");
    }

    @Test
    void theWriterMarksAStartWheneverAServiceReportsAProcessIdItHasNotStored() {
        export("orders", 1234, NOW);
        flush();
        assertThat(startMarks()).hasSize(1);
        assertThat(startMarks().get(0).note()).isEqualTo("pid 1234");
        assertThat(startMarks().get(0).service()).isEqualTo("orders");

        // The same application exporting again is not a restart.
        export("orders", 1234, NOW + 1000);
        flush();
        assertThat(startMarks()).hasSize(1);

        // A new process id is: the application was rebuilt and run again.
        export("orders", 4321, NOW + 2000);
        flush();
        assertThat(startMarks()).hasSize(2);
        assertThat(startMarks().get(0).note()).isEqualTo("pid 4321");
        assertThat(startMarks().get(0).at()).isPositive();
    }

    /**
     * An exporter batches for seconds, so a run's first request arrives after it
     * began: the start mark takes that request's start, and since=start keeps it.
     */
    @Test
    void theStartMarkIsTheRunsFirstRecordNotTheExportsArrival() {
        long started = System.currentTimeMillis() - 3_000;
        export("orders", 1234, started);
        flush();

        assertThat(startMarks().get(0).at()).isEqualTo(started);

        // A record claiming to be far older than its export moves the mark back no further than a minute.
        long now = System.currentTimeMillis();
        export("orders", 4321, now - 3_600_000);
        flush();

        assertThat(startMarks().get(0).at()).isBetween(now - 61_000, now);
    }

    private List<Marks.Mark> startMarks() {
        List<Marks.Mark> starts = new java.util.ArrayList<>();
        for (Marks.Mark mark : store.marks().list(50)) {
            if (Marks.START.equals(mark.name())) {
                starts.add(mark);
            }
        }
        return starts;
    }

    private void export(String service, long pid, long at) {
        decoder.accept(Otlp.traces(
                Otlp.resource(Otlp.attr("service.name", service),
                        Otlp.attr("telemetry.sdk.language", "java"),
                        Otlp.attr("process.pid", pid)),
                Otlp.span("%032x".formatted(at), "%016x".formatted(at), "GET /orders",
                        Span.SpanKind.SPAN_KIND_SERVER, at, 5)));
    }
}
