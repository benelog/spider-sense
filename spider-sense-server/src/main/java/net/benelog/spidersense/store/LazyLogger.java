package net.benelog.spidersense.store;

import java.util.ResourceBundle;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A {@link System.Logger} that looks the real one up at its first use rather than when the class
 * that holds it is initialised.
 *
 * <p>In agent mode the store's classes are initialised inside {@code premain}, and
 * {@link System#getLogger} initialises {@code java.util.logging} there and then. That reads
 * {@code java.util.logging.manager} once and for all, so an application that names its own manager
 * in {@code main}, as JBoss Modules does, would get the JDK's. The lookup waits for the first
 * message instead, which in an ordinary run is long after {@code main}, or never comes.
 *
 * <p>It is a {@code System.Logger} itself, so a call site reads as it would with the real one, and
 * {@code java.util.logging} still names the caller as the source of a record: it skips the frames
 * of every {@code System.Logger}.
 */
final class LazyLogger implements System.Logger {

    private final String name;
    private final Supplier<System.Logger> lookup;
    private volatile System.@Nullable Logger logger;

    /** The logger named after {@code owner}, looked up at its first use. */
    static System.Logger of(Class<?> owner) {
        String name = owner.getName();
        return new LazyLogger(name, () -> System.getLogger(name));
    }

    /** @param lookup the real logger, asked for once, at the first use */
    LazyLogger(String name, Supplier<System.Logger> lookup) {
        this.name = name;
        this.lookup = lookup;
    }

    private System.Logger logger() {
        System.Logger found = logger;
        if (found == null) {
            found = lookup.get();
            logger = found;
        }
        return found;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isLoggable(Level level) {
        return logger().isLoggable(level);
    }

    @Override
    public void log(Level level, @Nullable ResourceBundle bundle, @Nullable String message,
            @Nullable Throwable thrown) {
        logger().log(level, bundle, message, thrown);
    }

    @Override
    public void log(Level level, @Nullable ResourceBundle bundle, @Nullable String format,
            @Nullable Object... parameters) {
        logger().log(level, bundle, format, parameters);
    }
}
