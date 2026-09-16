package net.benelog.spidersense;

import java.util.concurrent.atomic.AtomicInteger;

import net.benelog.spidersense.server.Config;

/**
 * Configuration for a test: always an in-memory H2 with a name of its own, so a
 * test never touches {@code ~/db/spider-sense} and two tests never share a table.
 */
public final class TestStore {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestStore() {
    }

    /** {@code DB_CLOSE_DELAY=-1} keeps the database alive between pooled connections. */
    public static String memoryUrl() {
        return "jdbc:h2:mem:spidersense-test-" + COUNTER.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE";
    }

    public static Config config(String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "--port=0";
        args[1] = "--db=" + memoryUrl();
        args[2] = "--retention.hours=24";
        System.arraycopy(extra, 0, args, 3, extra.length);
        return Config.parse(args);
    }
}
