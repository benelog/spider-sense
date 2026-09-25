package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.ResponseBuckets;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersilk.json.Json;

/** Both renderings of one status read the same snapshot, so they cannot disagree. */
class StatusSnapshotTest {

    private static final StatusSnapshot STATUS = new StatusSnapshot("standalone", "http://127.0.0.1:4000",
            0, 1_700_000_000_000L, null, 500, 100, new ResponseBuckets(500), List.of(), List.of("com.acme"),
            List.of("org.springframework."), null, 24, 1_000_000, 1_500L,
            new Database.Storage("jdbc:h2:mem:x", null, 4096, false, null),
            0, 7, 0, 12, 3, 40, 5, 2, 0, 0);

    @Test
    void theJsonAndTheTextReportTheSameCounts() {
        Json.JsonObject json = Codecs.status(STATUS);
        String text = Text.status(STATUS);

        Json.JsonObject counts = json.getObject("counts");
        assertThat(counts.getLong("spans")).isEqualTo(12);
        assertThat(text).contains("| spans | 12 |").contains("| traces | 3 |").contains("| logs | 40 |")
                .contains("| metric series | 5 |").contains("| services | 2 |")
                .contains("| dropped spans | 7 |");
        assertThat(json.getObject("storage").getLong("droppedSpans")).isEqualTo(7);
    }

    @Test
    void anIngestCapIsNamedInBothAndNoneIsNullInTheJson() {
        assertThat(Codecs.status(STATUS).getObject("ingest").getLong("maxSpansPerSecond")).isEqualTo(1_500);
        assertThat(Text.status(STATUS)).contains("| ingest cap | 1500 spans/s |").contains("| ignore | none |")
                .contains("| started | — |").contains("| oldest span | — |");
    }
}
