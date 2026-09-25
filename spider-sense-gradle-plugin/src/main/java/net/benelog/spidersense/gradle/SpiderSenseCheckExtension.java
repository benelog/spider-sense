package net.benelog.spidersense.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * The {@code check { }} block inside {@code spiderSense { }}: the rules the
 * {@code spiderSenseCheck} task judges the window by.
 *
 * <p>Each rule becomes one {@code --max-…} or {@code --min-…} argument of the
 * CLI's {@code check}, and a rule left unset contributes nothing, so the CLI's
 * own default set ({@code maxErrors=0}, {@code maxNPlusOne=0},
 * {@code maxRegressions=0}, {@code maxP95Ms=<slow.request.ms>}) applies to a
 * block that names no rule at all. The window and the scope are the other three: {@code since}, which
 * defaults to {@code start} and which the project property
 * {@code spiderSense.check.since} overrides for one run, {@code until}, and
 * {@code service}, which follows the outer block's {@code service}.
 *
 * <p>The specification is {@code gradle-plugin.adoc#check-task}.
 */
public abstract class SpiderSenseCheckExtension {

    // The three fractional rules are held rather than managed, because a
    // managed property cannot have a setter of its own and these need one:
    // `minApdex = 0.9` in the Groovy DSL is a BigDecimal, which a
    // Property<Double> refuses, and a decimal literal is how anyone writes a
    // rate. The setters below take any Number so that 0.9, 0.9d and 1 all work.
    private final Property<Double> maxErrorRate;
    private final Property<Double> maxQueriesPerRequest;
    private final Property<Double> minApdex;

    @Inject
    public SpiderSenseCheckExtension(ObjectFactory objects) {
        this.maxErrorRate = objects.property(Double.class);
        this.maxQueriesPerRequest = objects.property(Double.class);
        this.minApdex = objects.property(Double.class);
    }

    /** {@code --since}: the start of the window, {@code start} unless the build says otherwise. */
    public abstract Property<String> getSince();

    /** {@code --until}: the end of the window; unset means now. */
    public abstract Property<String> getUntil();

    /** {@code --service}: the service to judge, the outer block's {@code service} unless the build says otherwise. */
    public abstract Property<String> getService();

    /** {@code --endpoint}: narrows the scope to one endpoint, by id or by name. */
    public abstract Property<String> getEndpoint();

    /** {@code --max-p95-ms}: the highest p95 of any endpoint in scope. */
    public abstract Property<Long> getMaxP95Ms();

    /** {@code --max-errors}: error groups' occurrences summed. */
    public abstract Property<Long> getMaxErrors();

    /** {@code --max-error-rate}: failed entry spans over entry spans. */
    public Property<Double> getMaxErrorRate() {
        return maxErrorRate;
    }

    /** {@code maxErrorRate = 0.01}: a decimal literal, whatever Number type the DSL made of it. */
    public void setMaxErrorRate(Number value) {
        maxErrorRate.set(value == null ? null : value.doubleValue());
    }

    /** {@code --max-queries-per-request}: database spans per entry span, the highest of any endpoint. */
    public Property<Double> getMaxQueriesPerRequest() {
        return maxQueriesPerRequest;
    }

    /** {@code maxQueriesPerRequest = 5.5}: as {@link #setMaxErrorRate(Number)}. */
    public void setMaxQueriesPerRequest(Number value) {
        maxQueriesPerRequest.set(value == null ? null : value.doubleValue());
    }

    /** {@code --max-slow-queries}: query calls over {@code slow.query.ms}. */
    public abstract Property<Long> getMaxSlowQueries();

    /** {@code --max-n-plus-one}: {@code n-plus-one} findings. */
    public abstract Property<Long> getMaxNPlusOne();

    /** {@code --max-log-errors}: {@code log-error} findings' uncovered records summed. */
    public abstract Property<Long> getMaxLogErrors();

    /** {@code --max-regressions}: {@code regression} findings, resolved findings that came back. */
    public abstract Property<Long> getMaxRegressions();

    /** {@code --min-apdex}: the Apdex over the scope. */
    public Property<Double> getMinApdex() {
        return minApdex;
    }

    /** {@code minApdex = 0.9}: as {@link #setMaxErrorRate(Number)}. */
    public void setMinApdex(Number value) {
        minApdex.set(value == null ? null : value.doubleValue());
    }

    /**
     * Whether exit code {@code 3}, no request in the window, fails the build
     * too. It does by default, because a check that judged nothing is not a
     * pass.
     */
    public abstract Property<Boolean> getFailOnNoRequests();
}
