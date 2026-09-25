package net.benelog.spidersense;

import java.util.concurrent.atomic.AtomicInteger;

import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Schema;

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

    /**
     * A memory database with the schema a server's open writes, as a server that ran and stopped
     * leaves its file: the CLI's open creates nothing, so a command pointed at a bare
     * {@link #memoryUrl()} is refused (cli.adoc#invocation).
     */
    public static String writtenUrl() {
        String url = memoryUrl();
        Database.open(url, null).close();
        return url;
    }

    /** The same as an older Spider Sense left it: the schema, and no read-only user. */
    public static String writtenUrlWithoutReader() {
        String url = memoryUrl();
        try (Database older = Database.open(url, null)) {
            older.sql().execute("DROP USER " + Schema.READER);
        }
        return url;
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
