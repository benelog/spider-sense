package bookstore.service;

import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction boundaries calling TransactionTemplate directly, without AOP.
 * The code a service wraps in write()/read() is exactly the transaction scope.
 */
public class Transactions {

    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public Transactions(DataSource dataSource) {
        PlatformTransactionManager manager = new JdbcTransactionManager(dataSource);
        this.writeTx = new TransactionTemplate(manager);
        this.readTx = new TransactionTemplate(manager);
        this.readTx.setReadOnly(true);
    }

    /** A write transaction. Rolls back when a runtime exception escapes. */
    public <T> T write(Supplier<T> action) {
        return writeTx.execute(status -> action.get());
    }

    /** A read-only transaction. Groups several reads into one snapshot. */
    public <T> T read(Supplier<T> action) {
        return readTx.execute(status -> action.get());
    }
}
