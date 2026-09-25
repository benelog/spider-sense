package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The numbers every {@code slow-*} finding, {@code check} and {@code compare} share, with no store. */
class SlowGroupTest {

    @Test
    void databaseWorkPerRequestIsZeroWhenNothingWasRequested() {
        Queries.DbWork work = new Queries.DbWork(30, 120.0, 2);

        assertThat(work.callsPer(10)).isEqualTo(3.0);
        assertThat(work.msPer(10)).isEqualTo(12.0);
        assertThat(work.callsPer(0)).isZero();
        assertThat(work.msPer(0)).isZero();
        assertThat(Queries.DbWork.NONE.callsPer(10)).isZero();
    }

    @Test
    void theDatabaseShareIsAtMostOneAndZeroWithNoTime() {
        Queries.DbWork work = new Queries.DbWork(30, 120.0, 2);

        assertThat(work.shareOf(480.0)).isEqualTo(0.25);
        // Database spans that outlast their entry span (async work) do not make a share above one.
        assertThat(work.shareOf(100.0)).isEqualTo(1.0);
        assertThat(work.shareOf(0)).isZero();
    }

    @Test
    void thePercentilesAreWrittenInTheOrderFindingsAdocListsThem() {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("calls", 40L);

        new SlowGroup("svc", "GET /orders", 40, 12.0, 812.0, 950.0, 4_000.0).putPercentiles(numbers);

        assertThat(numbers).containsExactly(entry("calls", 40L), entry("p50Ms", 12.0),
                entry("p95Ms", 812.0), entry("maxMs", 950.0), entry("totalMs", 4_000.0));
    }

    @Test
    void theFrustratedBoundIsFourTimesTheThreshold() {
        ResponseBuckets buckets = new ResponseBuckets(500);

        assertThat(buckets.frustratedMs()).isEqualTo(2_000);
        assertThat(buckets.bounds()).containsExactly(125, 500, 2_000);
    }

    @Test
    void aServiceNodeIdNamesItsServiceAndNoOtherNodeDoes() {
        assertThat(Stats.Node.serviceId("orders")).isEqualTo("svc:orders");
        assertThat(Stats.Node.serviceOf("svc:orders")).isEqualTo("orders");
        assertThat(Stats.Node.serviceOf("db:h2:mem")).isNull();
        assertThat(Stats.Node.serviceOf("user")).isNull();
    }
}
