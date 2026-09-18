package worker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One pass over every job, against an in-memory database, with no threads leaked.
 *
 * <p>It runs {@link WorkerApp#runOnce(Settings)} rather than {@code main},
 * because {@code main} ends in {@link System#exit} and a test JVM would go with
 * it. The archive hold is cut to a few milliseconds so eight slices over three
 * connections take a moment instead of a quarter of a minute.
 */
class WorkerOnceTest {

    private static final String URL = "jdbc:h2:mem:worker-test;DB_CLOSE_DELAY=-1";

    @Test
    void onePassReconcilesRemindsArchivesAndReports() throws SQLException {
        WorkerApp.Result result = WorkerApp.runOnce(new Settings(URL, 3_000, 0, 20));

        assertThat(result.leakedThreads()).isZero();
        assertThat(result.reportBytes()).isPositive();
        assertThat(accountsWithAWrongBalance()).isZero();
        assertThat(remindedAccounts()).isPositive();
    }

    /** Every account that has events must carry their sum, to the cent. */
    private long accountsWithAWrongBalance() throws SQLException {
        return scalar("""
                select count(*) from (
                    select a.id from accounts a join events e on e.account_id = a.id
                    group by a.id, a.balance
                    having a.balance <> sum(e.amount)
                )
                """);
    }

    private long remindedAccounts() throws SQLException {
        return scalar("select count(*) from accounts where reminded_at is not null");
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }
}
