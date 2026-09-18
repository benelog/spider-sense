package net.benelog.spidersense.gradle;

import org.gradle.api.GradleException;

import java.io.File;
import java.util.List;

/**
 * The {@code spiderSense} configuration held something other than the one
 * Spider Sense jar. Its own class so that the message says which configuration
 * and what was in it: emptiness usually means a repository the jar is not in,
 * and more than one file a dependency that dragged something along.
 */
public class SingleJarExpected extends GradleException {

    SingleJarExpected(String configuration, List<File> files) {
        super("The " + configuration + " configuration must hold exactly one file, the Spider Sense jar,"
                + " but it holds " + files.size()
                + (files.isEmpty() ? "." : ": " + files.stream().map(File::getName).toList() + "."));
    }
}
