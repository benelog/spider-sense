package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
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


    @Test
    void theFifthRepeatOfAFastStatementCarriesTheStackAndNoLaterRepeatDoes() {
        inOneTrace(() -> {
            for (int i = 0; i < 6; i++) {
                query("select * from order_line where order_id = ?");
            }
        });

        List<SpanData> repeats = exportedQueries();
        assertThat(repeats).hasSize(6);
        for (int i = 0; i < SlowQuerySpanProcessor.N_PLUS_ONE_REPEATS - 1; i++) {
            assertThat(stacktraceOf(repeats.get(i))).as("repeat " + (i + 1)).isNull();
        }
        assertThat(stacktraceOf(repeats.get(4)))
                .as("the fifth repeat is where the N+1 gets its line")
                .isNotNull()
                .contains("SlowQuerySpanProcessorTest");
        assertThat(stacktraceOf(repeats.get(5)))
                .as("one capture per statement per trace, not one per repeat")
                .isNull();
    }

    @Test
    void anotherStatementOfTheSameTraceIsCountedOnItsOwn() {
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                query("select * from order_line where order_id = ?");
            }
            for (int i = 0; i < 5; i++) {
                query("select * from customer where id = ?");
            }
        });

        List<SpanData> repeats = exportedQueries();
        assertThat(repeats).hasSize(9);
        for (int i = 0; i < 8; i++) {
            assertThat(stacktraceOf(repeats.get(i))).as("span " + (i + 1)).isNull();
        }
        assertThat(stacktraceOf(repeats.get(8)))
                .as("the fifth repeat of the second statement")
                .isNotNull();
    }

    @Test
    void aSpanOfAnotherTraceOnTheSameThreadStartsTheCountOver() {
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                query("select * from order_line where order_id = ?");
            }
        });
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                query("select * from order_line where order_id = ?");
            }
        });

        List<SpanData> repeats = exportedQueries();
        assertThat(repeats).hasSize(8);
        assertThat(repeats).allSatisfy(span -> assertThat(stacktraceOf(span))
                .as("four repeats in each of two traces is not an N+1 in either")
                .isNull());
    }

    @Test
    void aSpanThatIsNotADatabaseSpanIsNeverCounted() {
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                tracer.spanBuilder("select * from order_line where order_id = ?").startSpan().end();
            }
            query("select * from order_line where order_id = ?");
        });

        assertThat(exportedQueries())
                .as("the four spans without db.system did not bring the database span to five")
                .allSatisfy(span -> assertThat(stacktraceOf(span)).isNull());
    }

    @Test
    void aSlowRepeatIsStillCaptured() {
        inOneTrace(() -> {
            Span span = tracer.spanBuilder("select * from order_line where order_id = ?")
                    .setAttribute("db.system", "h2")
                    .setAttribute("db.query.text", "select * from order_line where order_id = ?")
                    .startSpan();
            sleep(THRESHOLD_MS * 2);
            span.end();
        });

        assertThat(stacktraceOf(exportedQueries().get(0)))
                .as("the slow path does not care about the counter")
                .isNotNull();
    }

    @Test
    void beyondTheStatementLimitTheCounterGivesUpQuietly() {
        inOneTrace(() -> {
            for (int i = 0; i < SlowQuerySpanProcessor.MAX_STATEMENTS; i++) {
                query("select * from table_" + i + " where id = ?");
            }
            for (int i = 0; i < 6; i++) {
                query("select * from one_too_many where id = ?");
            }
        });

        List<SpanData> repeats = exportedQueries();
        assertThat(repeats).hasSize(SlowQuerySpanProcessor.MAX_STATEMENTS + 6);
        assertThat(repeats).allSatisfy(span -> assertThat(stacktraceOf(span))
                .as("the 257th statement is not counted, and nothing throws")
                .isNull());
    }

    private SpanData exported() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        return exporter.getFinishedSpanItems().get(0);
    }


    /** The database spans the processor saw, in the order they ended. */
    private List<SpanData> exportedQueries() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        List<SpanData> queries = new ArrayList<>();
        for (SpanData span : exporter.getFinishedSpanItems()) {
            if (!span.getName().startsWith("GET ")) {
                queries.add(span);
            }
        }
        return queries;
    }

    private static String stacktraceOf(SpanData span) {
        return span.getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE);
    }

    /** One entry span, so everything started inside it shares its trace id. */
    private void inOneTrace(Runnable work) {
        Span entry = tracer.spanBuilder("GET /orders/{id}").startSpan();
        try (Scope ignored = entry.makeCurrent()) {
            work.run();
        } finally {
            entry.end();
        }
    }

    /** A database span that ends well inside the threshold, so only the counter can fire on it. */
    private void query(String statement) {
        tracer.spanBuilder(statement)
                .setAttribute("db.system", "h2")
                .setAttribute("db.query.text", statement)
                .startSpan()
                .end();
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
