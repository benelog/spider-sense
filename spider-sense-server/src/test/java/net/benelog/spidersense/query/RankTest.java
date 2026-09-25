package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.store.Acks;

/**
 * The acknowledgement and resolution partition of findings.adoc#acknowledgements and
 * #resolutions, over hand-built findings: no rules, no store.
 */
class RankTest {

    private static final Findings.Recurrence NEVER_BACK = (finding, resolvedAt) -> null;

    private static Findings.Ranked ranked(String id, String kind, String severity, double impact) {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("count", 1L);
        return new Findings.Ranked(new Findings.Finding(id, kind, severity, "orders", "title " + id,
                "why " + id, Findings.Subject.error(id), numbers, null, List.of(), List.of()), impact);
    }

    /** Three errors, the heaviest first, as the rules hand them over. */
    private static List<Findings.Ranked> found() {
        return List.of(ranked("a", Findings.ERROR, Findings.HIGH, 30),
                ranked("b", Findings.ERROR, Findings.HIGH, 20),
                ranked("c", Findings.ERROR, Findings.HIGH, 10));
    }

    private static List<String> ids(Findings.Answer answer) {
        return answer.findings().stream().map(Findings.Finding::id).toList();
    }

    @Test
    void anAcknowledgedFindingIsRankedLastAndCountedBeforeTheLimit() {
        Map<String, Acks.Ack> decided = Map.of("a", new Acks.Ack("a", 1_000L, "known"));

        Findings.Answer all = Findings.rank(found(), decided, NEVER_BACK, 10, false);
        assertThat(ids(all)).containsExactly("b", "c", "a");
        assertThat(all.findings().get(2).ack()).isEqualTo(new Findings.Ack(1_000L, "known"));
        assertThat(all.acked()).isEqualTo(1);

        Findings.Answer one = Findings.rank(found(), decided, NEVER_BACK, 1, false);
        assertThat(ids(one)).containsExactly("b");
        assertThat(one.acked()).isEqualTo(1);
    }

    @Test
    void hideAckedLeavesTheSetAsideOnesOutButStillCountsThem() {
        Map<String, Acks.Ack> decided = Map.of("c", new Acks.Ack("c", 1_000L, null),
                "a", new Acks.Ack("a", 2_000L, null, true));

        Findings.Answer answer = Findings.rank(found(), decided, NEVER_BACK, 10, true);

        assertThat(ids(answer)).containsExactly("b");
        assertThat(answer.acked()).isEqualTo(1);
        assertThat(answer.resolved()).isEqualTo(1);
    }

    @Test
    void theSetAsidePartitionIsStable() {
        Map<String, Acks.Ack> decided = Map.of("a", new Acks.Ack("a", 1_000L, null),
                "b", new Acks.Ack("b", 1_000L, null));

        assertThat(ids(Findings.rank(found(), decided, NEVER_BACK, 10, false)))
                .containsExactly("c", "a", "b");
    }

    @Test
    void aResolvedFindingThatDidNotComeBackIsCountedAndSetAside() {
        Map<String, Acks.Ack> decided = Map.of("a", new Acks.Ack("a", 5_000L, "fixed", true));

        Findings.Answer answer = Findings.rank(found(), decided, NEVER_BACK, 10, false);

        assertThat(ids(answer)).containsExactly("b", "c", "a");
        assertThat(answer.findings().get(2).resolution()).isEqualTo(new Findings.Resolution(5_000L, "fixed"));
        assertThat(answer.findings().get(2).kind()).isEqualTo(Findings.ERROR);
        assertThat(answer.resolved()).isEqualTo(1);
    }

    @Test
    void aResolvedFindingThatCameBackIsARegressionRankedFirst() {
        List<Findings.Ranked> found = List.of(ranked("x", Findings.N_PLUS_ONE, Findings.MEDIUM, 5),
                ranked("y", Findings.ERROR, Findings.HIGH, 50));
        Map<String, Acks.Ack> decided = Map.of("x", new Acks.Ack("x", 5_000L, "batched", true));
        Findings.Recurrence back = (finding, resolvedAt) -> {
            assertThat(resolvedAt).isEqualTo(5_000L);
            return finding;
        };

        Findings.Answer answer = Findings.rank(found, decided, back, 10, false);

        assertThat(ids(answer)).containsExactly("x", "y");
        Findings.Finding regression = answer.findings().get(0);
        assertThat(regression.kind()).isEqualTo(Findings.REGRESSION);
        assertThat(regression.severity()).isEqualTo(Findings.HIGH);
        assertThat(regression.state()).isEqualTo(Findings.REGRESSED);
        assertThat(regression.baseKind()).isEqualTo(Findings.N_PLUS_ONE);
        assertThat(regression.numbers().keySet()).startsWith(Findings.RESOLVED_AT, Findings.NOTE,
                Findings.ORIGINAL_KIND);
        assertThat(regression.why()).startsWith("came back after it was resolved (batched); ");
        assertThat(answer.resolved()).isZero();
    }
}
