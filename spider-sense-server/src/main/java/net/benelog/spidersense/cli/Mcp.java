package net.benelog.spidersense.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Map;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.mcp.McpServer;
import net.benelog.spidersense.mcp.McpTools;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * {@code java -jar spider-sense.jar mcp}: the MCP server over stdio, for a host
 * that launches its tools as a process (mcp.adoc).
 *
 * <p>Newline-delimited JSON-RPC on stdin and stdout, nothing else on stdout,
 * diagnostics on stderr, and it ends at end of input.
 *
 * <p>Where an answer comes from is the CLI's own decision, made once more rather
 * than a second time: {@code initialize}, {@code ping} and {@code tools/list} are
 * this process's own, and a {@code tools/call} goes to the Spider Sense at
 * {@code --url} when one answers and to the H2 file when none does — with the same
 * line on stderr the other commands print, because a host must never mistake
 * yesterday's database for a live one. {@code --db} is the statement that the file
 * is the answer, and then no server is asked at all.
 */
final class Mcp {

    private final McpServer server;
    private final Options options;
    private final PrintStream out;
    private final PrintStream err;

    /** The Spider Sense to forward a call to, or null once the file is the answer. */
    private @Nullable String base;

    /** A named {@code --url} is a statement that there is a server there. */
    private final boolean named;

    /** Opened on the first call that needs it, and only if one does. */
    private @Nullable Config config;
    private @Nullable Reports reports;

    private Mcp(Options options, @Nullable String base, boolean named, PrintStream out,
            PrintStream err) {
        this.options = options;
        this.base = base;
        this.named = named;
        this.out = out;
        this.err = err;
        this.server = new McpServer(McpTools.TOOLS, this::inProcess, Version.CURRENT);
    }

    static int run(Options options, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err) {
        String url = options.valueOrNull("url");
        // --db says where to read, which leaves no question to ask a server.
        String base = options.has("db") ? null : url == null ? defaultUrl : url;
        Mcp mcp = new Mcp(options, base, url != null, out, err);
        try {
            return mcp.pump(in);
        } finally {
            mcp.close();
        }
    }

    private int pump(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handle(line);
                if (response != null) {
                    out.print(response);
                    out.print('\n');
                    out.flush();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Cli.OK;
    }

    /**
     * One message: forwarded when it is a tool call and a server may answer it,
     * and answered here otherwise.
     *
     * <p>The forwarded message is the client's own bytes and what comes back is
     * the server's own bytes, so the two transports cannot render differently:
     * over HTTP it is the same {@link McpServer} answering.
     */
    private @Nullable String handle(String line) {
        String forwardTo = base;
        if (forwardTo != null && "tools/call".equals(method(line))) {
            try {
                return Remote.post(forwardTo, "/mcp", line);
            } catch (Remote.Busy e) {
                // Running, only slow: this call fails and the next one asks it again.
                String said = e.said();
                return server.handle(line, (name, arguments) -> McpServer.ToolResult.failed(said));
            } catch (Remote.Refused e) {
                // Running, and it refused this one message: the same.
                String said = String.valueOf(e.getMessage());
                return server.handle(line, (name, arguments) -> McpServer.ToolResult.failed(said));
            } catch (Remote.Unreachable e) {
                if (named) {
                    String said = "no Spider Sense at " + forwardTo + " (" + e.getMessage() + ")";
                    return server.handle(line, (name, arguments) -> McpServer.ToolResult.failed(said));
                }
                err.println("(no Spider Sense at " + forwardTo + "; reading "
                        + Local.describe(config()) + " directly)");
                err.flush();
                base = null;
            }
        }
        return server.handle(line);
    }

    private McpServer.ToolResult inProcess(String name, Map<String, Object> arguments) {
        Reports opened = reports;
        if (opened == null) {
            opened = Reports.readOnly(config());
            reports = opened;
        }
        return new McpTools(opened).call(name, arguments);
    }

    private Config config() {
        Config opened = config;
        if (opened == null) {
            opened = Local.config(options);
            config = opened;
        }
        return opened;
    }

    /** The method of a message, or null when it does not have one this can read. */
    private static @Nullable String method(String line) {
        try {
            if (Json.parse(line) instanceof Json.JsonObject object
                    && object.has("method") && object.get("method").isString()) {
                return object.getString("method");
            }
        } catch (RuntimeException e) {
            // Not a message: let the dispatcher answer with the protocol's own error.
        }
        return null;
    }

    private void close() {
        Reports opened = reports;
        if (opened != null) {
            opened.close();
        }
    }
}
