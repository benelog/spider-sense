package net.benelog.spidersense.gradle;

import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The arguments the plugin contributes to a forked JVM, computed when the task
 * runs rather than when the build file is read.
 *
 * <p>It holds providers and a {@link FileCollection} and never a
 * {@code Project}, which is what makes it safe to store in the configuration
 * cache, and it is what lets a {@code spiderSense { }} block placed after
 * {@code tasks.named('bootRun')} still be seen.
 *
 * <p>The jar is declared {@link InputFiles} so that a project dependency on it
 * is built before the task runs, and {@link PathSensitivity#NAME_ONLY} because
 * where it was resolved to — a Gradle cache directory here, a build directory
 * there — says nothing about what it contains.
 *
 * <p>{@code attached} false means this task is not one of {@code attachTo}, or
 * Spider Sense is switched off: nothing is contributed, and because the file
 * collection is built from the same decision, no jar is resolved either.
 * {@code agent} false serves the two Spider Sense tasks, which run the jar
 * rather than attach it: they take the {@code -Dspidersense.*} options, or the
 * {@code init} command line, and no {@code -javaagent}.
 */
public class SpiderSenseArguments implements CommandLineArgumentProvider {

    private final FileCollection jar;
    private final Provider<List<String>> arguments;
    private final Provider<Boolean> attached;
    private final boolean agent;
    private final String configuration;

    SpiderSenseArguments(FileCollection jar, Provider<List<String>> arguments, Provider<Boolean> attached,
            boolean agent, String configuration) {
        this.jar = jar;
        this.arguments = arguments;
        this.attached = attached;
        this.agent = agent;
        this.configuration = configuration;
    }

    /** The Spider Sense jar, or nothing when this task is not attached. */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public FileCollection getJar() {
        return jar;
    }

    /** The arguments that follow the agent: one per property of the block that has a value. */
    @Input
    public List<String> getArguments() {
        return attached.get() ? arguments.get() : List.of();
    }

    /** Whether this task gets anything at all. */
    @Input
    public boolean isAttached() {
        return attached.get();
    }

    /** Whether {@code -javaagent:} leads the list. */
    @Input
    public boolean isAgent() {
        return agent;
    }

    @Override
    public Iterable<String> asArguments() {
        if (!attached.get()) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        if (agent) {
            all.add("-javaagent:" + theJar().getAbsolutePath());
        }
        all.addAll(arguments.get());
        return all;
    }

    /**
     * The one jar, or an error that names the configuration rather than a stack
     * trace about a file collection. Nothing asks for it until the task runs.
     */
    File theJar() {
        List<File> files = List.copyOf(jar.getFiles());
        if (files.size() != 1) {
            throw new SingleJarExpected(configuration, files);
        }
        return files.get(0);
    }
}
