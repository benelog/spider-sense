package worker;

import java.io.IOException;
import java.util.Random;

/**
 * The mail server that is not always there.
 *
 * <p>A send takes 5 to 20 ms and fails one time in eight with an
 * {@link IOException}. The randomness is seeded in the constructor, so a test
 * that asks for the same seed gets the same run of failures.
 */
public class ReminderGateway {

    /** One send in this many fails. */
    public static final int FAILURE_IN = 8;

    private final Random random;

    public ReminderGateway() {
        this(System.nanoTime());
    }

    public ReminderGateway(long seed) {
        this.random = new Random(seed);
    }

    /** Sends one reminder, slowly, and sometimes not at all. */
    public void send(long accountId, String name) throws IOException {
        int delay;
        boolean fails;
        synchronized (random) {
            delay = 5 + random.nextInt(16);
            fails = random.nextInt(FAILURE_IN) == 0;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending to account " + accountId, e);
        }
        if (fails) {
            throw new IOException("SMTP 451 mailbox busy, try later");
        }
    }
}
