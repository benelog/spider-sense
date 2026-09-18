package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Which frame of a stack trace an agent is meant to open. */
class CodeFramesTest {

    private static final String STACKTRACE = """
            java.lang.IllegalStateException: no such order 42
            \tat java.base/java.util.Objects.requireNonNull(Objects.java:220)
            \tat orders.OrderService.load(OrderService.java:41)
            \tat org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:1089)
            \tat com.acme.billing.Invoices.total(Invoices.java:12)
            \tat io.opentelemetry.javaagent.Runner.run(Runner.java:1)""";

    @Test
    void withoutAnAllowlistEverythingThatIsNotAKnownFrameworkIsApplicationCode() {
        CodeFrames frames = new CodeFrames("");

        assertThat(frames.ofStacktrace(STACKTRACE)).containsExactly(
                "orders.OrderService.load(OrderService.java:41)",
                "com.acme.billing.Invoices.total(Invoices.java:12)");
    }

    @Test
    void anAllowlistReplacesTheHeuristic() {
        CodeFrames frames = new CodeFrames("com.acme, org.acme");

        assertThat(frames.ofStacktrace(STACKTRACE))
                .containsExactly("com.acme.billing.Invoices.total(Invoices.java:12)");
    }

    @Test
    void atMostFiveFramesInnermostFirst() {
        StringBuilder deep = new StringBuilder("java.lang.IllegalStateException: deep\n");
        for (int i = 0; i < 9; i++) {
            deep.append("\tat orders.Frame").append(i).append(".run(Frame").append(i)
                    .append(".java:").append(i).append(")\n");
        }
        CodeFrames frames = new CodeFrames(null);

        assertThat(frames.ofStacktrace(deep.toString()))
                .hasSize(CodeFrames.MAX_FRAMES)
                .startsWith("orders.Frame0.run(Frame0.java:0)");
    }

    @Test
    void nothingToReadIsAnEmptyList() {
        CodeFrames frames = new CodeFrames("");

        assertThat(frames.ofStacktrace(null)).isEmpty();
        assertThat(frames.ofStacktrace("   ")).isEmpty();
        assertThat(frames.ofStacktrace("java.lang.IllegalStateException: no frames")).isEmpty();
    }

    @Test
    void theSpanAttributesAreTheOtherSourceOfAFrame() {
        CodeFrames frames = new CodeFrames("");

        assertThat(frames.ofAttributes(Map.of("code.namespace", "orders.OrderService",
                "code.function", "load"))).containsExactly("orders.OrderService.load");
        assertThat(frames.ofAttributes(Map.of("code.namespace", "org.apache.catalina.Valve",
                "code.function", "invoke"))).isEmpty();
        assertThat(frames.ofAttributes(Map.of())).isEmpty();
        assertThat(frames.of(STACKTRACE, Map.of("code.namespace", "orders.Other",
                "code.function", "run"))).hasSize(2);
    }

    @Test
    void aSlowQuerySpanCarriesARealStackTraceInCodeStacktrace() {
        CodeFrames frames = new CodeFrames("");
        String captured = """
                \tat org.h2.jdbc.JdbcPreparedStatement.executeQuery(JdbcPreparedStatement.java:120)
                \tat orders.OrderRepository.load(OrderRepository.java:64)
                \tat orders.OrderService.report(OrderService.java:18)
                """;

        assertThat(frames.ofAttributes(Map.of("code.stacktrace", captured))).containsExactly(
                "orders.OrderRepository.load(OrderRepository.java:64)",
                "orders.OrderService.report(OrderService.java:18)");

        // It wins over the attribute pair, because it has a file and a line, and falls back to it
        // when every frame it holds is framework.
        assertThat(frames.ofAttributes(Map.of("code.stacktrace", captured,
                "code.namespace", "orders.Other", "code.function", "run")))
                .containsExactly("orders.OrderRepository.load(OrderRepository.java:64)",
                        "orders.OrderService.report(OrderService.java:18)");
        assertThat(frames.ofAttributes(Map.of(
                "code.stacktrace", "\tat org.h2.jdbc.JdbcStatement.execute(JdbcStatement.java:1)\n",
                "code.namespace", "orders.Other", "code.function", "run")))
                .containsExactly("orders.Other.run");
    }
}
