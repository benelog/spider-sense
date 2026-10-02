package net.benelog.spidersense.launcher;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Starts the collector + UI inside the current JVM, in its own class loader.
 *
 * <p>{@code SpiderSenseServer.main} binds and returns in agent mode (its Jetty pool is daemon), and
 * blocks in standalone mode. It is invoked through {@link SenseClassLoader#invokeStatic}, which
 * says why that is reflective and what it does with the context class loader.
 */
final class EmbeddedServer {

    static final String SERVER_CLASS = "net.benelog.spidersense.server.SpiderSenseServer";

    /** Non-null once started; keeps the loader alive and makes a second attach a no-op. */
    private static volatile @Nullable SenseClassLoader started;

    private EmbeddedServer() {
    }

    /**
     * Starts the collector and UI in this JVM, at most once.
     *
     * @return the port the server bound, which is the one to print and export to when
     *         {@code config} asked for port {@code 0}; empty when a server is already running in
     *         this JVM (in standalone mode the call does not return until shutdown)
     */
    static synchronized OptionalInt start(Config config) throws Exception {
        if (started != null) {
            return OptionalInt.empty();
        }
        Path jar = NestedJar.serverJar();
        SenseClassLoader loader = new SenseClassLoader(jar);
        // Set before the call: in standalone mode main() never returns, and in agent mode a second
        // agentmain must not start a second server while the first is still binding.
        started = loader;
        @Nullable Object bound;
        try {
            List<String> args = config.toServerArgs();
            // The distributable's own path, which the UI's "Copy CLI line" names (api.adoc#status,
            // /api/status.jar): the server runs out of the nested jar and cannot find it itself.
            Path own = NestedJar.ownJar();
            if (own != null) {
                args.add("--jar=" + own.toAbsolutePath());
            }
            // launch returns the bound port; main, in standalone mode, blocks until shutdown.
            bound = loader.invokeStatic(SERVER_CLASS, Config.AGENT.equals(config.mode()) ? "launch" : "main",
                    args.toArray(new String[0]));
        } catch (InvocationTargetException e) {
            started = null;
            Throwable cause = e.getCause();
            throw cause instanceof Exception ex ? ex : new IllegalStateException(cause);
        } catch (Exception | LinkageError e) {
            started = null;
            throw e instanceof Exception ex ? ex : new IllegalStateException(e);
        }
        return OptionalInt.of(bound instanceof Integer port ? port : config.port());
    }

    static boolean isRunning() {
        return started != null;
    }
}
