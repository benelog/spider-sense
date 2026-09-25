package net.benelog.spidersense.launcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The class loader of the collector + UI server.
 *
 * <p>Its parent is the platform class loader, not the application's, so the server never sees the
 * monitored application's classes and the application never sees Jetty or protobuf from the server.
 *
 * <p>The name of this class is part of the contract: {@code SpiderSenseAgent} puts it in
 * {@code otel.javaagent.exclude-class-loaders}, and the OpenTelemetry agent matches that list
 * against the class name of the loader that defined a class. Renaming it silently turns the UI's
 * own Jetty requests back into spans.
 */
public final class SenseClassLoader extends URLClassLoader {

    public SenseClassLoader(Path serverJar) throws MalformedURLException {
        this(serverJar.toUri().toURL());
    }

    public SenseClassLoader(URL serverJar) {
        super("spider-sense", new URL[] {serverJar}, ClassLoader.getPlatformClassLoader());
    }

    /**
     * The hand-over to the server jar: calls the static {@code method(String[])} of
     * {@code className}, loaded and initialised by this loader, with this loader as the thread's
     * context class loader, so anything the server looks up by thread context finds its own jar.
     * The previous context class loader is restored afterwards, whatever happens.
     *
     * <p>It is reflective because the launcher has no compile-time dependency on the server.
     *
     * @return what the method returned, {@code null} for a {@code void} one
     * @throws InvocationTargetException when the method itself threw; its cause is what it threw
     * @throws ReflectiveOperationException when the class or the method is not there to call
     */
    @Nullable Object invokeStatic(String className, String method, String[] args)
            throws ReflectiveOperationException {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try {
            current.setContextClassLoader(this);
            Method entry = Class.forName(className, true, this).getMethod(method, String[].class);
            return entry.invoke(null, (Object) args);
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}
