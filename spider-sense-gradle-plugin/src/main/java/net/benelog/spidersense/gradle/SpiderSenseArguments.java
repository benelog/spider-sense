package net.benelog.spidersense.gradle;

import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>It serves three roles, one factory each: {@link #javaagent} for a task in
 * {@code attachTo}, {@link #jvmOptions} for the three Spider Sense tasks, which
 * run the jar rather than attach it and so take the {@code -Dspidersense.*}
 * options and no {@code -javaagent}, and {@link #programArguments} for the
 * command line of {@code spiderSenseInit} and {@code spiderSenseCheck}.
 */
public class SpiderSenseArguments implements CommandLineArgumentProvider {

    private static final Logger LOGGER = Logging.getLogger(SpiderSenseArguments.class);

    private final FileCollection jar;
    private final Provider<List<String>> arguments;
    /** Null for always; see {@link #javaagent} for what false means. */
    private final @Nullable Provider<Boolean> attached;
    /** What the build says as the task starts, or nothing; null for a task that runs the jar. */
    private final @Nullable Provider<String> warning;
    private final boolean agent;
    private final String configuration;

    private SpiderSenseArguments(FileCollection jar, Provider<List<String>> arguments,
            @Nullable Provider<Boolean> attached, @Nullable Provider<String> warning, boolean agent,
            String configuration) {
        this.jar = jar;
        this.arguments = arguments;
        this.attached = attached;
        this.warning = warning;
        this.agent = agent;
        this.configuration = configuration;
    }

    /**
     * {@code -javaagent:} and the block's {@code -Dspidersense.*} options, for a
     * task that runs the application.
     *
     * @param attached      false when the block does not list this task to attach
     *                      to, Spider Sense is switched off, or the task runs on a
     *                      Java the agent does not run on: nothing is contributed,
     *                      and because the file collection is built from the same
     *                      decision, no jar is resolved either
     * @param warning       what the build says of the task's Java, logged once the
     *                      arguments are asked for, or no value
     * @param configuration the configuration the jar is resolved through, named
     *                      when it does not resolve to exactly one file
     */
    static SpiderSenseArguments javaagent(FileCollection jar, Provider<List<String>> systemProperties,
            Provider<Boolean> attached, Provider<String> warning, String configuration) {
        return new SpiderSenseArguments(jar, systemProperties, attached, warning, true, configuration);
    }

    /** The block's {@code -Dspidersense.*} options alone, for a task that runs the jar itself. */
    static SpiderSenseArguments jvmOptions(FileCollection jar, Provider<List<String>> systemProperties,
            String configuration) {
        return new SpiderSenseArguments(jar, systemProperties, null, null, false, configuration);
    }

    /** A command line after the main class, which needs no jar of its own. */
    static SpiderSenseArguments programArguments(FileCollection none, Provider<List<String>> arguments) {
        return new SpiderSenseArguments(none, arguments, null, null, false, "");
    }

    /** The Spider Sense jar, or nothing when this task is not attached. */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public FileCollection getJar() {
        return jar;
    }

    /**
     * The arguments that follow the agent, if any: one per property of the block
     * that has a value, or the command line of a Spider Sense task.
     */
    @Input
    public List<String> getArguments() {
        return isAttached() ? arguments.get() : List.of();
    }

    /** Whether this task gets anything at all. */
    @Input
    public boolean isAttached() {
        return attached == null || attached.get();
    }

    /** Whether {@code -javaagent:} leads the list. */
    @Input
    public boolean isAgent() {
        return agent;
    }

    @Override
    public Iterable<String> asArguments() {
        if (warning != null && warning.isPresent()) {
            LOGGER.warn(warning.get());
        }
        if (!isAttached()) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        if (agent) {
            all.add("-javaagent:" + singleJar().getAbsolutePath());
        }
        all.addAll(arguments.get());
        return all;
    }

    /**
     * The one jar, or an error that names the configuration rather than a stack
     * trace about a file collection. Nothing asks for it until the task runs.
     */
    File singleJar() {
        List<File> files = List.copyOf(jar.getFiles());
        if (files.size() != 1) {
            throw new SingleJarExpected(configuration, files);
        }
        return files.get(0);
    }
}
