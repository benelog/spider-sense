package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The processor against a real {@link SdkTracerProvider}: whatever the SDK does around
 * {@code onEnding} is part of the test, because the whole point is that the callback runs while the
 * span is still writable.
 */
class SlowQuerySpanProcessorTest {

    /** Small enough that a test can sleep past it, large enough that a fast span stays under it. */
    private static final long THRESHOLD_MS = 50;

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void start() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new SlowQuerySpanProcessor(THRESHOLD_MS))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void stop() {
        tracerProvider.close();
    }

    @Test
    void aSlowDatabaseSpanCarriesTheStackItWasIssuedFrom() {
        Span span = tracer.spanBuilder("SELECT books").setAttribute("db.system", "h2").startSpan();
        sleep(THRESHOLD_MS * 2);
        span.end();

        String stacktrace = exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE);
        assertThat(stacktrace).as("code.stacktrace").isNotNull();
        String first = stacktrace.split("\\R")[0];
        assertThat(first)
                .as("the frames start where the query was issued, not inside the SDK")
                .startsWith("\tat ")
                .contains("SlowQuerySpanProcessorTest.aSlowDatabaseSpanCarriesTheStackItWasIssuedFrom");
        assertThat(stacktrace.split("\\R"))
                .allMatch(line -> line.startsWith("\tat "))
                .hasSizeLessThanOrEqualTo(SlowQuerySpanProcessor.MAX_FRAMES);
    }

    @Test
    void theStableAttributeSpellingCountsTheSameWay() {
        Span span = tracer.spanBuilder("SELECT books").setAttribute("db.system.name", "h2").startSpan();
        sleep(THRESHOLD_MS * 2);
        span.end();

        assertThat(exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE)).isNotNull();
    }

    @Test
    void aFastDatabaseSpanIsLeftAlone() {
        Span span = tracer.spanBuilder("SELECT books").setAttribute("db.system", "h2").startSpan();
        span.end();

        assertThat(exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE)).isNull();
    }

    @Test
    void aSlowSpanThatIsNotADatabaseSpanIsLeftAlone() {
        Span span = tracer.spanBuilder("GET /orders").setAttribute("http.request.method", "GET").startSpan();
        sleep(THRESHOLD_MS * 2);
        span.end();

        assertThat(exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE)).isNull();
    }

    @Test
    void theThresholdComesFromTheSamePropertyTheServerUses() {
        assertThat(SlowQuerySpanProcessor.configuredThresholdMillis())
                .isEqualTo(SlowQuerySpanProcessor.DEFAULT_THRESHOLD_MS);
        System.setProperty(SlowQuerySpanProcessor.THRESHOLD_PROPERTY, "250");
        try {
            assertThat(SlowQuerySpanProcessor.configuredThresholdMillis()).isEqualTo(250);
            System.setProperty(SlowQuerySpanProcessor.THRESHOLD_PROPERTY, "not a number");
            assertThat(SlowQuerySpanProcessor.configuredThresholdMillis())
                    .as("nonsense falls back rather than throwing out of a static initialiser")
                    .isEqualTo(SlowQuerySpanProcessor.DEFAULT_THRESHOLD_MS);
        } finally {
            System.clearProperty(SlowQuerySpanProcessor.THRESHOLD_PROPERTY);
        }
    }

    @Test
    void theFramesOfTheSdkAndOfOurselvesAreDroppedFromTheTop() {
        StackTraceElement[] frames = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("net.benelog.spidersense.extension.SlowQuerySpanProcessor",
                        "onEnding", "SlowQuerySpanProcessor.java", 2),
                new StackTraceElement("io.opentelemetry.sdk.trace.SdkSpan", "end", "SdkSpan.java", 3),
                new StackTraceElement("org.h2.jdbc.JdbcPreparedStatement", "executeQuery",
                        "JdbcPreparedStatement.java", 4),
                new StackTraceElement("orders.OrderRepository", "load", "OrderRepository.java", 5),
                new StackTraceElement("io.opentelemetry.NotLeading", "run", "NotLeading.java", 6),
        };

        assertThat(SlowQuerySpanProcessor.format(frames)).isEqualTo("""
                \tat org.h2.jdbc.JdbcPreparedStatement.executeQuery(JdbcPreparedStatement.java:4)
                \tat orders.OrderRepository.load(OrderRepository.java:5)
                \tat io.opentelemetry.NotLeading.run(NotLeading.java:6)
                """);
    }

    @Test
    void atMost64Frames() {
        StackTraceElement[] frames = new StackTraceElement[200];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("orders.Frame" + i, "run", "Frame.java", i);
        }

        assertThat(SlowQuerySpanProcessor.format(frames).split("\\R"))
                .hasSize(SlowQuerySpanProcessor.MAX_FRAMES);
    }

    private SpanData exported() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        return exporter.getFinishedSpanItems().get(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
