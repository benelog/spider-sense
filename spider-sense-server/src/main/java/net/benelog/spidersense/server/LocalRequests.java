package net.benelog.spidersense.server;

import java.util.Locale;

import net.benelog.spidersense.ingest.ErrorBody;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import org.jspecify.annotations.Nullable;

/**
 * Turns away what a web page in the developer's browser sends on its own behalf.
 *
 * <p>The API writes marks and acknowledgements, runs SQL over the store, reads
 * source lines and clears the data, all without credentials, because only this
 * machine can reach it. A page on another site can still reach it through the
 * browser: a cross-origin POST that needs no preflight is sent, and a page whose
 * own name the attacker re-points at 127.0.0.1 (DNS rebinding) is same-origin to
 * the browser. The first carries an {@code Origin} that is not this server's, the
 * second a {@code Host} that is not a name of this machine; either answers 403.
 * A client outside a browser (the CLI, an MCP client, an OpenTelemetry exporter)
 * sends a loopback {@code Host} and no {@code Origin}, so it is never refused.
 */
final class LocalRequests {

    private LocalRequests() {
    }

    /**
     * The before-request check.
     *
     * @param bindHost the address the server binds; a wildcard one means the user
     *                 opened it to the network, and then any {@code Host} is accepted
     */
    static @Nullable WebResponse check(WebRequest req, String bindHost) {
        String host = req.header("Host");
        if (host != null && !acceptedHost(name(host), bindHost)) {
            return forbidden("Host " + host + " is not a name of this machine");
        }
        String origin = req.header("Origin");
        if (origin != null && (host == null || !sameOrigin(origin, host))) {
            return forbidden("Origin " + origin + " is not this Spider Sense");
        }
        return null;
    }

    /** The host part of a {@code Host} header, without its port and brackets, in lower case. */
    static String name(String host) {
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            return end < 0 ? name.substring(1) : name.substring(1, end);
        }
        int colon = name.lastIndexOf(':');
        // More than one colon is a bare IPv6 address, which has no port to strip.
        return colon >= 0 && name.indexOf(':') == colon ? name.substring(0, colon) : name;
    }

    static boolean acceptedHost(String name, String bindHost) {
        String bound = name(bindHost);
        if (bound.isEmpty() || "0.0.0.0".equals(bound) || "::".equals(bound)) {
            return true;
        }
        return loopback(name) || name.equals(bound);
    }

    private static boolean loopback(String name) {
        return "localhost".equals(name) || name.endsWith(".localhost") || "::1".equals(name)
                || name.matches("127(\\.\\d{1,3}){3}");
    }

    /** Whether {@code Origin} names the same authority the request was sent to. */
    private static boolean sameOrigin(String origin, String host) {
        int scheme = origin.indexOf("://");
        if (scheme < 0) {
            // "null", from a sandboxed frame or a file: page.
            return false;
        }
        return origin.substring(scheme + 3).equalsIgnoreCase(host.trim());
    }

    private static WebResponse forbidden(String why) {
        return ErrorBody.response(HttpStatus.FORBIDDEN, why, "Forbidden");
    }
}
