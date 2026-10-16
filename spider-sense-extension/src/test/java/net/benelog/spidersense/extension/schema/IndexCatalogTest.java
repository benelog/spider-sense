package net.benelog.spidersense.extension.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    /**
     * MySQL reports no column for a functional key part, which H2 cannot have: the part keeps its
     * place, so the column after it is never taken for the one the index seeks on.
     */
    @Test
    void aKeyPartWithNoColumnKeepsItsPlace() throws SQLException {
        DatabaseMetaData meta = indexInfo(
                indexRow("idx_email_status", 1, null),
                indexRow("idx_email_status", 2, "status"),
                indexRow("PRIMARY", 1, "id"));

        assertThat(IndexCatalog.indexesJson(IndexCatalog.indexesOf(meta, "shop", null, "customers")))
                .isEqualTo("[{\"name\":\"idx_email_status\",\"unique\":false,\"columns\":[\"\",\"status\"]},"
                        + "{\"name\":\"PRIMARY\",\"unique\":false,\"columns\":[\"id\"]}]");
    }

    @Test
    void aTableIsLookedUpOnceAProcess() throws SQLException {
        slow("select * from items where name = ?");
        slow("select * from items where category = ?");

        assertThat(exporter.getFinishedLogRecordItems()).hasSize(1);
    }

    /**
     * Read only when slow, the catalog would outlive the index that made the statement fast: the
     * index the block asked for is added, the application restarts, and the row never changes.
     */
    @Test
    void aStatementUnderTheThresholdIsLookedUpToo() throws SQLException {
        fast("select * from items where name = ?");

        assertThat(onlyRecord().getAttributes().get(TABLE)).isEqualTo("ITEMS");
    }

    /** An index created after a table was read, by a seed insert say, is read on its next statement. */
    @Test
    void aTableIsLookedUpAgainAfterDdlThatNamesIt() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table members (id bigint primary key, email varchar)");
        }
        fast("select id from members where email = ?");
        fast("select id from items where name = ?");
        ddl("create index idx_members_email on public.members (email)");
        fast("select id from members where email = ?");
        fast("select id from items where name = ?");

        assertThat(exporter.getFinishedLogRecordItems())
                .extracting(record -> record.getAttributes().get(TABLE) + " " + record.getAttributes().get(INDEXES))
                .as("members twice, the second time with its new index; items, which no DDL named, once")
                .hasSize(3)
                .satisfies(records -> {
                    assertThat(records.get(0)).startsWith("MEMBERS ").doesNotContain("IDX_MEMBERS_EMAIL");
                    assertThat(records.get(1)).startsWith("ITEMS ");
                    assertThat(records.get(2)).startsWith("MEMBERS ").contains("IDX_MEMBERS_EMAIL");
                });
    }

    @Test
    void theDdlThatChangesATablesIndexesIsRead() {
        assertThat(IndexCatalog.ddlTargets("create index idx on members (email)")).containsExactly("members");
        assertThat(IndexCatalog.ddlTargets("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx ON ONLY public.\"Members\" (email)"))
                .containsExactly("members");
        assertThat(IndexCatalog.ddlTargets("create index idx on orders ((lower(email)), status)")).containsExactly("orders");
        assertThat(IndexCatalog.ddlTargets("alter table if exists shop.orders add constraint uq unique (code)"))
                .containsExactly("orders");
        assertThat(IndexCatalog.ddlTargets("create table if not exists orders (id bigint primary key)"))
                .containsExactly("orders");
        assertThat(IndexCatalog.ddlTargets("drop table if exists orders, order_lines")).containsExactly("orders", "order_lines");
        assertThat(IndexCatalog.ddlTargets("drop index idx on orders")).as("MySQL").containsExactly("orders");
        assertThat(IndexCatalog.ddlTargets("drop index if exists idx")).as("any table's").isEmpty();
        assertThat(IndexCatalog.ddlTargets("create temporary table scratch (x int)")).as("no catalog").isNull();
        assertThat(IndexCatalog.ddlTargets("create local temporary table scratch (x int)")).isNull();
        assertThat(IndexCatalog.ddlTargets("create view v as select * from orders")).isNull();
        assertThat(IndexCatalog.ddlTargets("select * from orders where note = 'create index'")).isNull();
    }

    /** Past the statements it remembers, a new one is read only when slow, as before. */
    @Test
    void pastTheStatementsItRemembersOnlyASlowOneIsRead() throws SQLException {
        for (int i = 0; i < IndexCatalog.MAX_STATEMENTS; i++) {
            fast("select * from items where id = " + i);
        }
        exporter.reset();

        fast("select * from bare where x = ?");
        assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
        slow("select * from bare where x = ?");
        assertThat(onlyRecord().getAttributes().get(TABLE)).isEqualTo("BARE");
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

    /** A view has no indexes of its own; its base table's serve it, so it is not reported as bare. */
    @Test
    void aViewEmitsNothing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create view active_items as select * from items where category = 'active'");
        }

        slowOnAnyStatement("select * from active_items where name = ?");

        assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
    }

    /** H2 has an INFORMATION_SCHEMA.USERS of its own, which a {@code users} the application wrote never means. */
    @Test
    void anUnqualifiedNameIsTheCurrentSchemasTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table users (id bigint primary key, email varchar)");
            statement.execute("create index idx_users_email on users (email)");
            statement.execute("create schema audit");
            statement.execute("create table audit.items (id bigint, name varchar)");
        }

        slow("select * from users where email = ?");
        slow("select * from items where name = ?");

        assertThat(exporter.getFinishedLogRecordItems())
                .extracting(record -> record.getAttributes().get(SCHEMA) + "." + record.getAttributes().get(TABLE))
                .containsExactly("PUBLIC.USERS", "PUBLIC.ITEMS");
    }

    /** A table of a later schema on the search path is still found, and the catalog's own are not. */
    @Test
    void anUnqualifiedNameTheCurrentSchemaLacksIsLookedUpInTheOthers() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create schema audit");
            statement.execute("create table audit.entries (id bigint, at timestamp)");
        }

        slowOnAnyStatement("select * from entries where at > ?");
        slowOnAnyStatement("select * from sessions where id = ?");

        assertThat(exporter.getFinishedLogRecordItems())
                .extracting(record -> record.getAttributes().get(SCHEMA) + "." + record.getAttributes().get(TABLE))
                .as("INFORMATION_SCHEMA.SESSIONS is H2's, not the application's")
                .containsExactly("AUDIT.ENTRIES");
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

    /** A statement that took less than the threshold. */
    private void fast(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            IndexCatalog.afterExecute(statement, sql, 0, THRESHOLD_NANOS);
        }
    }

    /** DDL, run and then handed over as the advice would hand it. */
    private void ddl(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            IndexCatalog.afterExecute(statement, sql, 0, THRESHOLD_NANOS);
        }
    }

    /** For a statement the database would refuse to prepare: only the connection is needed. */
    private void slowOnAnyStatement(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            IndexCatalog.afterExecute(statement, sql, THRESHOLD_NANOS, THRESHOLD_NANOS);
        }
    }

    /** One row of {@code getIndexInfo}, as a driver answers it for a non-unique index's key part. */
    private static Map<String, Object> indexRow(String index, int ordinal, String column) {
        Map<String, Object> row = new HashMap<>();
        row.put("INDEX_NAME", index);
        row.put("NON_UNIQUE", true);
        row.put("TYPE", (short) DatabaseMetaData.tableIndexOther);
        row.put("ORDINAL_POSITION", (short) ordinal);
        row.put("COLUMN_NAME", column);
        return row;
    }

    /** A catalog whose {@code getIndexInfo} answers these rows: what H2 cannot be made to say. */
    @SafeVarargs
    private static DatabaseMetaData indexInfo(Map<String, Object>... rows) {
        return (DatabaseMetaData) Proxy.newProxyInstance(IndexCatalogTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getIndexInfo")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Iterator<Map<String, Object>> next = Arrays.asList(rows).iterator();
                    Object[] current = new Object[1];
                    return Proxy.newProxyInstance(IndexCatalogTest.class.getClassLoader(),
                            new Class<?>[] {ResultSet.class}, (set, call, values) -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> row = (Map<String, Object>) current[0];
                                return switch (call.getName()) {
                                    case "next" -> {
                                        current[0] = next.hasNext() ? next.next() : null;
                                        yield current[0] != null;
                                    }
                                    case "getString" -> (String) row.get((String) values[0]);
                                    case "getShort" -> (Short) row.get((String) values[0]);
                                    case "getBoolean" -> Boolean.TRUE.equals(row.get((String) values[0]));
                                    case "close" -> null;
                                    default -> throw new UnsupportedOperationException(call.getName());
                                };
                            });
                });
    }

    private LogRecordData onlyRecord() {
        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertThat(records).hasSize(1);
        return records.get(0);
    }
}
