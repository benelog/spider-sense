package net.benelog.spidersense.boot;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Premain-Class}, {@code Agent-Class} and {@code Main-Class} of the distributable jar,
 * and the one class of it compiled for Java 8 (design.adoc#java-versions).
 *
 * <p>A JVM loads the class {@code -javaagent} names before any code of ours runs, and a class it
 * cannot read aborts it before {@code main}, which no {@code try} can catch. So this class is
 * written for the oldest JVM the OpenTelemetry agent supports: it reads the JVM's version and
 * hands over only to code that JVM can load, {@code SpiderSenseAgent} from Java
 * {@value #AGENT_FLOOR} and {@code SpiderSenseMain} from Java {@value #SERVER_FLOOR}. Below that
 * it prints one line, and under {@code -javaagent} the application starts without Spider Sense.
 *
 * <p>The hand-over is reflective, so that nothing here names a class this JVM might not read.
 */
public final class VersionGate {

    /** The oldest Java the launcher and the extension run on: agent mode forwards from here. */
    static final int AGENT_FLOOR = 17;

    /** The oldest Java the nested server runs on: the embedded UI, standalone, and the CLI. */
    static final int SERVER_FLOOR = 25;

    static final String AGENT_CLASS = "net.benelog.spidersense.launcher.SpiderSenseAgent";
    static final String MAIN_CLASS = "net.benelog.spidersense.launcher.SpiderSenseMain";

    private static final String LOG_PREFIX = "[spider-sense] ";

    private VersionGate() {
    }

    public static void premain(@Nullable String agentArgs, Instrumentation inst) {
        attach(System.getProperty("java.specification.version"), javaVersion(), System.err,
                () -> handOver("premain", agentArgs, inst));
    }

    public static void agentmain(@Nullable String agentArgs, Instrumentation inst) {
        attach(System.getProperty("java.specification.version"), javaVersion(), System.err,
                () -> handOver("agentmain", agentArgs, inst));
    }

    /**
     * {@code java -jar spider-sense.jar}: the standalone server or a CLI command, both of which
     * run the nested server, so below Java {@value #SERVER_FLOOR} it is one line and exit code
     * {@code 2}, as a command that could not run.
     */
    public static void main(String[] args) throws Throwable {
        if (feature(System.getProperty("java.specification.version")) < SERVER_FLOOR) {
            System.err.println("spider-sense: " + belowServerFloor(javaVersion(), System.getProperty("java.home")));
            System.exit(2);
            return;
        }
        Method main = Class.forName(MAIN_CLASS).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            // The launcher's own failure, as the JVM would print it had it called the launcher itself.
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    /**
     * What {@code premain} and {@code agentmain} do, with the JVM's version, the stream and the
     * hand-over given, for a test: below Java {@value #AGENT_FLOOR} one line on {@code err} and
     * nothing else, and from there the hand-over. Nothing escapes, because anything that escaped
     * {@code premain} would abort the JVM, the application with it.
     *
     * @param specification {@code java.specification.version}: {@code 1.8} for Java 8, then
     *                      {@code 9}, {@code 11}, {@code 17}
     * @param javaVersion   {@code java.version}, which the line names
     */
    static void attach(@Nullable String specification, String javaVersion, PrintStream err, Callable<?> handover) {
        try {
            if (feature(specification) < AGENT_FLOOR) {
                err.println(LOG_PREFIX + belowAgentFloor(javaVersion));
                return;
            }
            handover.call();
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            err.println(LOG_PREFIX + "did not start, and the application runs without it: " + cause);
        }
    }

    private static @Nullable Object handOver(String phase, @Nullable String agentArgs, Instrumentation inst)
            throws ReflectiveOperationException {
        return Class.forName(AGENT_CLASS).getMethod(phase, String.class, Instrumentation.class)
                .invoke(null, agentArgs, inst);
    }

    /** The line a JVM below the agent's floor gets, under {@code -javaagent}. */
    static String belowAgentFloor(String javaVersion) {
        return "this JVM is Java " + javaVersion + ", and Spider Sense needs " + AGENT_FLOOR
                + " or later; the application runs without it";
    }

    /** The line a JVM below the server's floor gets from {@code java -jar}. */
    static String belowServerFloor(String javaVersion, @Nullable String javaHome) {
        return "the standalone server and the command line need Java " + SERVER_FLOOR + " or later, and this JVM is Java "
                + javaVersion + (javaHome == null ? "" : " (" + javaHome + ")");
    }

    /**
     * The feature release of a {@code java.specification.version}: {@code 8} for {@code 1.8},
     * {@code 21} for {@code 21}. One that does not read as a number is taken as new enough, so
     * that a JVM this cannot read still gets the hand-over, whose failure is caught.
     */
    static int feature(@Nullable String specification) {
        if (specification == null) {
            return Integer.MAX_VALUE;
        }
        String version = specification.startsWith("1.") ? specification.substring(2) : specification;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 9) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(version.substring(0, end));
    }

    private static String javaVersion() {
        String version = System.getProperty("java.version");
        return version == null ? "unknown" : version;
    }
}
