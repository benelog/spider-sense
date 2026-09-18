package net.benelog.spidersense.api;

import net.benelog.spidersense.mcp.McpServer;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

/**
 * MCP over Streamable HTTP: one JSON-RPC message per request, on the server's own
 * port (api.md, "MCP").
 *
 * <p>The route is all there is to the transport. {@link McpServer} speaks the
 * protocol and {@link net.benelog.spidersense.mcp.McpTools} answers from the same
 * {@link Reports} the JSON endpoints and the CLI use, so this class decides only
 * two things: the status code, and that {@code GET} is not how you talk to it.
 *
 * <p>Stateless by construction: no {@code Mcp-Session-Id} is issued or read, so
 * there is nothing to expire and nothing to clean up when a host goes away.
 */
public final class McpApi {

    public static final String PATH = "/mcp";

    private final McpServer server;

    public McpApi(McpServer server) {
        this.server = server;
    }

    public void register(App app) {
        app.post(PATH, "One MCP message, for a host with no shell", this::message);
        // Registered rather than left to the router, because an unmatched GET here
        // would fall through to the single page: the UI's router is hash-based, so
        // anything without an extension is index.html (SpiderSenseServer.notFound).
        app.get(PATH, "MCP is POST only", McpApi::postOnly);
        app.delete(PATH, "MCP is POST only", McpApi::postOnly);
    }

    /**
     * A request is answered with the JSON-RPC response; a notification, which
     * expects nothing back, with {@code 202} and no body.
     */
    public WebResponse message(WebRequest req) {
        String response = server.handle(req.body());
        return response == null
                ? WebResponse.empty(HttpStatus.ACCEPTED)
                : WebResponse.json(response);
    }

    private static WebResponse postOnly(WebRequest req) {
        throw new HttpException(HttpStatus.METHOD_NOT_ALLOWED,
                "MCP here is one POST per JSON-RPC message; this server issues no session and "
                        + "opens no stream");
    }
}
