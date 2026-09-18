package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import net.benelog.spidersense.api.AgentApi;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersilk.json.Json;

/**
 * The CLI when a Spider Sense is running: one HTTP call, and its body printed
 * verbatim.
 *
 * <p>Nothing is rendered here. The server already has the renderer the UI and
 * the H2-file mode use, so asking it for {@code format=text} and printing what
 * comes back is what keeps the three answers identical (agent.md). The only
 * thing this class decides is the exit code.
 */
final class Remote {

    /** Nothing answered at the URL: a connection refused or a timeout, never a 4xx. */
    static final class Unreachable extends RuntimeException {
        Unreachable(String reason) {
            super(reason);
        }
    }

    private static final Duration CONNECT = Duration.ofSeconds(2);
    private static final Duration READ = Duration.ofSeconds(30);

    private Remote() {
    }

    static int run(Options options, String base, PrintStream out, PrintStream err) {
        URI uri = URI.create(trimSlash(base) + path(options));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(READ);
        String body = body(options);
        if (Options.UNACK.equals(options.command())) {
            request.DELETE();
        } else if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Unreachable(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted");
        }

        if (response.statusCode() >= 400) {
            err.println("spider-sense: " + message(response));
            return response.statusCode() == 404 ? Cli.NOT_FOUND : Cli.USAGE;
        }
        if (Options.UNACK.equals(options.command())) {
            Reports.Report report = Reports.unack(options.argument());
            print(out, options.flag("json") ? report.json().toJson() : report.text());
            return Cli.OK;
        }
        print(out, response.body());
        return Options.CHECK.equals(options.command()) ? verdict(response) : Cli.OK;
    }

    /**
     * One {@code POST} of a body that is not a command, for a transport that
     * speaks its own protocol: the {@code mcp} command forwarding a JSON-RPC
     * message to a running Spider Sense (agent.md, "MCP").
     *
     * <p>The client, the timeouts and the {@link Unreachable} rule are the CLI's
     * own, so "is there a Spider Sense there" is answered the same way for every
     * command. A status of 400 or more is unreachable too: this server answers a
     * JSON-RPC error with 200, so a status means the thing at that URL is not a
     * Spider Sense of this version, and the file is the better answer.
     *
     * @return the response body, or null when the server answered with none
     */
    static String post(String base, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(base) + path))
                .timeout(READ)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Unreachable(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted");
        }
        if (response.statusCode() >= 400) {
            throw new Unreachable("HTTP " + response.statusCode());
        }
        String answer = response.body();
        return answer == null || answer.isBlank() ? null : answer;
    }

    /**
     * The URL of one command, with every parameter the command takes.
     *
     * <p>The limits are the CLI's own, not the server's defaults, so that a
     * command prints the same list whether it was answered over HTTP or read from
     * the file.
     */
    static String path(Options options) {
        String format = options.flag("json") ? "json" : "text";
        Query query = switch (options.command()) {
            case "status" -> new Query("/api/status");
            case "findings" -> window(options, new Query("/api/findings"))
                    .add("limit", options.limit(Limits.FINDINGS, Limits.FINDINGS_MAX))
                    .add("hideAcked", options.flag("hide-acked") ? "true" : null);
            case Options.ACK, Options.UNACK ->
                    new Query("/api/findings/" + encode(options.argument()) + "/ack");
            case Options.TRACE -> new Query("/api/traces/" + encode(options.argument()));
            case "traces" -> window(options, new Query("/api/traces"))
                    .add("status", options.value("status", null))
                    .add("minMs", options.value("min-ms", null))
                    .add("q", options.value("q", null))
                    .add("limit", options.limit(Limits.TRACES, Limits.TRACES_MAX));
            case "endpoints" -> window(options, new Query("/api/endpoints"));
            case "queries" -> window(options, new Query("/api/queries"))
                    .add("limit", options.limit(Limits.QUERIES, Limits.QUERIES_MAX));
            case "errors" -> window(options, new Query("/api/errors"))
                    .add("limit", options.limit(Limits.ERRORS, Limits.ERRORS_MAX));
            case "logs" -> window(options, new Query("/api/logs"))
                    .add("severity", options.value("severity", null))
                    .add("q", options.value("q", null))
                    .add("traceId", options.value("trace", null))
                    .add("limit", options.limit(Limits.LOGS, Limits.LOGS_MAX));
            case Options.MARK -> new Query("/api/marks");
            case "marks" -> new Query("/api/marks").add("limit", options.limit(Limits.MARKS, Limits.MARKS_MAX));
            case Options.COMPARE -> new Query("/api/compare")
                    .add("before", options.value("before", null))
                    .add("after", options.value("after", null))
                    .add("until", options.value("until", null))
                    .add("service", options.value("service", null));
            case Options.CHECK -> check(options);
            case Options.SQL -> new Query("/api/sql");
            default -> throw new Options.Usage("unknown command: " + options.command());
        };
        if (options.flag("full")) {
            query.add("full", "true");
        }
        return query.add("format", format).toString();
    }

    private static Query check(Options options) {
        Query query = window(options, new Query("/api/check"))
                .add("endpoint", options.value("endpoint", null));
        for (Map.Entry<String, Double> rule : options.rules().entrySet()) {
            query.add(rule.getKey(), plain(rule.getValue()));
        }
        return query;
    }

    private static Query window(Options options, Query query) {
        return query
                .add("since", options.value("since", Limits.SINCE))
                .add("until", options.value("until", null))
                .add("service", options.value("service", null));
    }

    /**
     * The JSON body of the two commands that post one, or null for a {@code GET}.
     *
     * <p>A statement and a mark are what the caller says rather than what it asks
     * about, so they travel in a body; everything else is a window and some
     * filters, which are query parameters (api.md).
     */
    private static String body(Options options) {
        return switch (options.command()) {
            case Options.MARK -> Json.obj()
                    .put("name", options.argument())
                    .put("note", options.value("note", null))
                    .put("service", options.value("service", null))
                    .toJson();
            case Options.ACK -> Json.obj()
                    .put("note", options.value("note", null))
                    .toJson();
            case Options.SQL -> Json.obj()
                    .put("sql", options.argument())
                    .put("limit", options.limit(Limits.SQL, Limits.SQL_MAX))
                    .toJson();
            default -> null;
        };
    }

    /**
     * The verdict, read from the header {@code /api/check} sets, because the text
     * rendering is for a reader and an exit code must not be parsed out of prose.
     */
    private static int verdict(HttpResponse<String> response) {
        String pass = response.headers().firstValue(AgentApi.PASS_HEADER).orElse(null);
        if (pass == null) {
            return Cli.OK;
        }
        return switch (pass) {
            case "true" -> Cli.OK;
            case "false" -> Cli.CHECK_FAILED;
            default -> Cli.NO_REQUESTS;
        };
    }

    /**
     * The {@code {"error": "..."}} the contract promises, or the body as it came.
     *
     * <p>{@code /api/sql} answers its errors in the format that was asked for, so a
     * refused statement arrives as one line of text rather than as an object
     * (agent.md); printing that line as it came is what makes the CLI say the same
     * thing whether a server answered or the file did.
     */
    private static String message(HttpResponse<String> response) {
        String body = response.body();
        try {
            if (Json.parse(body) instanceof Json.JsonObject object && object.has("error")) {
                return object.getString("error");
            }
        } catch (RuntimeException e) {
            // Not JSON: the status line and the body are all there is to say.
        }
        boolean text = response.headers().firstValue("content-type").orElse("").startsWith("text/");
        if (text && body != null && !body.isBlank()) {
            return body.trim();
        }
        return "HTTP " + response.statusCode() + (body == null || body.isBlank() ? "" : ": " + body.trim());
    }

    private static void print(PrintStream out, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        out.print(body);
        if (!body.endsWith("\n")) {
            out.println();
        }
        out.flush();
    }

    private static String trimSlash(String base) {
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** A whole limit stays whole in the URL: {@code maxErrors=0}, not {@code 0.0}. */
    private static String plain(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A URL with its query string, built in the order the parameters are added. */
    private static final class Query {

        private final StringBuilder url;
        private boolean started;

        Query(String path) {
            this.url = new StringBuilder(path);
        }

        Query add(String key, String value) {
            if (value != null) {
                url.append(started ? '&' : '?').append(key).append('=').append(encode(value));
                started = true;
            }
            return this;
        }

        Query add(String key, int value) {
            return add(key, String.valueOf(value));
        }

        @Override
        public String toString() {
            return url.toString();
        }
    }
}
