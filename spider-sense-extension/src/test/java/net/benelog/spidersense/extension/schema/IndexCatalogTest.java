package net.benelog.spidersense.extension.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The catalog against a real database, because every interesting part of it is what a driver
 * answers: how it folds an identifier, what it calls the primary key's index, in which order it
 * reports an index's columns.
 *
 * <p>H2 is the database here for the same reason it is the examples': it starts in the test's own
 * process and stores identifiers upper-case, which is the folding rule the lookup has to get right.
 */
class IndexCatalogTest {

    private static final long THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static final AttributeKey<String> TABLE = AttributeKey.stringKey("spidersense.schema.table");
    private static final AttributeKey<String> SCHEMA = AttributeKey.stringKey("spidersense.schema.schema");
    private static final AttributeKey<String> PRODUCT = AttributeKey.stringKey("spidersense.schema.product");
    private static final AttributeKey<String> INDEXES = AttributeKey.stringKey("spidersense.schema.indexes");

    private Connection connection;
    private InMemoryLogRecordExporter exporter;
    private SdkLoggerProvider loggerProvider;

    /** What the baggage said at the moment each record was emitted; see {@link #theLookupIsMarkedInTheBaggage()}. */
    private final List<String> marks = new ArrayList<>();

    @BeforeEach
    void start() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:index-catalog-test;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table items"
                    + " (id bigint primary key, name varchar, category varchar, supplier_id bigint)");
            statement.execute("create index idx_items_supplier on items (supplier_id, name)");
            statement.execute("create table bare (x int)");
        }
        exporter = InMemoryLogRecordExporter.create();
        marks.clear();
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(new Marks())
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        IndexCatalog.loggerProvider(loggerProvider);
        IndexCatalog.forget();
    }

    @AfterEach
    void stop() throws SQLException {
        IndexCatalog.loggerProvider(null);
        IndexCatalog.forget();
        loggerProvider.close();
        connection.close();
    }

    /** Reads what the record was emitted under, which is where the baggage entry shows up. */
    private final class Marks implements LogRecordProcessor {
        @Override
        public void onEmit(Context context, ReadWriteLogRecord logRecord) {
            marks.add(Baggage.fromContext(context).getEntryValue("spidersense.schema.lookup"));
        }
    }

    @Test
    void aSlowStatementGetsTheIndexesOfItsTable() throws SQLException {
        slow("select * from items where name = ?");

        LogRecordData record = onlyRecord();
        assertThat(record.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(record.getBodyValue().asString()).isEqualTo("index catalog of ITEMS");
        assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("spider-sense");
        assertThat(record.getAttributes().get(TABLE)).as("the database's spelling").isEqualTo("ITEMS");
        assertThat(record.getAttributes().get(SCHEMA)).isEqualTo("PUBLIC");
        assertThat(record.getAttributes().get(PRODUCT)).isEqualTo("H2");
        assertThat(record.getAttributes().get(INDEXES))
                .as("the primary key, and the two columns of the index in ordinal order")
                .contains("\"unique\":true,\"columns\":[\"ID\"]")
                .contains("{\"name\":\"IDX_ITEMS_SUPPLIER\",\"unique\":false,"
                        + "\"columns\":[\"SUPPLIER_ID\",\"NAME\"]}");
    }

    @Test
    void aTableIsLookedUpOnceAProcess() throws SQLException {
        slow("select * from items where name = ?");
        slow("select * from items where category = ?");

        assertThat(exporter.getFinishedLogRecordItems()).hasSize(1);
    }

    @Test
    void aStatementUnderTheThresholdIsNotLookedUp() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select * from items where name = ?")) {
            IndexCatalog.afterExecute(statement, "select * from items where name = ?",
                    THRESHOLD_NANOS - 1, THRESHOLD_NANOS);
        }

        assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
    }

    @Test
    void aTableWithoutAnIndexSaysSoRatherThanNothing() throws SQLException {
        slow("select * from bare where x = ?");

        assertThat(onlyRecord().getAttributes().get(INDEXES)).isEqualTo("[]");
    }

    @Test
    void aNameTheDatabaseHasNoTableForEmitsNothing() throws SQLException {
        slowOnAnyStatement("select * from nowhere where x = ?");

        assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
    }

    @Test
    void aQuotedNameIsNotFoldedAndSoDoesNotMatchAnUpperCaseTable() throws SQLException {
        slowOnAnyStatement("select * from \"items\" where name = ?");

        assertThat(exporter.getFinishedLogRecordItems())
                .as("H2 stores ITEMS, and a quoted name is what the application wrote")
                .isEmpty();
    }

    @Test
    void theLookupIsMarkedInTheBaggage() throws SQLException {
        slow("select * from items where name = ?");

        assertThat(marks)
                .as("what the sampler drops the lookup's own spans by")
                .containsExactly("1");
        assertThat(Baggage.current().getEntryValue("spidersense.schema.lookup"))
                .as("and nothing of it outlives the lookup")
                .isNull();
    }

    @Test
    void everyTableOfAJoinIsLookedUp() throws SQLException {
        slow("select * from items i join bare b on b.x = i.id");

        assertThat(exporter.getFinishedLogRecordItems())
                .extracting(record -> record.getAttributes().get(TABLE))
                .containsExactly("ITEMS", "BARE");
    }

    @Test
    void theFifthRepeatWithinATraceIsLookedUpThoughItIsFast() throws SQLException {
        String sql = "select * from items where id = ?";
        try (Scope trace = inTrace("1"); Statement statement = connection.createStatement()) {
            fast(statement, sql);
            fast(statement, sql);
            fast(statement, sql);
            fast(statement, sql);
            assertThat(exporter.getFinishedLogRecordItems())
                    .as("four fast queries are nobody's N+1 yet")
                    .isEmpty();

            fast(statement, sql);
        }

        assertThat(onlyRecord().getAttributes().get(TABLE)).isEqualTo("ITEMS");
    }

    @Test
    void repeatsAreNotCountedAcrossTraces() throws SQLException {
        String sql = "select * from items where id = ?";
        try (Statement statement = connection.createStatement()) {
            try (Scope first = inTrace("1")) {
                fast(statement, sql);
                fast(statement, sql);
                fast(statement, sql);
                fast(statement, sql);
            }
            try (Scope second = inTrace("2")) {
                fast(statement, sql);
            }
        }

        assertThat(exporter.getFinishedLogRecordItems())
                .as("the fifth of another trace is that trace's first")
                .isEmpty();
    }

    @Test
    void outsideATraceNothingIsCounted() throws SQLException {
        String sql = "select * from items where id = ?";
        try (Statement statement = connection.createStatement()) {
            for (int i = 0; i < 10; i++) {
                fast(statement, sql);
            }
        }

        assertThat(exporter.getFinishedLogRecordItems())
                .as("a statement outside a request is nobody's N+1")
                .isEmpty();
    }

    @Test
    void beyondTheDistinctStatementCapNothingIsCounted() throws SQLException {
        try (Scope trace = inTrace("1"); Statement statement = connection.createStatement()) {
            for (int i = 0; i < IndexCatalog.MAX_STATEMENTS; i++) {
                fast(statement, "select * from items where id = " + i);
            }
            for (int repeat = 0; repeat < IndexCatalog.N_PLUS_ONE_REPEATS; repeat++) {
                fast(statement, "select * from bare where x = ?");
            }
        }

        assertThat(exporter.getFinishedLogRecordItems())
                .as("the counter stopped adding keys, so the repeat is never seen")
                .isEmpty();
    }

    /** A statement of a trace that took no time at all: what an N+1's queries look like. */
    private void fast(Statement statement, String sql) {
        IndexCatalog.afterExecute(statement, sql, 1, THRESHOLD_NANOS);
    }

    /** A current span of a trace of its own, which is all the repeat counter reads. */
    private static Scope inTrace(String digit) {
        SpanContext context = SpanContext.create(
                "0000000000000000000000000000000".concat(digit),
                "000000000000000".concat(digit),
                TraceFlags.getSampled(),
                TraceState.getDefault());
        return Span.wrap(context).makeCurrent();
    }

    /** A prepared statement that really ran, which is the shape the advice sees. */
    private void slow(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (sql.contains("?")) {
                statement.setObject(1, null);
            }
            statement.execute();
            // Outside the agent the VirtualField the JDBC instrumentation fills is empty, so the
            // SQL is passed the way an execute(String) call would carry it.
            IndexCatalog.afterExecute(statement, sql, THRESHOLD_NANOS, THRESHOLD_NANOS);
        }
    }

    /** For a statement the database would refuse to prepare: only the connection is needed. */
    private void slowOnAnyStatement(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            IndexCatalog.afterExecute(statement, sql, THRESHOLD_NANOS, THRESHOLD_NANOS);
        }
    }

    private LogRecordData onlyRecord() {
        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertThat(records).hasSize(1);
        return records.get(0);
    }
}
