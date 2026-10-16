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
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.JavaForkOptions;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.inject.Inject;
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
    public static final String GROUP = "Spider Sense";

    /**
     * The jar's {@code Main-Class}, as {@code java -jar} runs it: the version gate, compiled for
     * Java 8, which hands over to the standalone server and the CLI on Java 25 and answers an
     * older {@code javaLauncher} with one line and exit code 2.
     */
    public static final String MAIN_CLASS = "net.benelog.spidersense.boot.VersionGate";

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

    /** The oldest Java the agent instruments an application on, forwarding (gradle-plugin.adoc#java-versions). */
    static final int AGENT_JAVA = 17;

    /** The oldest Java the jar's server runs on: the embedded UI, the standalone and the CLI. */
    static final int SERVER_JAVA = 25;

    /** A task's Java when its launcher does not say, which is then the JVM that runs Gradle. */
    static final int UNKNOWN_JAVA = 0;

    private final JavaToolchainService toolchains;

    /** @param toolchains where the three tasks find a Java the jar runs on */
    @Inject
    public SpiderSensePlugin(JavaToolchainService toolchains) {
        this.toolchains = toolchains;
    }

    @Override
    public void apply(Project project) {
        ObjectFactory objects = project.getObjects();
        ProviderFactory providers = project.getProviders();
        File projectDir = project.getLayout().getProjectDirectory().getAsFile();

        SpiderSenseExtension extension = project.getExtensions().create(NAME, SpiderSenseExtension.class);
        extension.getEnabled().convention(true);
        extension.getVersion().convention(pluginVersion());
        // A value, not a convention: Gradle's add() builds on the explicit value
        // and drops a convention, so `attachTo.add('test')`, the line the manual
        // gives, would otherwise detach bootRun and run. Assigning a list still
        // replaces the defaults.
        extension.getAttachTo().set(DEFAULT_ATTACH_TO);
        extension.getService().convention(project.getName());
        // The tests are another service, so a 4xx a test provokes on purpose is
        // not the application's, the way the Maven page's Surefire line has it.
        extension.getTestService().convention(extension.getService().map(service -> service + "-test"));
        // A list property is present and empty until something sets it, which would
        // make "ignore nothing" indistinguishable from "say nothing". A convention of
        // null is Gradle's way of saying "no value at all", so here emptiness can mean
        // what `ignoreEndpoints = []` and `sourceDirs = []` say they mean.
        extension.getIgnoreEndpoints().convention((Iterable<String>) null);
        extension.getSourceDirs().convention((Iterable<String>) null);
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
        // need no resolution; only the third reaches a repository. A blank
        // property is no path at all, not the project directory.
        NamedJar namedJar = new NamedJar(
                providers.gradleProperty(JAR_PROPERTY)
                        .filter(path -> !path.isBlank())
                        .map(path -> projectDir.toPath().resolve(path).normalize().toFile()),
                extension.getJar().map(file -> file.getAsFile()));

        Provider<List<String>> systemProperties = systemProperties(objects, extension, extension.getService(),
                extension.getPort());
        Provider<List<String>> testSystemProperties =
                systemProperties(objects, extension, extension.getTestService(), extension.getPort());
        Provider<List<String>> checkArguments = checkArguments(objects, providers, extension.getCheck());

        attach(project, objects, extension, enabled, namedJar, configuration, systemProperties,
                testSystemProperties);
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
     *
     * <p>A {@link Test} task reports as {@code testService}, every other task as
     * {@code service}.
     *
     * <p>The Java the task runs the application on decides too, read from its
     * launcher when it runs: below {@value #AGENT_JAVA} nothing is attached, and
     * below {@value #SERVER_JAVA} no UI starts in the application's JVM. The build
     * says so in one warning, unless the block forwards (gradle-plugin.adoc#java-versions).
     */
    private void attach(Project project, ObjectFactory objects, SpiderSenseExtension extension,
            Provider<Boolean> enabled, NamedJar namedJar, Configuration configuration,
            Provider<List<String>> systemProperties, Provider<List<String>> testSystemProperties) {
        project.getTasks().withType(JavaExec.class).configureEach(
                attachOne(objects, extension, enabled, namedJar, configuration, systemProperties));
        project.getTasks().withType(Test.class).configureEach(
                attachOne(objects, extension, enabled, namedJar, configuration, testSystemProperties));
    }

    private static Action<Task> attachOne(ObjectFactory objects, SpiderSenseExtension extension,
            Provider<Boolean> enabled, NamedJar namedJar, Configuration configuration,
            Provider<List<String>> systemProperties) {
        return task -> {
            String name = task.getName();
            Provider<Boolean> named = enabled.zip(extension.getAttachTo(),
                    (on, names) -> on && names.contains(name));
            // The task's Java is read only when the block names the task, so no other
            // task's toolchain is looked up for it.
            Provider<Integer> java = javaOf(task);
            Provider<Boolean> forwarding = extension.getCollector().map(collector -> true).orElse(false);
            Provider<Boolean> no = objects.property(Boolean.class).value(false);
            Provider<String> nothing = objects.property(String.class);
            Provider<Boolean> attached = named.flatMap(on -> on ? java.map(SpiderSensePlugin::canAttach) : no);
            Provider<String> warning = named.flatMap(on -> on
                    ? java.zip(forwarding, (version, forwards) -> launcherWarning(name, version, forwards))
                    : nothing);
            FileCollection jar = objects.fileCollection()
                    .from(JarSource.whenAttached(attached, namedJar, configuration));
            ((JavaForkOptions) task).getJvmArgumentProviders().add(SpiderSenseArguments.javaagent(
                    jar, systemProperties, attached, warning, configuration.getName()));
        };
    }

    /**
     * The feature release of the Java a {@link JavaExec} or {@link Test} task runs on,
     * from its launcher: the project's toolchain, or the one the build gives the task.
     * {@link #UNKNOWN_JAVA} when it has none, such as a task with an executable of its
     * own or a project without the {@code java} plugin.
     */
    static Provider<Integer> javaOf(Task task) {
        Provider<JavaLauncher> launcher = task instanceof JavaExec exec
                ? exec.getJavaLauncher()
                : ((Test) task).getJavaLauncher();
        return launcher.map(it -> it.getMetadata().getLanguageVersion().asInt()).orElse(UNKNOWN_JAVA);
    }

    /** Whether the agent is attached to a task on this Java: from {@value #AGENT_JAVA}, or when it is not known. */
    static boolean canAttach(int java) {
        return java == UNKNOWN_JAVA || java >= AGENT_JAVA;
    }

    /**
     * What the build says of a task the block names on a Java the jar cannot fully
     * serve, or null when there is nothing to say: below {@value #AGENT_JAVA}
     * nothing is attached, and below {@value #SERVER_JAVA} with no {@code collector}
     * the agent starts no UI and exports to nothing unless a Spider Sense already
     * listens at the block's port.
     */
    static @Nullable String launcherWarning(String task, int java, boolean forwarding) {
        if (java == UNKNOWN_JAVA || java >= SERVER_JAVA) {
            return null;
        }
        if (java < AGENT_JAVA) {
            return "Spider Sense is not attached to " + task + ": it runs on Java " + java
                    + ", and Spider Sense needs Java " + AGENT_JAVA + " or later.";
        }
        return forwarding ? null
                : "Spider Sense starts no UI in " + task + ": it runs on Java " + java + ", and the embedded UI needs Java "
                        + SERVER_JAVA + " or later. Start one on Java " + SERVER_JAVA
                        + " with ./gradlew spiderSense, and set collector in the spiderSense block to forward to it.";
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
            NamedJar namedJar, Configuration configuration, Provider<List<String>> systemProperties,
            Provider<List<String>> checkArguments, File projectDir) {
        FileCollection jar = objects.fileCollection().from(JarSource.always(namedJar, configuration));
        Provider<JavaLauncher> launcher = jarLauncher(toolchains);
        Provider<String> url = baseUrl(extension);
        // The standalone listens where the block forwards to, when that is here.
        Provider<List<String>> standaloneProperties = systemProperties(objects, extension, extension.getService(),
                standalonePort(extension));

        project.getTasks().register(NAME, JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Runs Spider Sense standalone, or a CLI command given with --args");
            configureJarRun(task, jar, launcher, standaloneProperties, configuration.getName(), url);
        });

        // init writes the CLAUDE.md block and installs the skills, and the
        // directory it writes into, the jar path it writes down, that the
        // application starts through Gradle, and where its Spider Sense listens
        // are what this build knows and the CLI does not.
        project.getTasks().register(NAME + "Init", JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Writes the Spider Sense block into CLAUDE.md and installs the agent skills");
            SpiderSenseArguments options =
                    configureJarRun(task, jar, launcher, systemProperties, configuration.getName(), url);
            ListProperty<String> initArguments = objects.listProperty(String.class);
            // Mapped from a fixed provider so the jar is resolved when the task runs, not now.
            initArguments.addAll(objects.property(Boolean.class).value(true).map(ignored -> List.of(
                    "init",
                    "--dir=" + projectDir.getAbsolutePath(),
                    "--jar=" + options.singleJar().getAbsolutePath(),
                    "--gradle")));
            initArguments.addAll(url.map(base -> List.of("--url=" + base)).orElse(List.of()));
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
            configureJarRun(task, jar, launcher, systemProperties, configuration.getName(), url);
            task.getArgumentProviders().add(
                    SpiderSenseArguments.programArguments(objects.fileCollection(), checkArguments));
            task.setIgnoreExitValue(true);
            task.doFirst(new WatchVerdict());
            task.doLast(new FailOnVerdict(extension.getCheck().getFailOnNoRequests()));
        });
    }

    /**
     * The setup the three tasks share: the jar as the class path, the jar's main
     * class, a Java the jar runs on, the block's options, and the URL.
     */
    private static SpiderSenseArguments configureJarRun(JavaExec task, FileCollection jar,
            Provider<JavaLauncher> launcher, Provider<List<String>> systemProperties, String configuration,
            Provider<String> url) {
        SpiderSenseArguments options = SpiderSenseArguments.jvmOptions(jar, systemProperties, configuration);
        task.setClasspath(jar);
        task.getMainClass().set(MAIN_CLASS);
        task.getJavaLauncher().set(launcher);
        task.getJvmArgumentProviders().add(options);
        task.doFirst(new SetSenseUrl(url));
        return options;
    }

    /**
     * The Java the three tasks run the jar on: they run no code of the project, and the
     * jar's server and CLI need Java {@value #SERVER_JAVA} whatever the project's
     * toolchain is. That is the JVM that runs Gradle when it is new enough, which needs
     * no lookup, and a Java {@value #SERVER_JAVA} toolchain otherwise. A build that wants
     * another sets the task's {@code javaLauncher} itself.
     */
    static Provider<JavaLauncher> jarLauncher(JavaToolchainService toolchains) {
        return toolchains.launcherFor(spec -> { })
                .filter(gradles -> gradles.getMetadata().getLanguageVersion().canCompileOrRun(SERVER_JAVA))
                .orElse(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(SERVER_JAVA))));
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
     * The port the {@code spiderSense} task's standalone listens on: the block's
     * {@code port}, or, when the block sets none and forwards to a {@code collector}
     * on this machine, that collector's port, so {@code ./gradlew spiderSense}
     * starts the Spider Sense the block forwards to.
     */
    static Provider<Integer> standalonePort(SpiderSenseExtension extension) {
        return extension.getPort().orElse(extension.getCollector().map(SpiderSensePlugin::loopbackPort));
    }

    /**
     * The explicit port of a collector URL on a loopback host, or null for any other URL. A
     * collector without a scheme is {@code http://}, as the jar reads it.
     */
    static @Nullable Integer loopbackPort(String collector) {
        String trimmed = collector.trim();
        URI uri;
        try {
            uri = new URI(trimmed.contains("://") ? trimmed : "http://" + trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || uri.getPort() < 0) {
            return null;
        }
        boolean loopback = host.equalsIgnoreCase("localhost") || host.startsWith("127.")
                || host.equals("[::1]") || host.equals("::1");
        return loopback ? uri.getPort() : null;
    }

    /**
     * One {@code -Dspidersense.<key>=<value>} per property of the block that has
     * a value, in the order the documentation's table lists them. A property
     * left unset contributes nothing, which is how the jar's own default stays
     * the default.
     *
     * @param service the block's {@code service}, or its {@code testService} for a test task
     * @param port    the block's {@code port}, or {@link #standalonePort} for the {@code spiderSense} task
     */
    static Provider<List<String>> systemProperties(ObjectFactory objects, SpiderSenseExtension extension,
            Provider<String> service, Provider<Integer> port) {
        ListProperty<String> arguments = objects.listProperty(String.class);
        arguments.addAll(option("config", extension.getConfigFile().map(file -> file.getAsFile().getAbsolutePath())));
        arguments.addAll(option("service", service));
        arguments.addAll(option("port", port));
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
        arguments.addAll(listOption("ignore.endpoints", extension.getIgnoreEndpoints()));
        arguments.addAll(option("retention.spans", extension.getRetentionSpans()));
        arguments.addAll(option("ingest.max-spans-per-second", extension.getMaxSpansPerSecond()));
        // The same for `sourceDirs = []`, which names no root and turns source lines off.
        arguments.addAll(listOption("source.dirs", extension.getSourceDirs()));
        return arguments;
    }

    private static Provider<List<String>> option(String key, Provider<?> value) {
        return value.map(v -> List.of("-Dspidersense." + key + "=" + v)).orElse(List.of());
    }

    /** A list joined with commas when it has a value, an empty one included. */
    private static Provider<List<String>> listOption(String key, Provider<List<String>> values) {
        return values.map(list -> List.of("-Dspidersense." + key + "=" + String.join(",", list))).orElse(List.of());
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
        private final NamedJar namedJar;
        private final Configuration configuration;

        private JarSource(@Nullable Provider<Boolean> attached, NamedJar namedJar,
                Configuration configuration) {
            this.attached = attached;
            this.namedJar = namedJar;
            this.configuration = configuration;
        }

        /** The jar, for a task that runs it. */
        static JarSource always(NamedJar namedJar, Configuration configuration) {
            return new JarSource(null, namedJar, configuration);
        }

        /** The jar when {@code attached} holds, else nothing, for a task that attaches it. */
        static JarSource whenAttached(Provider<Boolean> attached, NamedJar namedJar,
                Configuration configuration) {
            return new JarSource(attached, namedJar, configuration);
        }

        @Override
        public Object call() {
            if (attached != null && !attached.get()) {
                return List.of();
            }
            File named = namedJar.get();
            return named != null ? named : configuration;
        }
    }

    /**
     * The jar a build names by path rather than resolves: the project property
     * {@code spiderSense.jar}, which wins, and the block's {@code jar}. Two
     * providers rather than one, so that a path that is not a file is reported
     * with where it was written.
     */
    static final class NamedJar {

        private final Provider<File> fromProperty;
        private final Provider<File> fromBlock;

        NamedJar(Provider<File> fromProperty, Provider<File> fromBlock) {
            this.fromProperty = fromProperty;
            this.fromBlock = fromBlock;
        }

        /**
         * The named jar, or null when the build names none and the configuration
         * decides. A path that is not a regular file fails here, before a JVM is
         * forked with a {@code -javaagent:} or a class path it cannot open.
         */
        @Nullable File get() {
            File file = fromProperty.getOrNull();
            String origin = "-P" + JAR_PROPERTY;
            if (file == null) {
                file = fromBlock.getOrNull();
                origin = "jar in the " + NAME + " block";
            }
            if (file != null && !file.isFile()) {
                throw new GradleException(notAFile(file, origin));
            }
            return file;
        }

        /** What the build says of a named jar that is not there. */
        static String notAFile(File file, String origin) {
            return "The Spider Sense jar " + file.getAbsolutePath()
                    + (file.exists() ? " is not a file" : " does not exist") + " (from " + origin + ").";
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
     * Passes the check's standard output through a {@link VerdictWatch} as the
     * task starts, so {@link FailOnVerdict} can tell a failed check from a JVM that
     * failed. Set when the task runs, because a stream is nothing the
     * configuration cache can store, and this action holds nothing.
     */
    static final class WatchVerdict implements Action<Task> {

        @Override
        public void execute(Task task) {
            JavaExec exec = (JavaExec) task;
            // Unset is where Gradle forwards a process's output itself: this JVM's System.out,
            // which the daemon hands to the build's log.
            OutputStream current = exec.getStandardOutput();
            exec.setStandardOutput(new VerdictWatch(current != null ? current : System.out));
        }
    }

    /**
     * The check task's standard output, passed on unchanged, noting whether a line
     * starts with the heading of a failing verdict, {@code # check  fail}
     * (check.adoc#text-rendering). Exit code {@code 1} is also what a JVM gives for
     * a main class it cannot load or an error nothing caught, and only the heading
     * tells the two apart.
     */
    static final class VerdictWatch extends OutputStream {

        private static final byte[] HEADING = "# check  fail".getBytes(StandardCharsets.UTF_8);

        private final OutputStream out;
        /** How much of the heading the current line has matched; -1 once it cannot match. */
        private int matched;
        private volatile boolean failingVerdict;

        VerdictWatch(OutputStream out) {
            this.out = out;
        }

        /** Whether the CLI printed the heading of a failing verdict. */
        boolean sawFailingVerdict() {
            return failingVerdict;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            see((byte) b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) {
                see(bytes[i]);
            }
        }

        private void see(byte b) {
            if (b == '\n') {
                matched = 0;
            } else if (matched >= 0 && matched < HEADING.length) {
                matched = b == HEADING[matched] ? matched + 1 : -1;
                if (matched == HEADING.length) {
                    failingVerdict = true;
                }
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        /** Flushes and no more: the stream it writes to is the build's, or {@code System.out}. */
        @Override
        public void close() throws IOException {
            out.flush();
        }
    }

    /**
     * Turns the CLI's exit code into the build's verdict, after the CLI has
     * printed its own rendering: {@link #CHECK_FAILED} after the heading of a
     * failing verdict is a failed check, {@link #NO_REQUESTS} a window with no
     * request in it, which is not a pass unless the build says it is, and anything
     * else is the check not having run at all — no Spider Sense at the URL, a
     * scope that is not there, or a JVM that failed before the CLI could judge.
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
            JavaExec exec = (JavaExec) task;
            int exit = exec.getExecutionResult().get().getExitValue();
            boolean failingVerdict = exec.getStandardOutput() instanceof VerdictWatch watch
                    && watch.sawFailingVerdict();
            String failure = failure(exit, failingVerdict, failOnNoRequests.get());
            if (failure != null) {
                throw new GradleException(failure);
            }
        }

        /**
         * Why the build fails for this exit code, or null when it does not.
         *
         * @param failingVerdict whether the CLI printed the heading of a failing verdict
         */
        static @Nullable String failure(int exit, boolean failingVerdict, boolean failOnNoRequests) {
            return switch (exit) {
                case PASSED -> null;
                case CHECK_FAILED -> failingVerdict ? "Spider Sense check failed"
                        : "Spider Sense check could not run (exit " + exit + ")";
                case NO_REQUESTS -> failOnNoRequests ? "Spider Sense check had no request to judge" : null;
                default -> "Spider Sense check could not run (exit " + exit + ")";
            };
        }
    }
}
