package net.benelog.spidersense.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Nested;

/**
 * The {@code spiderSense} block: what the application is run under and how the
 * embedded Spider Sense is configured.
 *
 * <p>Every property is lazy and unset by default, and unset means "leave the
 * jar's own default": the plugin passes a {@code -Dspidersense.*} option only
 * for a property the build actually set, so the defaults live in one place, the
 * jar, rather than being copied into this class where they would drift.
 * The exceptions are the four that cannot come from the jar because they are
 * about the build: {@code enabled}, {@code version}, {@code attachTo}, and
 * {@code service}, which is the project's name.
 *
 * <p>The block sets only {@code spidersense.*} properties. Anything the
 * OpenTelemetry agent takes as {@code otel.*} goes on the task as an ordinary
 * {@code jvmArgs}, and the two combine on the forked JVM.
 */
public abstract class SpiderSenseExtension {

    /**
     * Whether anything is attached at all; {@code false} is the same as not
     * applying the plugin, except that the block and the tasks are still there.
     * The project property {@code spiderSense.enabled} wins over this, so a run
     * can go bare without editing the build file.
     */
    public abstract Property<Boolean> getEnabled();

    /** The version of {@code net.benelog.spidersense:spider-sense} the configuration's default dependency names. */
    public abstract Property<String> getVersion();

    /**
     * A jar to attach instead of resolving one. The project property
     * {@code spiderSense.jar} wins over this.
     */
    public abstract RegularFileProperty getJar();

    /** The names of the {@link org.gradle.api.tasks.JavaExec} tasks that get the agent. */
    public abstract SetProperty<String> getAttachTo();

    /** {@code -Dspidersense.service}, which is {@code otel.service.name} unless that is set already. */
    public abstract Property<String> getService();

    /** {@code -Dspidersense.port}: where the embedded UI and OTLP receiver listen. */
    public abstract Property<Integer> getPort();

    /** {@code -Dspidersense.host}: the interface the embedded server binds. */
    public abstract Property<String> getHost();

    /** {@code -Dspidersense.collector}: forward to that Spider Sense instead of embedding one. */
    public abstract Property<String> getCollector();

    /** {@code -Dspidersense.db}: the H2 database file. */
    public abstract Property<String> getDb();

    /** {@code -Dspidersense.retention.hours}: how long a span is kept. */
    public abstract Property<Integer> getRetentionHours();

    /** {@code -Dspidersense.slow.request.ms}: the threshold a request is slow past. */
    public abstract Property<Long> getSlowRequestMs();

    /** {@code -Dspidersense.slow.query.ms}: the threshold a query is slow past. */
    public abstract Property<Long> getSlowQueryMs();

    /** {@code -Dspidersense.open}: open the browser on the UI at startup. */
    public abstract Property<Boolean> getOpen();

    /** {@code -Dspidersense.app.packages}, joined with commas: the application's own packages. */
    public abstract ListProperty<String> getAppPackages();

    /**
     * {@code -Dspidersense.ignore.endpoints}, joined with commas: the endpoints
     * that are not requests. Unset leaves the jar's own list
     * ({@code /actuator/**}, {@code /health}, {@code /healthz}, {@code /livez},
     * {@code /readyz}); an empty list set explicitly passes an empty value,
     * which ignores nothing.
     */
    public abstract ListProperty<String> getIgnoreEndpoints();

    /** {@code -Dspidersense.retention.spans}: how many spans the database keeps. */
    public abstract Property<Long> getRetentionSpans();

    /** {@code -Dspidersense.ingest.max-spans-per-second}: the ceiling on spans accepted per second. */
    public abstract Property<Long> getMaxSpansPerSecond();

    /**
     * The {@code check { }} block: the rules of the {@code spiderSenseCheck}
     * task. It is a nested block rather than properties of this one because
     * they are not {@code -Dspidersense.*} options of the application, they are
     * the arguments of one task.
     */
    @Nested
    public abstract SpiderSenseCheckExtension getCheck();

    /** {@code check { }} in the build file, in both the Groovy and the Kotlin DSL. */
    public void check(Action<? super SpiderSenseCheckExtension> action) {
        action.execute(getCheck());
    }
}
