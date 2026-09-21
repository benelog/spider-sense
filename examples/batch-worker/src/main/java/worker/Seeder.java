package worker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Random;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills the database the first time the worker starts, and leaves it alone
 * every time after that.
 *
 * <p>The event count is what makes the jobs slow: three hundred thousand rows
 * with no index on {@code account_id} cannot be grouped in under half a second,
 * and cannot be streamed into a CSV without the JVM noticing. A third of the
 * accounts are overdrawn so {@code send-reminders} always has twenty targets.
 */
public final class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private static final int ACCOUNTS = 2_000;
    private static final int BATCH = 1_000;
    private static final String[] KINDS = {"purchase", "refund", "fee", "payout"};
    private static final String[] FIRST_NAMES = {
            "Ada", "Björn", "Chidi", "Dara", "Elif", "Farid", "Grete", "Hana", "Ivo", "Jun",
            "Kaya", "Liv", "Mira", "Nils", "Oona", "Pia", "Quinn", "Rui", "Sora", "Tove"};
    private static final String[] LAST_NAMES = {
            "Ahlberg", "Brandt", "Castellan", "Duarte", "Eriksen", "Fontaine", "Gaddis",
            "Halloran", "Ishikawa", "Jovanovic"};
    private static final String[] NOTES = {
            "card ending 4417", "monthly settlement", "chargeback, disputed",
            "partial payout to the seller", "service fee for the period", "top-up from the linked account"};

    private final DataSource dataSource;
    private final int eventCount;

    public Seeder(DataSource dataSource, int eventCount) {
        this.dataSource = dataSource;
        this.eventCount = eventCount;
    }

    /** Seeds when {@code events} is empty; otherwise says what was already there. */
    public void seed() {
        long existing = count("events");
        if (existing > 0) {
            log.info("Database already holds {} events over {} accounts, skipping the seed", existing, count("accounts"));
            return;
        }
        long start = System.currentTimeMillis();
        seedAccounts();
        seedEvents();
        log.info("Seeded {} accounts and {} events in {} ms", ACCOUNTS, eventCount, System.currentTimeMillis() - start);
    }

    private void seedAccounts() {
        Random random = new Random(11);
        String sql = "insert into accounts (name, balance, reminded_at) values (?, ?, null)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (int i = 1; i <= ACCOUNTS; i++) {
                statement.setString(1, FIRST_NAMES[i % FIRST_NAMES.length] + " "
                        + LAST_NAMES[(i / FIRST_NAMES.length) % LAST_NAMES.length] + " #" + i);
                // A third of them are overdrawn, so the reminder job never runs out of work.
                statement.setBigDecimal(2, java.math.BigDecimal.valueOf(
                        random.nextInt(3) == 0 ? -random.nextInt(50_000) / 100.0 : random.nextInt(200_000) / 100.0));
                statement.addBatch();
                if (i % BATCH == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not seed accounts", e);
        }
    }

    private void seedEvents() {
        Random random = new Random(42);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.systemDefault()));
        String sql = "insert into events (account_id, amount, kind, note, occurred_at) values (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (int i = 1; i <= eventCount; i++) {
                String kind = KINDS[random.nextInt(KINDS.length)];
                double amount = (-200_00 + random.nextInt(700_00)) / 100.0;
                // purchase and fee take money out, refund and payout put it back, so a
                // reconciled balance is as likely to end up negative as positive and the
                // reminder job never runs out of overdrawn accounts.
                if (kind.equals("purchase") || kind.equals("fee")) {
                    amount = -amount;
                }
                statement.setInt(1, 1 + random.nextInt(ACCOUNTS));
                statement.setBigDecimal(2, java.math.BigDecimal.valueOf(amount));
                statement.setString(3, kind);
                statement.setString(4, NOTES[random.nextInt(NOTES.length)]);
                statement.setTimestamp(5, now);
                statement.addBatch();
                if (i % BATCH == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not seed events", e);
        }
    }

    private long count(String table) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count " + table, e);
        }
    }
}
