package warehouse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Random;

import javax.sql.DataSource;

/**
 * Creates the schema and, the first time it runs, fills it with enough rows for
 * an unindexed query to be genuinely slow.
 *
 * <p>The item count is a constructor parameter, so the tests seed a few hundred
 * rows into an in-memory database while the application seeds 100,000 into the
 * file one. Items go in as batches of {@link #BATCH} inside a single
 * transaction; a database that already has items is left alone, so only the
 * very first start pays for this.
 */
public class Seeder {

    /**
     * What the application seeds: enough that a full scan is measured in
     * hundreds of milliseconds rather than in tens.
     *
     * <p>The number was measured, not guessed. An item row is narrow — a SKU, a
     * three-word name, a category — so H2 scans 100,000 of them in about 30 ms,
     * comfortably under the 100 ms at which Spider Sense calls a query slow. At
     * 400,000 the unindexed search takes about 220 ms and the report about
     * 430 ms, which is what this example is for. Seeding them takes 1.8 s once
     * and leaves an 85 MB file, the same order as the bookstore's.
     */
    public static final int DEFAULT_ITEMS = 400_000;

    private static final int BATCH = 1_000;
    private static final int SUPPLIERS = 1_000;
    private static final int MOVED_ITEMS = 200;
    private static final int MOVEMENTS_PER_ITEM = 40;

    /**
     * The vocabularies are fixed and small on purpose: the load generator
     * searches for these exact words, so every {@code ?q=} it sends matches
     * something and still scans the whole table.
     */
    private static final String[] ADJECTIVES = {
            "steel", "oak", "copper", "matte", "glass", "woven", "carbon", "nylon", "brass", "cedar"};
    private static final String[] NOUNS = {
            "bracket", "hinge", "valve", "gasket", "spindle", "bearing", "sprocket", "flange",
            "coupling", "washer"};
    private static final String[] SIZES = {"S", "M", "L", "XL", "12mm", "20mm"};
    private static final String[] CATEGORIES = {
            "fasteners", "tools", "fittings", "electrical", "packaging", "safety"};
    private static final String[] COUNTRIES = {
            "DE", "SE", "PL", "KR", "JP", "IT", "TR", "PT", "CZ", "NL"};
    private static final String[] SUPPLIER_NAMES = {
            "Hansen", "Okonkwo", "Rossi", "Vargas", "Lindqvist", "Nowak", "Park", "Tanaka",
            "Yilmaz", "Ferreira", "Novak", "De Vries"};
    private static final String[] NOTES = {
            "cycle count", "goods in", "damaged in transit", "picked for order",
            "returned by customer", "supplier correction", "moved to overflow bay"};

    private final DataSource dataSource;
    private final int itemCount;

    public Seeder(DataSource dataSource) {
        this(dataSource, DEFAULT_ITEMS);
    }

    public Seeder(DataSource dataSource, int itemCount) {
        this.dataSource = dataSource;
        this.itemCount = itemCount;
    }

    /** Schema first, then the rows — skipping everything if items are already there. */
    public void seed() {
        try (Connection connection = dataSource.getConnection()) {
            schema(connection);
            long existing = count(connection, "items");
            if (existing > 0) {
                System.out.printf("servlet-warehouse: %,d items already in the database, not seeding%n",
                        existing);
                return;
            }
            long start = System.currentTimeMillis();
            connection.setAutoCommit(false);
            seedSuppliers(connection);
            seedItems(connection);
            seedMovements(connection);
            connection.commit();
            connection.setAutoCommit(true);
            System.out.printf("servlet-warehouse: seeded %,d suppliers, %,d items and %,d movements in %,d ms%n",
                    SUPPLIERS, itemCount, movementCount(), System.currentTimeMillis() - start);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the warehouse database", e);
        }
    }

    /**
     * There is deliberately no index on {@code items.name} or
     * {@code items.category}: that absence is what makes {@code /items?q=} and
     * {@code /api/report} slow, and it is not a bug to fix.
     */
    private void schema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists suppliers (
                        id identity primary key,
                        name varchar(120) not null,
                        country varchar(8) not null
                    )
                    """);
            statement.execute("""
                    create table if not exists items (
                        id identity primary key,
                        sku varchar(20) not null unique,
                        name varchar(120) not null,
                        category varchar(40) not null,
                        supplier_id bigint not null,
                        quantity int not null,
                        unit_price decimal(10, 2) not null,
                        location varchar(20) not null
                    )
                    """);
            statement.execute("""
                    create table if not exists movements (
                        id identity primary key,
                        item_id bigint not null,
                        supplier_id bigint not null,
                        delta int not null,
                        note varchar(200),
                        moved_at timestamp not null
                    )
                    """);
            statement.execute("create index if not exists idx_movements_item on movements (item_id)");
        }
    }

    private void seedSuppliers(Connection connection) throws SQLException {
        Random random = new Random(11);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into suppliers (name, country) values (?, ?)")) {
            for (int i = 1; i <= SUPPLIERS; i++) {
                insert.setString(1, SUPPLIER_NAMES[i % SUPPLIER_NAMES.length] + " Supply "
                        + String.format("%03d", i));
                insert.setString(2, COUNTRIES[random.nextInt(COUNTRIES.length)]);
                insert.addBatch();
                if (i % BATCH == 0 || i == SUPPLIERS) {
                    insert.executeBatch();
                }
            }
        }
    }

    /**
     * The SKU is the item's own id in {@code SKU-%06d}, which only works because
     * the table was empty a moment ago and {@code identity} hands out 1, 2, 3 …
     * in insert order. It makes {@code /items/SKU-000001} and the load
     * generator's URLs predictable without a lookup.
     */
    private void seedItems(Connection connection) throws SQLException {
        Random random = new Random(42);
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into items (sku, name, category, supplier_id, quantity, unit_price, location)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (int i = 1; i <= itemCount; i++) {
                insert.setString(1, "SKU-%06d".formatted(i));
                insert.setString(2, ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " "
                        + NOUNS[random.nextInt(NOUNS.length)] + " "
                        + SIZES[random.nextInt(SIZES.length)]);
                insert.setString(3, CATEGORIES[random.nextInt(CATEGORIES.length)]);
                insert.setLong(4, 1 + random.nextInt(SUPPLIERS));
                insert.setInt(5, 20 + random.nextInt(400));
                insert.setBigDecimal(6, new java.math.BigDecimal(
                        String.format("%.2f", 1.5 + random.nextInt(48_000) / 100.0)));
                insert.setString(7, "%s-%02d-%02d".formatted(
                        (char) ('A' + random.nextInt(6)), 1 + random.nextInt(20), 1 + random.nextInt(8)));
                insert.addBatch();
                if (i % BATCH == 0 || i == itemCount) {
                    insert.executeBatch();
                }
            }
        }
    }

    /** Only the first 200 items get movements, which is what makes {@code /items/*} an N+1. */
    private void seedMovements(Connection connection) throws SQLException {
        Random random = new Random(7);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into movements (item_id, supplier_id, delta, note, moved_at)
                values (?, ?, ?, ?, ?)
                """)) {
            int rows = 0;
            for (int itemId = 1; itemId <= Math.min(MOVED_ITEMS, itemCount); itemId++) {
                for (int i = 0; i < MOVEMENTS_PER_ITEM; i++) {
                    insert.setLong(1, itemId);
                    insert.setLong(2, 1 + random.nextInt(SUPPLIERS));
                    insert.setInt(3, random.nextInt(26) - 5);
                    insert.setString(4, NOTES[random.nextInt(NOTES.length)]);
                    insert.setTimestamp(5, now);
                    insert.addBatch();
                    if (++rows % BATCH == 0) {
                        insert.executeBatch();
                    }
                }
            }
            insert.executeBatch();
        }
    }

    private int movementCount() {
        return Math.min(MOVED_ITEMS, itemCount) * MOVEMENTS_PER_ITEM;
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
