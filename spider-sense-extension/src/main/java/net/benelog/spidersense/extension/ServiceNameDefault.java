package net.benelog.spidersense.extension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ConditionalResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Names a service that nothing else named, after the jar or the project it was started from, so
 * that a quick start without {@code -Dotel.service.name} is not {@code unknown_service:java}
 * (configuration.adoc#service-name).
 *
 * <p>It is the last word only where nobody spoke: it applies when neither {@code otel.service.name}
 * ({@code OTEL_SERVICE_NAME}, or {@code spidersense.service} through the launcher) nor
 * {@code otel.resource.attributes} names the service, and when the resource built so far is still
 * {@code unknown_service:java}. Its {@link #order()} puts it after the agent's Spring Boot
 * detector ({@code spring.application.name}, order 100) and its manifest detector
 * ({@code Implementation-Title}, order 300), so either of them wins, and before the agent's own
 * jar-name detector (order 1000), whose name keeps the version: {@code orders-0.1.0} would be a new
 * service on every release.
 */
public final class ServiceNameDefault implements ConditionalResourceProvider {

    static final String UNKNOWN = "unknown_service:java";
    static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    static final int ORDER = 900;

    /** A trailing version: {@code -0.1.0}, {@code -1.0-SNAPSHOT}, {@code -2.3.1.RELEASE}. */
    private static final Pattern VERSION = Pattern.compile("-\\d+(\\.\\d+)*([.-][0-9A-Za-z]+)*$");

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean shouldApply(ConfigProperties config, Resource existing) {
        if (config.getString("otel.service.name") != null) {
            return false;
        }
        if (config.getMap("otel.resource.attributes").containsKey(SERVICE_NAME.getKey())) {
            return false;
        }
        return UNKNOWN.equals(existing.getAttribute(SERVICE_NAME));
    }

    @Override
    public Resource createResource(ConfigProperties config) {
        try {
            String name = derive(System.getProperty("sun.java.command"), System.getProperty("java.class.path"),
                    ServiceNameDefault::isFile, ServiceNameDefault::holds);
            return name == null ? Resource.empty() : Resource.create(Attributes.of(SERVICE_NAME, name));
        } catch (RuntimeException e) {
            // A name is a convenience; the agent's own default is still a name.
            return Resource.empty();
        }
    }

    /**
     * The name a command line gives its service, or {@code null} when it gives none.
     *
     * <ul>
     *   <li>{@code -jar build/libs/orders-0.1.0.jar}: the jar's name without the extension and the
     *       version, {@code orders}.</li>
     *   <li>A main class: the class path entry that holds it. A jar is named as above, which is what
     *       an {@code installDist} start script runs; a directory under {@code build/} or
     *       {@code target/} is the project's, so {@code orders/build/classes/java/main} is
     *       {@code orders}. Failing both, the class's simple name.</li>
     * </ul>
     *
     * @param command   {@code sun.java.command}: the main class or the jar, then the arguments
     * @param classPath {@code java.class.path}
     * @param isFile    whether a path is a file, for a jar path with a space in it
     * @param holds     whether a class path entry holds a class file, given as a relative path
     */
    static @Nullable String derive(@Nullable String command, @Nullable String classPath,
            Predicate<String> isFile, ClassFileLookup holds) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String trimmed = command.trim();
        String first = trimmed.split("\\s+", 2)[0];
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return jarName(first);
        }
        // A jar whose path holds a space runs up to a ".jar" that is a file, not to the first space.
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (int at = lower.indexOf(".jar "); at >= 0; at = lower.indexOf(".jar ", at + 1)) {
            String candidate = trimmed.substring(0, at + ".jar".length());
            if (isFile.test(candidate)) {
                return jarName(candidate);
            }
        }
        // -m module/class
        String mainClass = first.substring(first.lastIndexOf('/') + 1);
        if (mainClass.isEmpty()) {
            return null;
        }
        String classFile = mainClass.replace('.', '/') + ".class";
        if (classPath != null) {
            for (String entry : classPath.split(Pattern.quote(File.pathSeparator), -1)) {
                if (entry.isEmpty() || !holds.test(entry, classFile)) {
                    continue;
                }
                String name = entry.toLowerCase(Locale.ROOT).endsWith(".jar") ? jarName(entry) : projectOf(entry);
                if (name != null) {
                    return name;
                }
                break;
            }
        }
        return mainClass.substring(mainClass.lastIndexOf('.') + 1);
    }

    /** Whether a class path entry holds a class file. */
    @FunctionalInterface
    interface ClassFileLookup {
        boolean test(String entry, String classFile);
    }

    /** A jar's file name without {@code .jar} and without a trailing version. */
    static @Nullable String jarName(String jar) {
        Path file = Paths.get(jar).getFileName();
        if (file == null) {
            return null;
        }
        String name = file.toString();
        name = name.substring(0, name.length() - ".jar".length());
        String bare = VERSION.matcher(name).replaceFirst("");
        return bare.isEmpty() ? (name.isEmpty() ? null : name) : bare;
    }

    /** The project a classes directory belongs to: the directory above its {@code build} or {@code target}. */
    static @Nullable String projectOf(String directory) {
        for (Path p = Paths.get(directory).toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && (name.toString().equals("build") || name.toString().equals("target"))) {
                Path project = p.getParent();
                Path projectName = project == null ? null : project.getFileName();
                return projectName == null ? null : projectName.toString();
            }
        }
        return null;
    }

    private static boolean isFile(String path) {
        try {
            return Files.isRegularFile(Paths.get(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean holds(String entry, String classFile) {
        try {
            Path path = Paths.get(entry);
            if (Files.isDirectory(path)) {
                return Files.isRegularFile(path.resolve(classFile));
            }
            if (!Files.isRegularFile(path)) {
                return false;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return zip.getEntry(classFile) != null;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
