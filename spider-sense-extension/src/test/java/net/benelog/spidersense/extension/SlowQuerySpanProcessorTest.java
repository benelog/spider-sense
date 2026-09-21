package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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

    /** One frame per line, as {@code Throwable.printStackTrace} writes them. */
    private static final Pattern LINES = Pattern.compile("\\R");

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private SlowQuerySpanProcessor processor;

    @BeforeEach
    void start() {
        exporter = InMemorySpanExporter.create();
        processor = new SlowQuerySpanProcessor(THRESHOLD_MS, THRESHOLD_MS);
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
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
        String first = LINES.split(stacktrace, 2)[0];
        assertThat(first)
                .as("the frames start where the query was issued, not inside the SDK")
                .startsWith("\tat ")
                .contains("SlowQuerySpanProcessorTest.aSlowDatabaseSpanCarriesTheStackItWasIssuedFrom");
        assertThat(LINES.split(stacktrace))
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
    void aSlowOutboundCallCarriesTheStackItWasMadeFrom() {
        Span span = tracer.spanBuilder("GET")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("url.full", "http://localhost:8081/api/books/1")
                .startSpan();
        sleep(THRESHOLD_MS * 2);
        span.end();

        String stacktrace = exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE);
        assertThat(stacktrace).as("code.stacktrace").isNotNull();
        assertThat(LINES.split(stacktrace, 2)[0])
                .contains("SlowQuerySpanProcessorTest.aSlowOutboundCallCarriesTheStackItWasMadeFrom");
    }

    @Test
    void aFastOutboundCallIsLeftAlone() {
        Span span = tracer.spanBuilder("GET")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .startSpan();
        span.end();

        assertThat(exported().getAttributes().get(SlowQuerySpanProcessor.CODE_STACKTRACE)).isNull();
    }

    @Test
    void aSlowServerOrInternalSpanIsNotAnOutboundCall() {
        for (SpanKind kind : List.of(SpanKind.SERVER, SpanKind.INTERNAL)) {
            Span span = tracer.spanBuilder("GET /orders").setSpanKind(kind).startSpan();
            sleep(THRESHOLD_MS * 2);
            span.end();
        }

        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(exporter.getFinishedSpanItems())
                .as("only an outbound call is the third case")
                .allSatisfy(span -> assertThat(stacktraceOf(span)).isNull());
    }

    @Test
    void theRequestThresholdComesFromTheSamePropertyTheServerUses() {
        assertThat(SlowQuerySpanProcessor.configuredRequestThresholdMillis())
                .isEqualTo(SlowQuerySpanProcessor.DEFAULT_REQUEST_THRESHOLD_MS);
        System.setProperty(SlowQuerySpanProcessor.REQUEST_THRESHOLD_PROPERTY, "900");
        try {
            assertThat(SlowQuerySpanProcessor.configuredRequestThresholdMillis()).isEqualTo(900);
        } finally {
            System.clearProperty(SlowQuerySpanProcessor.REQUEST_THRESHOLD_PROPERTY);
        }
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

        assertThat(LINES.split(SlowQuerySpanProcessor.format(frames)))
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
    void eachTraceIsCountedOnItsOwn() {
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

    @Test
    void theFifthRepeatOfAFastCallCarriesTheStackAndNoLaterRepeatDoes() {
        inOneTrace(() -> {
            for (int i = 0; i < 6; i++) {
                call("http://localhost:8081/api/books/" + (100 + i));
            }
        });

        List<SpanData> repeats = exportedCalls();
        assertThat(repeats).hasSize(6);
        for (int i = 0; i < SlowQuerySpanProcessor.N_PLUS_ONE_REPEATS - 1; i++) {
            assertThat(stacktraceOf(repeats.get(i))).as("repeat " + (i + 1)).isNull();
        }
        assertThat(stacktraceOf(repeats.get(4)))
                .as("the digits are what the loop varies, so six URLs are one call")
                .isNotNull()
                .contains("SlowQuerySpanProcessorTest");
        assertThat(stacktraceOf(repeats.get(5)))
                .as("one capture per call per trace, not one per repeat")
                .isNull();
    }

    @Test
    void twoCallsThatDifferInMoreThanTheirDigitsAreCountedOnTheirOwn() {
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                call("http://localhost:8081/api/books/" + i);
            }
            for (int i = 0; i < 5; i++) {
                call("http://localhost:8081/api/authors/" + i);
            }
        });

        List<SpanData> repeats = exportedCalls();
        assertThat(repeats).hasSize(9);
        for (int i = 0; i < 8; i++) {
            assertThat(stacktraceOf(repeats.get(i))).as("call " + (i + 1)).isNull();
        }
        assertThat(stacktraceOf(repeats.get(8)))
                .as("the fifth repeat of the second call")
                .isNotNull();
    }

    @Test
    void aSlowRepeatCountsTowardsTheFifthLikeAnyOther() {
        inOneTrace(() -> {
            for (int i = 0; i < 2; i++) {
                Span span = tracer.spanBuilder("GET")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute("http.request.method", "GET")
                        .setAttribute("url.full", "http://localhost:8081/api/books/" + i)
                        .startSpan();
                sleep(THRESHOLD_MS * 2);
                span.end();
            }
            for (int i = 2; i < 5; i++) {
                call("http://localhost:8081/api/books/" + i);
            }
        });

        List<SpanData> repeats = exportedCalls();
        assertThat(repeats).hasSize(5);
        assertThat(stacktraceOf(repeats.get(3))).as("the fourth repeat").isNull();
        assertThat(stacktraceOf(repeats.get(4)))
                .as("the two slow repeats count, so the fifth is still the fifth")
                .isNotNull();
    }

    @Test
    void repeatsStartedOnDifferentThreadsStillReachTheFifth() {
        Span entry = tracer.spanBuilder("GET /orders/{id}").startSpan();
        Context trace;
        try (Scope ignored = entry.makeCurrent()) {
            trace = Context.current();
        }
        // A fan-out: every call is made by a worker of its own, and a counter on the thread
        // would see one repeat on each of them and never reach five.
        for (int i = 0; i < 6; i++) {
            String url = "http://localhost:8081/api/books/" + i;
            onItsOwnThread(() -> tracer.spanBuilder("GET")
                    .setParent(trace)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("http.request.method", "GET")
                    .setAttribute("url.full", url)
                    .startSpan()
                    .end());
        }
        entry.end();

        List<SpanData> repeats = exportedCalls();
        assertThat(repeats).hasSize(6);
        for (int i = 0; i < SlowQuerySpanProcessor.N_PLUS_ONE_REPEATS - 1; i++) {
            assertThat(stacktraceOf(repeats.get(i))).as("repeat " + (i + 1)).isNull();
        }
        assertThat(stacktraceOf(repeats.get(4)))
                .as("the trace counts the repeats, not the thread that happened to make them")
                .isNotNull();
        assertThat(stacktraceOf(repeats.get(5))).as("and only the fifth").isNull();
    }

    @Test
    void theStackIsTheThreadThatMadeTheCallNotTheOneThatEndedIt() {
        List<Span> calls = new ArrayList<>();
        Span entry = tracer.spanBuilder("GET /orders/{id}").startSpan();
        try (Scope ignored = entry.makeCurrent()) {
            for (int i = 0; i < 5; i++) {
                calls.add(tracer.spanBuilder("GET")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute("http.request.method", "GET")
                        .setAttribute("url.full", "http://localhost:8081/api/books/" + i)
                        .startSpan());
            }
        }
        // As an asynchronous client ends them: on the completion callback's thread, whose stack
        // is the JDK's own and says nothing about the loop that made the calls.
        for (Span call : calls) {
            onItsOwnThread(call::end);
        }
        entry.end();

        String stacktrace = stacktraceOf(exportedCalls().get(4));
        assertThat(stacktrace).isNotNull();
        assertThat(stacktrace)
                .as("the call site, which only the starting thread knows")
                .contains("SlowQuerySpanProcessorTest.theStackIsTheThreadThatMadeTheCallNotTheOneThatEndedIt");
    }

    @Test
    void aTraceIsForgottenWhenItsLocalRootEnds() {
        inOneTrace(() -> {
            for (int i = 0; i < 4; i++) {
                query("select * from order_line where order_id = ?");
            }
            assertThat(processor.tracesCounted())
                    .as("counted while the request is in flight")
                    .isEqualTo(1);
        });

        assertThat(processor.tracesCounted())
                .as("the entry span ends after everything it started, so the counts can go")
                .isZero();
    }

    @Test
    void anOutboundCallThatIsNotHttpIsNeverCounted() {
        inOneTrace(() -> {
            for (int i = 0; i < 6; i++) {
                tracer.spanBuilder("publish orders")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute("messaging.system", "kafka")
                        .startSpan()
                        .end();
            }
        });

        assertThat(exportedCalls())
                .as("only an HTTP call is the fourth case")
                .allSatisfy(span -> assertThat(stacktraceOf(span)).isNull());
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

    /** An outbound HTTP call that ends well inside the threshold, so only the counter can fire. */
    private void call(String url) {
        tracer.spanBuilder("GET")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("url.full", url)
                .startSpan()
                .end();
    }

    /** The outbound spans the processor saw, in the order they ended. */
    private List<SpanData> exportedCalls() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        List<SpanData> calls = new ArrayList<>();
        for (SpanData span : exporter.getFinishedSpanItems()) {
            if (span.getKind() == SpanKind.CLIENT) {
                calls.add(span);
            }
        }
        return calls;
    }

    /** A database span that ends well inside the threshold, so only the counter can fire on it. */
    private void query(String statement) {
        tracer.spanBuilder(statement)
                .setAttribute("db.system", "h2")
                .setAttribute("db.query.text", statement)
                .startSpan()
                .end();
    }

    /** Runs {@code work} on a fresh thread and waits for it, so the end order stays fixed. */
    private static void onItsOwnThread(Runnable work) {
        Thread worker = new Thread(work);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
