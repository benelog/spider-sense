package net.benelog.spidersense.extension;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.sql.Statement;
import net.benelog.spidersense.extension.schema.IndexCatalog;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.jspecify.annotations.Nullable;

/**
 * The one type this module instruments: every implementation of {@link java.sql.Statement}, which
 * covers {@link java.sql.PreparedStatement} and {@link java.sql.CallableStatement} with it, and the
 * pooled wrappers a connection pool puts in front of them.
 *
 * <p>Only the single-statement methods are advised. The batch methods are left alone because a
 * batch is one call for many statements and carries no SQL of its own to scan, and because the
 * whole point of a batch is that it is the fix, not the problem.
 */
public final class StatementInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return implementsInterface(named("java.sql.Statement"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                isPublic().and(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")),
                ExecuteAdvice.class.getName());
    }

    /**
     * Inlined into the driver, so it holds nothing but the timing and one call.
     *
     * <p>Advice code is copied into the instrumented method and cannot see the extension's own
     * classes, only the helpers injected beside the driver; every decision therefore belongs in
     * {@link IndexCatalog}, and what is left here has to stay small enough to be obviously harmless
     * in a method the application calls on every query; the helper returns at once for a statement
     * under the threshold.
     */
    @SuppressWarnings("unused")
    public static class ExecuteAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long onEnter() {
            return System.nanoTime();
        }

        /**
         * A statement that threw is not measured: the driver failed, the duration says nothing, and
         * the catalog of a table the statement may not even have reached is not worth a second
         * round trip on a connection that is already in trouble.
         */
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.This Statement statement,
                @Advice.AllArguments Object[] args,
                @Advice.Enter long start,
                @Advice.Thrown @Nullable Throwable thrown) {
            if (thrown == null) {
                String sql = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
                IndexCatalog.afterExecute(statement, sql, System.nanoTime() - start);
            }
        }
    }
}
