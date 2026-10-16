package net.benelog.spidersense.launcher;

import java.util.logging.LogManager;

/**
 * An application that picks its own {@code java.util.logging} manager in {@code main}, as JBoss
 * Modules and WildFly's launcher do, and prints the manager it got. {@code LogManager} reads
 * {@code java.util.logging.manager} once, when it is first initialised, so the line names this
 * class's {@link Manager} only when nothing initialised {@code java.util.logging} before
 * {@code main}: not the agent's {@code premain}, and not the embedded server it starts.
 *
 * <p>Run by {@code SingleJarIT} in a JVM of its own, under {@code -javaagent}.
 */
public final class JulApp {

    private JulApp() {
    }

    public static void main(String[] args) {
        System.setProperty("java.util.logging.manager", Manager.class.getName());
        System.out.println("log manager: " + LogManager.getLogManager().getClass().getName());
    }

    /** The application's own manager, which changes nothing. */
    public static final class Manager extends LogManager {

        public Manager() {
            super();
        }
    }
}
