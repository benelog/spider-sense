package net.benelog.spidersense.launcher;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

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
}
