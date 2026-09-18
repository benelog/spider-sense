package net.benelog.spidersense.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Map;

import net.benelog.spidersense.api.ApiRoutes;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.mcp.McpServer;
import net.benelog.spidersense.mcp.McpTools;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersilk.json.Json;

/**
 * {@code java -jar spider-sense.jar mcp}: the MCP server over stdio, for a host
 * that launches its tools as a process (agent.md, "MCP").
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
    private String base;

    /** A named {@code --url} is a statement that there is a server there. */
    private final boolean named;

    /** Opened on the first call that needs it, and only if one does. */
    private Config config;
    private Reports reports;

    private Mcp(Options options, String base, boolean named, PrintStream out, PrintStream err) {
        this.options = options;
        this.base = base;
        this.named = named;
        this.out = out;
        this.err = err;
        this.server = new McpServer(this::inProcess, ApiRoutes.VERSION);
    }

    static int run(Options options, String defaultUrl, InputStream in, PrintStream out,
            PrintStream err) {
        String url = options.value("url", null);
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
    private String handle(String line) {
        if (base != null && "tools/call".equals(method(line))) {
            try {
                return Remote.post(base, "/mcp", line);
            } catch (Remote.Unreachable e) {
                if (named) {
                    String said = "no Spider Sense at " + base + " (" + e.getMessage() + ")";
                    return server.handle(line, (name, arguments) -> McpServer.ToolResult.failed(said));
                }
                err.println("(no Spider Sense at " + base + "; reading "
                        + Local.describe(config()) + " directly)");
                err.flush();
                base = null;
            }
        }
        return server.handle(line);
    }

    private McpServer.ToolResult inProcess(String name, Map<String, Object> arguments) {
        if (reports == null) {
            reports = Reports.readOnly(config());
        }
        return new McpTools(reports).call(name, arguments);
    }

    private Config config() {
        if (config == null) {
            config = Local.config(options);
        }
        return config;
    }

    /** The method of a message, or null when it does not have one this can read. */
    private static String method(String line) {
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
        if (reports != null) {
            reports.close();
        }
    }
}
