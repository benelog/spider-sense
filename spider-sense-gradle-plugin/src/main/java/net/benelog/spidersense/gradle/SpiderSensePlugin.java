package net.benelog.spidersense.gradle;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.JavaExec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Runs an application under Spider Sense.
 *
 * <p>Spider Sense is a {@code -javaagent}, and the JVM that runs the
 * application is the one Gradle forks, not Gradle's own; so the whole of this
 * plugin is about getting the option onto that forked command line. It applies
 * nothing else and configures nothing it was not asked to: a project with
 * neither Spring Boot nor {@code application} gets the block and the two tasks,
 * and nothing attached.
 *
 * <p>Applying it creates the {@code spiderSense} extension and the
 * {@code spiderSense} configuration, adds a {@link SpiderSenseArguments} to the
 * {@code jvmArgumentProviders} of every {@link JavaExec} task named in
 * {@code attachTo}, and registers the {@code spiderSense} and
 * {@code spiderSenseInit} tasks. An argument provider rather than a write to
 * {@code jvmArgs} leaves the task's own arguments alone and defers every
 * decision to execution time, which is why the block may sit anywhere in the
 * build file and why nothing is resolved for a task that is not attached.
 *
 * <p>The specification is {@code docs/build-tools.md}.
 */
public class SpiderSensePlugin implements Plugin<Project> {

    /** The name of both the extension and the configuration. */
    public static final String NAME = "spiderSense";

    /** The group the two tasks are in. */
    public static final String GROUP = "spider sense";

    /** The launcher's main class: the standalone server and the CLI in one entry point. */
    public static final String MAIN_CLASS = "net.benelog.spidersense.launcher.SpiderSenseMain";

    /** {@code -PspiderSense.enabled=false} runs the application bare, {@code =true} the other way round. */
    public static final String ENABLED_PROPERTY = "spiderSense.enabled";

    /** {@code -PspiderSense.jar=<path>} attaches a jar that is not on Maven Central. */
    public static final String JAR_PROPERTY = "spiderSense.jar";

    /** The coordinates of the jar, without the version. */
    static final String JAR_COORDINATES = "net.benelog.spidersense:spider-sense";

    /** The task names that get the agent unless the block says otherwise. */
    static final Set<String> DEFAULT_ATTACH_TO = Set.of("bootRun", "bootTestRun", "run");

    @Override
    public void apply(Project project) {
        ObjectFactory objects = project.getObjects();
        ProviderFactory providers = project.getProviders();
        File projectDir = project.getLayout().getProjectDirectory().getAsFile();

        SpiderSenseExtension extension = project.getExtensions().create(NAME, SpiderSenseExtension.class);
        extension.getEnabled().convention(true);
        extension.getVersion().convention(pluginVersion());
        extension.getAttachTo().convention(DEFAULT_ATTACH_TO);
        extension.getService().convention(project.getName());
        // A list property is present and empty until something sets it, which would
        // make "ignore nothing" indistinguishable from "say nothing". A convention of
        // null is Gradle's way of saying "no value at all", so here emptiness can mean
        // what `ignoreEndpoints = []` says it means.
        extension.getIgnoreEndpoints().convention((Iterable<String>) null);

        Configuration configuration = createConfiguration(project, extension);

        // The project property wins over the block, so a single run can switch
        // Spider Sense off without the build file knowing.
        Provider<Boolean> enabled = providers.gradleProperty(ENABLED_PROPERTY)
                .map(Boolean::parseBoolean)
                .orElse(extension.getEnabled());

        // Where the jar comes from, in order: the project property, the block,
        // the single file of the configuration. The first two are a path and
        // need no resolution; only the third reaches a repository.
        Provider<File> namedJar = providers.gradleProperty(JAR_PROPERTY)
                .map(path -> projectDir.toPath().resolve(path).normalize().toFile())
                .orElse(extension.getJar().map(file -> file.getAsFile()));

        Provider<List<String>> systemProperties = systemProperties(objects, extension);

        attach(project, objects, extension, enabled, namedJar, configuration, systemProperties);
        registerTasks(project, objects, extension, namedJar, configuration, systemProperties, projectDir);
    }

    /**
     * The configuration the jar is resolved through: not consumable, because
     * nothing downstream wants it, and not transitive, because the single
     * distributable jar carries everything it needs inside itself.
     *
     * <p>The default dependency is what makes the two-line setup in the
     * documentation work, and a dependency the build adds replaces it, which is
     * how a checkout of this repository attaches the jar it just built.
     */
    private Configuration createConfiguration(Project project, SpiderSenseExtension extension) {
        return project.getConfigurations().create(NAME, configuration -> {
            configuration.setDescription("The Spider Sense jar attached to the application as -javaagent.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
            configuration.defaultDependencies(dependencies -> dependencies.add(
                    project.getDependencies().create(JAR_COORDINATES + ":" + extension.getVersion().get())));
        });
    }

    /**
     * Adds the argument provider to every {@link JavaExec} task. Whether it
     * contributes anything is decided from the task's name and the effective
     * {@code enabled}, so a task added to {@code attachTo} after this ran is
     * still attached, and an unattached task neither resolves the jar nor waits
     * for whatever builds it.
     */
    private void attach(Project project, ObjectFactory objects, SpiderSenseExtension extension,
            Provider<Boolean> enabled, Provider<File> namedJar, Configuration configuration,
            Provider<List<String>> systemProperties) {
        project.getTasks().withType(JavaExec.class).configureEach(task -> {
            String name = task.getName();
            Provider<Boolean> attached = enabled.zip(extension.getAttachTo(),
                    (on, names) -> on && names.contains(name));
            FileCollection jar = objects.fileCollection()
                    .from(new JarSource(attached, namedJar, configuration));
            task.getJvmArgumentProviders().add(new SpiderSenseArguments(
                    jar, systemProperties, attached, true, configuration.getName()));
        });
    }

    /**
     * The two tasks that run the jar rather than attach it: {@code spiderSense}
     * is {@code java -jar spider-sense.jar} with whatever {@code --args} say, so
     * the standalone server and the whole CLI are one task away in a project
     * that never checked out this repository, and {@code spiderSenseInit} is the
     * one CLI command that wants the project's own directory and the resolved
     * jar's path.
     *
     * <p>The jar is the entire class path, so neither task waits for the
     * application to compile, and the launcher finds the nested server jar
     * through its own code source exactly as {@code java -jar} would.
     */
    private void registerTasks(Project project, ObjectFactory objects, SpiderSenseExtension extension,
            Provider<File> namedJar, Configuration configuration, Provider<List<String>> systemProperties,
            File projectDir) {
        Provider<Boolean> always = objects.property(Boolean.class).value(true);
        FileCollection jar = objects.fileCollection().from(new JarSource(null, namedJar, configuration));
        Provider<String> url = baseUrl(extension);

        project.getTasks().register(NAME, JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Runs Spider Sense standalone, or a CLI command given with --args");
            run(task, jar, systemProperties, always, configuration.getName(), url);
        });

        // init writes the CLAUDE.md block and installs the skill, and both the
        // directory it writes into and the jar path it writes down are what this
        // build knows and the CLI does not.
        project.getTasks().register(NAME + "Init", JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Writes the Spider Sense block into CLAUDE.md and installs the agent skill");
            SpiderSenseArguments options = run(task, jar, systemProperties, always, configuration.getName(), url);
            Provider<List<String>> initArguments = always.map(ignored -> List.of(
                    "init",
                    "--dir=" + projectDir.getAbsolutePath(),
                    "--jar=" + options.theJar().getAbsolutePath()));
            task.getArgumentProviders().add(new SpiderSenseArguments(
                    objects.fileCollection(), initArguments, always, false, configuration.getName()));
        });
    }

    /** The setup the two tasks share: the jar as the class path, the launcher, the block's options, and the URL. */
    private SpiderSenseArguments run(JavaExec task, FileCollection jar, Provider<List<String>> systemProperties,
            Provider<Boolean> always, String configuration, Provider<String> url) {
        SpiderSenseArguments options = new SpiderSenseArguments(jar, systemProperties, always, false, configuration);
        task.setClasspath(jar);
        task.getMainClass().set(MAIN_CLASS);
        task.getJvmArgumentProviders().add(options);
        task.doFirst(new SetSenseUrl(url));
        return options;
    }

    /**
     * The base URL the block implies, so a CLI command asks the Spider Sense the
     * application is sending to rather than the default port. A host of
     * {@code 0.0.0.0} is an instruction to bind every interface, not an address
     * to call back on.
     */
    private Provider<String> baseUrl(SpiderSenseExtension extension) {
        return extension.getCollector().orElse(
                extension.getHost().orElse("127.0.0.1").zip(extension.getPort().orElse(4000),
                        (host, port) -> "http://" + ("0.0.0.0".equals(host) ? "127.0.0.1" : host) + ":" + port));
    }

    /**
     * One {@code -Dspidersense.<key>=<value>} per property of the block that has
     * a value, in the order the documentation's table lists them. A property
     * left unset contributes nothing, which is how the jar's own default stays
     * the default.
     */
    private Provider<List<String>> systemProperties(ObjectFactory objects, SpiderSenseExtension extension) {
        ListProperty<String> arguments = objects.listProperty(String.class);
        arguments.addAll(option("service", extension.getService()));
        arguments.addAll(option("port", extension.getPort()));
        arguments.addAll(option("host", extension.getHost()));
        arguments.addAll(option("collector", extension.getCollector()));
        arguments.addAll(option("db", extension.getDb()));
        arguments.addAll(option("retention.hours", extension.getRetentionHours()));
        arguments.addAll(option("slow.request.ms", extension.getSlowRequestMs()));
        arguments.addAll(option("slow.query.ms", extension.getSlowQueryMs()));
        arguments.addAll(option("open", extension.getOpen()));
        // A list property is empty rather than absent when nothing set it, so
        // emptiness is what "unset" means here.
        arguments.addAll(extension.getAppPackages().map(packages -> packages.isEmpty()
                ? List.<String>of()
                : List.of("-Dspidersense.app.packages=" + String.join(",", packages))));
        // Here emptiness cannot mean "unset": `ignoreEndpoints = []` is how a build
        // says "ignore nothing", and that has to reach the jar as an empty value. So
        // the convention is removed in apply() and presence is what "set" means.
        arguments.addAll(extension.getIgnoreEndpoints()
                .map(endpoints -> List.of("-Dspidersense.ignore.endpoints=" + String.join(",", endpoints)))
                .orElse(List.of()));
        return arguments;
    }

    private static Provider<List<String>> option(String key, Provider<?> value) {
        return value.map(v -> List.of("-Dspidersense." + key + "=" + v)).orElse(List.of());
    }

    /**
     * The plugin's own version, the default version of the jar it resolves, so
     * a plugin and its jar are always the same release. It is read from a
     * generated resource and not from the manifest, because TestKit and an IDE
     * run the plugin from class directories, where there is no manifest.
     */
    static String pluginVersion() {
        try (InputStream in = SpiderSensePlugin.class.getResourceAsStream("spider-sense-gradle-plugin.properties")) {
            if (in == null) {
                throw new IllegalStateException("spider-sense-gradle-plugin.properties is missing from the plugin");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Where a file collection gets the jar from.
     *
     * <p>A {@link Callable} and not a provider of files, because Gradle asks
     * this for the task's dependencies before anything runs: handed the
     * configuration itself it can see the task that builds the jar — a project
     * dependency on {@code :spider-sense-agent} — while a plain {@link File}
     * would say nothing and force the configuration to resolve too early.
     *
     * <p>{@code attached} null is the two Spider Sense tasks, which always want
     * the jar; otherwise an unattached task returns nothing here, and so nothing
     * is resolved and nothing is built for it.
     */
    static final class JarSource implements Callable<Object> {

        private final Provider<Boolean> attached;
        private final Provider<File> namedJar;
        private final Configuration configuration;

        JarSource(Provider<Boolean> attached, Provider<File> namedJar, Configuration configuration) {
            this.attached = attached;
            this.namedJar = namedJar;
            this.configuration = configuration;
        }

        @Override
        public Object call() {
            if (attached != null && !attached.get()) {
                return List.of();
            }
            File named = namedJar.getOrNull();
            return named != null ? named : configuration;
        }
    }

    /**
     * Sets {@code SPIDERSENSE_URL} on the forked JVM when the task runs.
     * A named action rather than a lambda: the configuration cache stores task
     * actions, and it can store this one.
     */
    static final class SetSenseUrl implements Action<Task> {

        private final Provider<String> url;

        SetSenseUrl(Provider<String> url) {
            this.url = url;
        }

        @Override
        public void execute(Task task) {
            ((JavaExec) task).environment("SPIDERSENSE_URL", url.get());
        }
    }
}
