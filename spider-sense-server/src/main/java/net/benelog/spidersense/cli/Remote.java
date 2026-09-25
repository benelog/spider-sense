package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import net.benelog.spidersense.api.AgentApi;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The CLI when a Spider Sense is running: one HTTP call, and its body printed
 * verbatim.
 *
 * <p>Nothing is rendered here. The server already has the renderer the UI and
 * the H2-file mode use, so asking it for {@code format=text} and printing what
 * comes back is what keeps the three answers identical (agent-loop.adoc#interfaces). The only
 * thing this class decides is the exit code.
 */
final class Remote {

    /** Nothing answered at the URL: a connection refused or not made in time, never a 4xx. */
    static final class Unreachable extends RuntimeException {
        Unreachable(String reason) {
            super(reason);
        }

        /** The line a caller says it with, the reason in parentheses (cli.adoc). */
        String line(String base) {
            return "no Spider Sense at " + base + " (" + getMessage() + ")";
        }
    }

    /**
     * A Spider Sense took the connection and did not answer in {@link #READ}.
     *
     * <p>It is running, so the file is not the better answer: reading it in process
     * would take longer still, and the CLI says so instead (cli.adoc).
     */
    static final class Busy extends RuntimeException {

        /** Its message is the one line the CLI prints and an MCP tool call fails with. */
        Busy(String base) {
            super("the Spider Sense at " + base + " did not answer within " + READ.toMinutes() + " minutes");
        }
    }

    /**
     * A Spider Sense answered a forwarded message with an error status: one too
     * large for its body limit, say. It is running, so this call fails and the
     * next one asks it again; the file is not the better answer.
     */
    static final class Refused extends RuntimeException {

        Refused(String base, String why) {
            super("the Spider Sense at " + base + " refused the call: " + why);
        }
    }

    private static final Duration CONNECT = Duration.ofSeconds(2);
    private static final Duration READ = Duration.ofMinutes(2);

    /**
     * What a failed exchange means: {@link Busy} when the connection was made and
     * the answer did not come, {@link Unreachable} for everything else, a
     * connection that was not made in {@link #CONNECT} included.
     */
    static RuntimeException failure(IOException e, String base) {
        if (e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException)) {
            return new Busy(trimSlash(base));
        }
        return new Unreachable(reason(e));
    }

    /** An exchange's failure as a reason: the exception's simple name and its message. */
    static String reason(IOException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private Remote() {
    }

    /** A client that gives up on a connection not made in {@link #CONNECT}; the caller closes it. */
    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    /** A request to a path of the Spider Sense at {@code base}, which waits {@link #READ} for the answer. */
    private static HttpRequest.Builder request(String base, String path) {
        return HttpRequest.newBuilder(URI.create(trimSlash(base) + path)).timeout(READ);
    }

    /**
     * One exchange, and what its failure means: {@link #failure} for an {@link IOException}, and
     * {@link Unreachable} for an interrupt, with the thread's interrupt flag restored.
     *
     * <p>The client is the caller's, so a streamed body can still be read after this returns.
     */
    static <T> HttpResponse<T> send(HttpClient client, String base, HttpRequest request,
            HttpResponse.BodyHandler<T> body) {
        try {
            return client.send(request, body);
        } catch (IOException e) {
            throw failure(e, base);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted");
        }
    }

    static int run(Options options, String base, PrintStream out, PrintStream err) {
        if (Options.EXPORT.equals(options.command())) {
            return export(options, base, out, err);
        }
        if (Options.IMPORT.equals(options.command())) {
            return importFile(options, base, out, err);
        }
        HttpRequest.Builder request = request(base, pathAndQuery(options));
        // A statement and a mark are what the caller says rather than what it asks about, so they
        // travel in a body; everything else is a window and some filters (api.adoc).
        String body = command(options).bodyOf(options);
        if (command(options).method() == Command.Method.DELETE) {
            request.DELETE();
        } else if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try (HttpClient client = client()) {
            response = send(client, base, request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        if (response.statusCode() >= 400) {
            err.println("spider-sense: " + message(response));
            return response.statusCode() == 404 ? Cli.NOT_FOUND : Cli.USAGE;
        }
        if (Options.UNACK.equals(options.command()) || Options.UNRESOLVE.equals(options.command())) {
            Output.printReport(out, options, Options.UNACK.equals(options.command())
                    ? Reports.unack(options.requiredArgument())
                    : Reports.unresolve(options.requiredArgument()));
            return Cli.OK;
        }
        Output.print(out, response.body());
        return Options.CHECK.equals(options.command())
                ? verdict(response.headers().firstValue(AgentApi.PASS_HEADER).orElse(null))
                : Cli.OK;
    }

    /**
     * {@code export}: the window as a file rather than as a printed answer.
     *
     * <p>The body is copied stream to stream, so a session larger than the heap
     * still lands on disk; {@code --out} decides where and whether it is gzipped,
     * and with no {@code --out} it goes to standard output as it arrives.
     */
    private static int export(Options options, String base, PrintStream out, PrintStream err) {
        String name = options.valueOrNull("out");
        HttpRequest request = request(base, withWindow(options, new UrlBuilder("/api/export")).toString()).GET().build();
        HttpClient client = client();
        try {
            HttpResponse<InputStream> response =
                    send(client, base, request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                err.println("spider-sense: HTTP " + response.statusCode());
                return Cli.USAGE;
            }
            try (InputStream body = response.body(); OutputStream file = Sessions.out(name, out)) {
                body.transferTo(file);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "could not write " + (name == null ? "the export" : name), e);
            }
        } finally {
            client.close();
        }
        Output.wroteExport(err, name);
        return Cli.OK;
    }

    /**
     * {@code import}: the file posted to the running Spider Sense, which answers
     * the one line the CLI prints.
     *
     * <p>A {@code .gz} is sent as it lies with {@code Content-Encoding: gzip}
     * (api.adoc), so the bytes on the wire are the bytes on disk.
     */
    private static int importFile(Options options, String base, PrintStream out, PrintStream err) {
        String name = options.requiredArgument();
        byte[] body = Sessions.bytes(name);
        String format = options.flag("json") ? "json" : "text";
        HttpRequest.Builder request = request(base, "/api/import?format=" + format)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (Sessions.gzipped(name)) {
            request.header("Content-Encoding", "gzip");
        }
        HttpResponse<String> response;
        try (HttpClient client = client()) {
            response = send(client, base, request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (response.statusCode() >= 400) {
            err.println("spider-sense: " + message(response));
            return Cli.USAGE;
        }
        Output.print(out, response.body());
        return Cli.OK;
    }

    /**
     * One {@code POST} of a body that is not a command, for a transport that
     * speaks its own protocol: the {@code mcp} command forwarding a JSON-RPC
     * message to a running Spider Sense (mcp.adoc).
     *
     * <p>The client, the timeouts and the {@link Unreachable} rule are the CLI's
     * own, so "is there a Spider Sense there" is answered the same way for every
     * command. A 404 or 405 is unreachable too: this server answers a JSON-RPC
     * error with 200 and has {@code POST /mcp}, so either means the thing at that
     * URL is not a Spider Sense of this version, and the file is the better
     * answer. Any other error status is a running Spider Sense refusing this one
     * message ({@link Refused}).
     *
     * @return the response body, or null when the server answered with none
     */
    static @Nullable String post(String base, String path, @Nullable String body) {
        HttpRequest request = request(base, path)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try (HttpClient client = client()) {
            response = send(client, base, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        int status = response.statusCode();
        if (status == 404 || status == 405) {
            throw new Unreachable("HTTP " + status);
        }
        if (status >= 400) {
            throw new Refused(base, "HTTP " + status + ", " + message(response));
        }
        String answer = response.body();
        return answer == null || answer.isBlank() ? null : answer;
    }

    /**
     * The URL of one command, with every parameter the command takes: its row of
     * {@link Command}, then {@code full} and the format.
     *
     * <p>The limits are the CLI's own, not the server's defaults, so that a
     * command prints the same list whether it was answered over HTTP or read from
     * the file.
     */
    static String pathAndQuery(Options options) {
        UrlBuilder query = command(options).pathOf(options);
        if (options.flag("full")) {
            query.add("full", "true");
        }
        return query.add("format", options.flag("json") ? "json" : "text").toString();
    }

    /** {@code check}'s path: the window, the endpoint, and each rule asked for. */
    static UrlBuilder check(Options options) {
        UrlBuilder query = withWindow(options, new UrlBuilder("/api/check"))
                .add("endpoint", options.valueOrNull("endpoint"));
        for (Map.Entry<String, Double> rule : options.rules().entrySet()) {
            query.add(rule.getKey(), plainNumber(rule.getValue()));
        }
        return query;
    }

    /** The window and the service every windowed command sends. */
    static UrlBuilder withWindow(Options options, UrlBuilder query) {
        return query
                .add("since", options.value("since", Selectors.DEFAULT_SINCE))
                .add("until", options.valueOrNull("until"))
                .add("service", options.valueOrNull("service"));
    }

    private static Command command(Options options) {
        Command command = Command.named(options.command());
        if (command == null) {
            throw new Options.Usage("unknown command: " + options.command());
        }
        return command;
    }

    /**
     * The verdict, read from the header {@code /api/check} sets, because the text
     * rendering is for a reader and an exit code must not be parsed out of prose:
     * {@code true} passes, {@code false} fails the check, anything else is a window
     * with no requests to judge, and no header at all passes.
     */
    static int verdict(@Nullable String pass) {
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
     * (cli.adoc#sql); printing that line as it came is what makes the CLI say the same
     * thing whether a server answered or the file did.
     */
    private static String message(HttpResponse<String> response) {
        return message(response.statusCode(), response.headers().firstValue("content-type").orElse(null),
                response.body());
    }

    /**
     * The same from the parts of a response: the {@code error} of a JSON object,
     * else a {@code text/} body as it came, trimmed, else the status line with
     * the body, if there is one, after it.
     */
    static String message(int status, @Nullable String contentType, @Nullable String body) {
        if (body != null) {
            try {
                if (Json.parse(body) instanceof Json.JsonObject object && object.has("error")) {
                    return object.getString("error");
                }
            } catch (RuntimeException e) {
                // Not JSON: the status line and the body are all there is to say.
            }
        }
        boolean text = contentType != null && contentType.startsWith("text/");
        if (text && body != null && !body.isBlank()) {
            return body.trim();
        }
        return "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body.trim());
    }

    /** The base URL without the trailing slashes a user may type: {@code http://h:4000/} is {@code http://h:4000}. */
    static String trimSlash(String base) {
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** A whole limit stays whole in the URL: {@code maxErrors=0}, not {@code 0.0}. */
    private static String plainNumber(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    static String encode(@Nullable String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A URL with its query string, built in the order the parameters are added. */
    static final class UrlBuilder {

        private final StringBuilder url;
        private boolean started;

        UrlBuilder(String path) {
            this.url = new StringBuilder(path);
        }

        UrlBuilder add(String key, @Nullable String value) {
            if (value != null) {
                url.append(started ? '&' : '?').append(key).append('=').append(encode(value));
                started = true;
            }
            return this;
        }

        UrlBuilder add(String key, int value) {
            return add(key, String.valueOf(value));
        }

        @Override
        public String toString() {
            return url.toString();
        }
    }
}
