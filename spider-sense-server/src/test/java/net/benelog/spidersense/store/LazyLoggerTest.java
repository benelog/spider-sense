package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The store's loggers are looked up at their first message, not when the store's classes are
 * initialised, which in agent mode is inside {@code premain}: a lookup there initialises
 * {@code java.util.logging} before the application's {@code main} could pick its manager.
 */
class LazyLoggerTest {

    @Test
    void theLoggerIsLookedUpAtTheFirstMessageAndOnce() {
        AtomicInteger lookups = new AtomicInteger();
        List<String> records = new ArrayList<>();
        System.Logger logger = new LazyLogger("net.benelog.spidersense.store.Writer", () -> {
            lookups.incrementAndGet();
            return recording(records);
        });

        assertThat(logger.getName()).isEqualTo("net.benelog.spidersense.store.Writer");
        assertThat(lookups).as("not by being made, nor by its name").hasValue(0);

        logger.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", new IllegalStateException("x"));
        logger.log(System.Logger.Level.DEBUG, "Spider Sense kept its exit flush: closed");

        assertThat(lookups).hasValue(1);
        assertThat(records).containsExactly(
                "WARNING Spider Sense could not store a batch java.lang.IllegalStateException: x",
                "DEBUG Spider Sense kept its exit flush: closed null");
    }

    /** The store's own loggers are lazy: initialising their classes asks for none. */
    @Test
    void theStoresLoggersAreLazy() {
        assertThat(LazyLogger.of(Writer.class)).isInstanceOf(LazyLogger.class);
        assertThat(LazyLogger.of(Writer.class).getName()).isEqualTo(Writer.class.getName());
    }

    /** A logger that writes each record as {@code <level> <message> <thrown>}. */
    private static System.Logger recording(List<String> records) {
        return new System.Logger() {
            @Override
            public String getName() {
                return "recording";
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
                records.add(level + " " + message + " " + thrown);
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
                records.add(level + " " + format + " " + null);
            }
        };
    }
}
