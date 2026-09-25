package net.benelog.spidersense.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
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
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Runs an application under Spider Sense.
 *
 * <p>Spider Sense is a {@code -javaagent}, and the JVM that runs the
 * application is the one Gradle forks, not Gradle's own; so the whole of this
 * plugin is about getting the option onto that forked command line. It applies
 * nothing else and configures nothing it was not asked to: a project with
 * neither Spring Boot nor {@code application} gets the block and the tasks,
 * and nothing attached.
 *
 * <p>Applying it creates the {@code spiderSense} extension and the
 * {@code spiderSense} configuration, adds a {@link SpiderSenseArguments} to the
 * {@code jvmArgumentProviders} of every {@link JavaExec} and {@link Test} task
 * named in {@code attachTo}, and registers the {@code spiderSense},
 * {@code spiderSenseInit} and {@code spiderSenseCheck} tasks. An argument
 * provider rather than a write to
 * {@code jvmArgs} leaves the task's own arguments alone and defers every
 * decision to execution time, which is why the block may sit anywhere in the
 * build file and why nothing is resolved for a task that is not attached.
 *
 * <p>The specification is {@code gradle-plugin.adoc}.
 */
public class SpiderSensePlugin implements Plugin<Project> {

    /** The name of both the extension and the configuration. */
    public static final String NAME = "spiderSense";

    /** The group the three tasks are in. */
    public static final String GROUP = "spider sense";

    /** The launcher's main class: the standalone server and the CLI in one entry point. */
    public static final String MAIN_CLASS = "net.benelog.spidersense.launcher.SpiderSenseMain";

    /** {@code -PspiderSense.enabled=false} runs the application bare, {@code =true} the other way round. */
    public static final String ENABLED_PROPERTY = "spiderSense.enabled";

    /** {@code -PspiderSense.jar=<path>} attaches a jar that is not on Maven Central. */
    public static final String JAR_PROPERTY = "spiderSense.jar";

    /** {@code -PspiderSense.check.since=<selector>} judges another window for one run. */
    public static final String CHECK_SINCE_PROPERTY = "spiderSense.check.since";

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
        // The check block's window and scope: since the application was last
        // started, the service the block names, and a verdict over nothing is
        // not a pass.
        extension.getCheck().getSince().convention("start");
        extension.getCheck().getService().convention(extension.getService());
        extension.getCheck().getFailOnNoRequests().convention(true);

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
        Provider<List<String>> checkArguments = checkArguments(objects, providers, extension.getCheck());

        attach(project, objects, extension, enabled, namedJar, configuration, systemProperties);
        registerTasks(project, objects, extension, namedJar, configuration, systemProperties, checkArguments,
                projectDir);
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
     * Adds the argument provider to every task that forks a JVM for the
     * project's code: {@link JavaExec} for {@code bootRun} and {@code run}, and
     * {@link Test}, which forks one too but is not a {@code JavaExec}. Whether
     * it contributes anything is decided from the task's name and the effective
     * {@code enabled}, so a task added to {@code attachTo} after this ran is
     * still attached, and an unattached task neither resolves the jar nor waits
     * for whatever builds it.
     */
    private void attach(Project project, ObjectFactory objects, SpiderSenseExtension extension,
            Provider<Boolean> enabled, Provider<File> namedJar, Configuration configuration,
            Provider<List<String>> systemProperties) {
        Action<Task> attachOne = task -> {
            String name = task.getName();
            Provider<Boolean> attached = enabled.zip(extension.getAttachTo(),
                    (on, names) -> on && names.contains(name));
            FileCollection jar = objects.fileCollection()
                    .from(JarSource.whenAttached(attached, namedJar, configuration));
            ((JavaForkOptions) task).getJvmArgumentProviders().add(SpiderSenseArguments.javaagent(
                    jar, systemProperties, attached, configuration.getName()));
        };
        project.getTasks().withType(JavaExec.class).configureEach(attachOne);
        project.getTasks().withType(Test.class).configureEach(attachOne);
    }

    /**
     * The three tasks that run the jar rather than attach it: {@code spiderSense}
     * is {@code java -jar spider-sense.jar} with whatever {@code --args} say, so
     * the standalone server and the whole CLI are one task away in a project
     * that never checked out this repository, {@code spiderSenseInit} is the
     * one CLI command that wants the project's own directory and the resolved
     * jar's path, and {@code spiderSenseCheck} is {@code check} with the rules
     * of the {@code check { }} block, turned into a build failure.
     *
     * <p>The jar is the entire class path, so no task waits for the application
     * to compile, and the launcher finds the nested server jar through its own
     * code source exactly as {@code java -jar} would.
     *
     * <p>All three run whatever {@code enabled} says: it decides what is
     * attached to the application's JVM, and asking a Spider Sense that is
     * already running what it saw is a different question from whether this
     * build starts one.
     */
    private void registerTasks(Project project, ObjectFactory objects, SpiderSenseExtension extension,
            Provider<File> namedJar, Configuration configuration, Provider<List<String>> systemProperties,
            Provider<List<String>> checkArguments, File projectDir) {
        FileCollection jar = objects.fileCollection().from(JarSource.always(namedJar, configuration));
        Provider<String> url = baseUrl(extension);

        project.getTasks().register(NAME, JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Runs Spider Sense standalone, or a CLI command given with --args");
            configureJarRun(task, jar, systemProperties, configuration.getName(), url);
        });

        // init writes the CLAUDE.md block and installs the skills, and both the
        // directory it writes into and the jar path it writes down are what this
        // build knows and the CLI does not.
        project.getTasks().register(NAME + "Init", JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Writes the Spider Sense block into CLAUDE.md and installs the agent skills");
            SpiderSenseArguments options = configureJarRun(task, jar, systemProperties, configuration.getName(), url);
            // Mapped from a fixed provider so the jar is resolved when the task runs, not now.
            Provider<List<String>> initArguments = objects.property(Boolean.class).value(true).map(ignored -> List.of(
                    "init",
                    "--dir=" + projectDir.getAbsolutePath(),
                    "--jar=" + options.singleJar().getAbsolutePath()));
            task.getArgumentProviders().add(
                    SpiderSenseArguments.programArguments(objects.fileCollection(), initArguments));
        });

        // check is the same run with the block's rules as its command line, and
        // the exit code read rather than thrown: the CLI has already printed the
        // verdict by then, and what the build adds is one line saying which of
        // the four outcomes it was.
        project.getTasks().register(NAME + "Check", JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Runs the Spider Sense check and fails the build when the verdict is fail");
            configureJarRun(task, jar, systemProperties, configuration.getName(), url);
            task.getArgumentProviders().add(
                    SpiderSenseArguments.programArguments(objects.fileCollection(), checkArguments));
            task.setIgnoreExitValue(true);
            task.doLast(new FailOnVerdict(extension.getCheck().getFailOnNoRequests()));
        });
    }

    /** The setup the three tasks share: the jar as the class path, the launcher, the block's options, and the URL. */
    private static SpiderSenseArguments configureJarRun(JavaExec task, FileCollection jar,
            Provider<List<String>> systemProperties, String configuration, Provider<String> url) {
        SpiderSenseArguments options = SpiderSenseArguments.jvmOptions(jar, systemProperties, configuration);
        task.setClasspath(jar);
        task.getMainClass().set(MAIN_CLASS);
        task.getJvmArgumentProviders().add(options);
        task.doFirst(new SetSenseUrl(url));
        return options;
    }

    /**
     * The address a client calls for a bind address: loopback for a wildcard, which
     * is not an address to call, and an IPv6 address in the brackets a URL needs.
     */
    static String callableHost(String host) {
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            return "127.0.0.1";
        }
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /**
     * The base URL the block implies, so a CLI command asks the Spider Sense the
     * application is sending to rather than the default port. A host of
     * {@code 0.0.0.0} is an instruction to bind every interface, not an address
     * to call back on.
     *
     * <p>Absent when the block names neither a collector, a host nor a port:
     * the CLI's own default is then what the {@code spidersense.*} properties
     * the task gets imply, which includes a {@code configFile}, and a URL made
     * of the defaults here would override the file's port.
     */
    static Provider<String> baseUrl(SpiderSenseExtension extension) {
        Provider<String> hostAndPort = extension.getHost().orElse("127.0.0.1").zip(extension.getPort().orElse(4000),
                (host, port) -> "http://" + callableHost(host) + ":" + port);
        Provider<String> whenNamed = extension.getHost().map(host -> true)
                .orElse(extension.getPort().map(port -> true))
                .flatMap(named -> hostAndPort);
        return extension.getCollector().orElse(whenNamed);
    }

    /**
     * One {@code -Dspidersense.<key>=<value>} per property of the block that has
     * a value, in the order the documentation's table lists them. A property
     * left unset contributes nothing, which is how the jar's own default stays
     * the default.
     */
    static Provider<List<String>> systemProperties(ObjectFactory objects, SpiderSenseExtension extension) {
        ListProperty<String> arguments = objects.listProperty(String.class);
        arguments.addAll(option("config", extension.getConfigFile().map(file -> file.getAsFile().getAbsolutePath())));
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
        arguments.addAll(option("retention.spans", extension.getRetentionSpans()));
        arguments.addAll(option("ingest.max-spans-per-second", extension.getMaxSpansPerSecond()));
        arguments.addAll(extension.getSourceDirs().map(dirs -> dirs.isEmpty()
                ? List.<String>of()
                : List.of("-Dspidersense.source.dirs=" + String.join(",", dirs))));
        return arguments;
    }

    private static Provider<List<String>> option(String key, Provider<?> value) {
        return value.map(v -> List.of("-Dspidersense." + key + "=" + v)).orElse(List.of());
    }

    /**
     * The command line of {@code spiderSenseCheck}: {@code check}, the window
     * and the scope, then one argument per rule the block set, in the order of
     * {@link SpiderSenseCheckExtension#rules()}, which is the documentation's
     * table. A rule left unset says nothing, which is how the CLI's own default
     * set stays the default.
     *
     * <p>{@code -PspiderSense.check.since} wins over the block, so one run can
     * judge a different window — a mark, say — without the build file knowing.
     */
    static Provider<List<String>> checkArguments(ObjectFactory objects, ProviderFactory providers,
            SpiderSenseCheckExtension check) {
        ListProperty<String> arguments = objects.listProperty(String.class);
        arguments.add("check");
        arguments.addAll(flag("since", providers.gradleProperty(CHECK_SINCE_PROPERTY).orElse(check.getSince())));
        arguments.addAll(flag("until", check.getUntil()));
        arguments.addAll(flag("service", check.getService()));
        arguments.addAll(flag("endpoint", check.getEndpoint()));
        check.rules().forEach((name, rule) -> arguments.addAll(flag(name, rule)));
        return arguments;
    }

    private static Provider<List<String>> flag(String name, Provider<?> value) {
        return value.map(v -> List.of("--" + name + "=" + plainly(v))).orElse(List.of());
    }

    /**
     * A value as the CLI reads it: a decimal point and no grouping, whatever the
     * build's locale, and no exponent for a small threshold. {@code toString}
     * would do for a {@code Long}; a {@code Double} needs saying.
     */
    static String plainly(Object value) {
        if (value instanceof Double number) {
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
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
     * <p>The three Spider Sense tasks always want the jar ({@link #always});
     * for a task that may attach it ({@link #whenAttached}), an unattached task
     * gets nothing here, and so nothing is resolved and nothing is built for it.
     */
    static final class JarSource implements Callable<Object> {

        /** Null for always. */
        private final @Nullable Provider<Boolean> attached;
        private final Provider<File> namedJar;
        private final Configuration configuration;

        private JarSource(@Nullable Provider<Boolean> attached, Provider<File> namedJar,
                Configuration configuration) {
            this.attached = attached;
            this.namedJar = namedJar;
            this.configuration = configuration;
        }

        /** The jar, for a task that runs it. */
        static JarSource always(Provider<File> namedJar, Configuration configuration) {
            return new JarSource(null, namedJar, configuration);
        }

        /** The jar when {@code attached} holds, else nothing, for a task that attaches it. */
        static JarSource whenAttached(Provider<Boolean> attached, Provider<File> namedJar,
                Configuration configuration) {
            return new JarSource(attached, namedJar, configuration);
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
     * Sets {@code SPIDERSENSE_URL} on the forked JVM when the task runs, when
     * the block implies one ({@link #baseUrl}). A named action rather than a
     * lambda: the configuration cache stores task actions, and it can store
     * this one.
     */
    static final class SetSenseUrl implements Action<Task> {

        private final Provider<String> url;

        SetSenseUrl(Provider<String> url) {
            this.url = url;
        }

        @Override
        public void execute(Task task) {
            if (url.isPresent()) {
                ((JavaExec) task).environment("SPIDERSENSE_URL", url.get());
            }
        }
    }

    /**
     * Turns the CLI's exit code into the build's verdict, after the CLI has
     * printed its own rendering: {@link #CHECK_FAILED} is a failed check,
     * {@link #NO_REQUESTS} a window with no request in it, which is not a pass
     * unless the build says it is, and anything else is the check not having run
     * at all — no Spider Sense at the URL, or a scope that is not there.
     *
     * <p>A named action holding one provider and reading the result off the task
     * it is given: no {@code Project} is captured, so the configuration cache
     * can store it.
     */
    static final class FailOnVerdict implements Action<Task> {

        // The CLI's exit codes (check.adoc#exit-codes), which the plugin cannot
        // take from the CLI's own constants: the jar is a runtime input, not a
        // dependency.
        /** {@code check} passed. */
        static final int PASSED = 0;
        /** At least one rule was over its limit. */
        static final int CHECK_FAILED = 1;
        /** There was no request to judge. */
        static final int NO_REQUESTS = 3;

        private final Provider<Boolean> failOnNoRequests;

        FailOnVerdict(Provider<Boolean> failOnNoRequests) {
            this.failOnNoRequests = failOnNoRequests;
        }

        @Override
        public void execute(Task task) {
            int exit = ((JavaExec) task).getExecutionResult().get().getExitValue();
            String failure = failure(exit, failOnNoRequests.get());
            if (failure != null) {
                throw new GradleException(failure);
            }
        }

        /** Why the build fails for this exit code, or null when it does not. */
        static @Nullable String failure(int exit, boolean failOnNoRequests) {
            return switch (exit) {
                case PASSED -> null;
                case CHECK_FAILED -> "Spider Sense check failed";
                case NO_REQUESTS -> failOnNoRequests ? "Spider Sense check had no request to judge" : null;
                default -> "Spider Sense check could not run (exit " + exit + ")";
            };
        }
    }
}
