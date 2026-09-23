package net.benelog.spidersense.extension;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The extension's second instrumentation module, {@code spider-sense-spider-silk}: the route of a
 * Spider Silk request ({@code docs/design.md}, "The extension").
 *
 * <p>A Spider Silk application is one servlet mapped to {@code /*}, so the servlet instrumentation
 * can only report that mapping as the request's {@code http.route}, and every request of the
 * application is one endpoint. Spring MVC has an instrumentation of its own that reports the
 * handler's pattern instead; Spider Silk has none in the agent, and this module is it.
 *
 * <p>It applies only where Spider Silk is on the class path, and declares no muzzle references: it
 * calls nothing but {@code Route.path()}, which every Spider Silk release has.
 */
public final class SpiderSilkInstrumentationModule extends InstrumentationModule {

    public SpiderSilkInstrumentationModule() {
        super("spider-sense-spider-silk");
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(SpiderSilkRouteInstrumentation.WEB_REQUEST);
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new SpiderSilkRouteInstrumentation());
    }
}
