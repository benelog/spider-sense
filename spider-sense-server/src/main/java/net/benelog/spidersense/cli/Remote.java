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
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
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

    /**
     * No Spider Sense answered at the URL: a connection refused or not made in time, or an error
     * answer from some other server there; never a Spider Sense's own 4xx.
     */
    static final class Unreachable extends RuntimeException {

        /** Whether something at the URL did answer, only not as a Spider Sense does. */
        private final boolean answered;

        /** The question the line ends with when something answered. */
        private final String question;

        Unreachable(String reason) {
            this(reason, false, "");
        }

        private Unreachable(String reason, boolean answered, String question) {
            super(reason);
            this.answered = answered;
            this.question = question;
        }

        /**
         * An error answer that is not a Spider Sense's, to a path every Spider Sense has: some
         * other server is at the URL, most often the application's own port.
         */
        static Unreachable answered(int status, String path) {
            return new Unreachable("HTTP " + status + " for " + withoutQuery(path), true,
                    "is that the application's port?");
        }

        /**
         * A Spider Sense's own {@code 404} for a route it does not have: one of another version,
         * which has no such command to answer, rather than a trace or a mark that is not there.
         */
        static Unreachable noRoute(String path) {
            return new Unreachable("HTTP 404 for " + withoutQuery(path), true,
                    "is it a Spider Sense of another version?");
        }

        boolean answered() {
            return answered;
        }

        /**
         * The line a caller says it with, the reason in parentheses (cli.adoc#fallback), and the
         * question to ask when a server did answer.
         */
        String line(String base) {
            return "no Spider Sense at " + base + " (" + getMessage() + ")" + (answered ? "; " + question : "");
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

    /** What a Spider Sense answers {@code format=text} with. */
    static final String MARKDOWN = "text/markdown";

    /** What it answers {@code format=json}, an export and an MCP message with. */
    static final String JSON = "application/json";

    /** What {@code /api/events} streams. */
    static final String EVENT_STREAM = "text/event-stream";

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

    /**
     * A base URL as the CLI asks it: the trailing slashes gone, and {@code http://} in front of one
     * typed without a scheme, so {@code --url=127.0.0.1:4000} is the server it names rather than a
     * URI the client refuses.
     *
     * <p>A path is a usage error rather than a prefix: {@code http://127.0.0.1:4000/api}, copied
     * from a browser, would ask {@code /api/api/status} and read the server's 404 for that path
     * as a trace or a mark that is not there.
     */
    static String base(String url) {
        String trimmed = trimSlash(url);
        String base = trimmed.contains("://") ? trimmed : "http://" + trimmed;
        URI uri = URI.create(base);
        if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new Options.Usage("--url is a base URL, such as http://127.0.0.1:4000, with no path: " + url.trim());
        }
        return base;
    }

    private static String withoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
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
        String path = pathAndQuery(options);
        HttpRequest.Builder request = request(base, path);
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
            return refused(options, response.statusCode(), contentType(response), response.body(), path, err);
        }
        boolean ours = command(options).method() == Command.Method.DELETE
                ? response.statusCode() == 204
                : answered(response, options.flag("json") ? JSON : MARKDOWN)
                        && (!Options.CHECK.equals(options.command())
                                || response.headers().firstValue(AgentApi.PASS_HEADER).isPresent());
        if (!ours) {
            // A dev server's index.html, a redirect, a health page: printing it would pass a check.
            throw Unreachable.answered(response.statusCode(), path);
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
        String path = withWindow(options, new UrlBuilder("/api/export")).toString();
        HttpRequest request = request(base, path).GET().build();
        HttpClient client = client();
        try {
            HttpResponse<InputStream> response =
                    send(client, base, request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                // An error is a few bytes of JSON, read whole, as every other command's is.
                String error;
                try (InputStream body = response.body()) {
                    error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw failure(e, base);
                }
                return refused(options, response.statusCode(), contentType(response), error, path, err);
            }
            // A health page is JSON too; the export is the one that comes as a spider-sense attachment.
            if (!answered(response, JSON) || !response.headers().firstValue("content-disposition")
                    .orElse("").contains("filename=\"spider-sense-")) {
                throw Unreachable.answered(response.statusCode(), path);
            }
            try (InputStream body = response.body(); OutputStream file = Sessions.out(name, out)) {
                body.transferTo(file);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "could not write " + (name == null ? "the export" : name) + ": " + Sessions.why(e), e);
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
        String path = "/api/import?format=" + format;
        HttpRequest.Builder request = request(base, path)
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
            requireSpiderSense(response, path);
            err.println("spider-sense: " + message(response));
            return Cli.USAGE;
        }
        if (!answered(response, options.flag("json") ? JSON : MARKDOWN)) {
            throw Unreachable.answered(response.statusCode(), path);
        }
        Output.print(out, response.body());
        return Cli.OK;
    }

    /**
     * Whether a success answer is one a Spider Sense sends: a {@code 2xx} of the type asked for.
     *
     * <p>A {@code 3xx} never is, since the client follows no redirect, and neither is a body of
     * another type: the frontend's dev server answers every path with its {@code index.html},
     * and a health page answers {@code {"status":"UP"}}, both with {@code 200}. Printed as the
     * answer, either would pass a {@code check} for a port that belongs to another server.
     *
     * @param type the media type the request asked for, such as {@code text/markdown}
     */
    static boolean answered(HttpResponse<?> response, String type) {
        return answered(response.statusCode(), contentType(response), type);
    }

    /** The same from the parts of a response. */
    static boolean answered(int status, @Nullable String contentType, String type) {
        return status >= 200 && status < 300 && contentType != null
                && contentType.trim().toLowerCase(java.util.Locale.ROOT).startsWith(type);
    }

    /**
     * An error answer, said as the file path says it: the server's reason, the help to read after
     * a {@code 400}, which is the caller's mistake as the file path's selector and store refusals
     * are, and exit {@code 4} for a {@code 404}, which is something named that is not there.
     *
     * @throws Unreachable when the error did not come from a Spider Sense ({@link #requireSpiderSense})
     */
    private static int refused(Options options, int status, @Nullable String contentType, @Nullable String body,
            String path, PrintStream err) {
        if (!fromSpiderSense(status, contentType, body)) {
            throw Unreachable.answered(status, path);
        }
        String message = message(status, contentType, body);
        if (status == 404 && message.equals("Not found: " + withoutQuery(path))) {
            // The server's words for a route it does not have (SpiderSenseServer#notFound).
            throw Unreachable.noRoute(path);
        }
        err.println("spider-sense: " + message);
        if (status == 400) {
            err.println(Cli.pointer(options.command()));
        }
        return status == 404 ? Cli.NOT_FOUND : Cli.USAGE;
    }

    private static @Nullable String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse(null);
    }

    /**
     * Throws {@link Unreachable} when an error answer did not come from a Spider Sense.
     *
     * <p>A Spider Sense answers every error with the {@code {"error": "..."}} object of
     * api.adoc#conventions, and nothing beside the {@code error}, or, to a caller that asked for
     * text, with the one line {@code /api/sql} sends as {@code text/markdown} with a {@code 400}.
     * Anything else, such as an HTML error page, a bare {@code 404}, or the
     * {@code {"status": 404, "error": "Not Found", "path": ...}} of a Spring application, is some
     * other server at the URL: then nothing the caller named is missing, there is simply no Spider
     * Sense to ask, which is exit {@code 2} and never the {@code 4} of a missing trace or mark
     * (cli.adoc#exit-codes).
     *
     * @param path the path asked, which the reason names
     */
    private static void requireSpiderSense(HttpResponse<String> response, String path) {
        if (!fromSpiderSense(response.statusCode(), contentType(response), response.body())) {
            throw Unreachable.answered(response.statusCode(), path);
        }
    }

    /** Whether an error body is one a Spider Sense sends, as {@link #requireSpiderSense} describes. */
    static boolean fromSpiderSense(int status, @Nullable String contentType, @Nullable String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            if (Json.parse(body) instanceof JsonObject object) {
                return object.size() == 1 && object.has("error");
            }
        } catch (RuntimeException e) {
            // Not JSON: only the text line below is ours.
        }
        return status == 400 && plainText(contentType);
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
     * <p>A success that is not JSON, a redirect among them, is unreachable as well: the thing at
     * that URL is not a Spider Sense, whatever status it answered with.
     *
     * @return the response body, or null for a {@code 202} with none
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
            throw Unreachable.answered(status, path);
        }
        if (status >= 400) {
            throw new Refused(base, "HTTP " + status + ", " + message(response));
        }
        String answer = response.body();
        if (status == 202 && (answer == null || answer.isBlank())) {
            return null;
        }
        if (answer == null || !answered(response, JSON) || !jsonRpc(answer)) {
            // A page written to the host as the JSON-RPC answer would corrupt its stream, and a
            // redirect, which has no body, would leave the call unanswered.
            throw Unreachable.answered(status, path);
        }
        return answer;
    }

    /** Whether a body is a JSON-RPC message, as every answer of {@code POST /mcp} is; a health page is not. */
    private static boolean jsonRpc(String body) {
        try {
            return Json.parse(body) instanceof JsonObject message
                    && "2.0".equals(message.getString("jsonrpc", ""));
        } catch (RuntimeException e) {
            return false;
        }
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
     * with no requests to judge, and no header at all is no verdict, which never passes:
     * {@link #run} reads such an answer as not a Spider Sense's.
     */
    static int verdict(@Nullable String pass) {
        if (pass == null) {
            return Cli.USAGE;
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
        return message(response.statusCode(), contentType(response), response.body());
    }

    /**
     * The same from the parts of a response: the {@code error} of a JSON object, or the
     * {@code message} of a JSON-RPC error, else a {@code text/} body as it came, trimmed, else the status line with
     * the first line of the body, if there is one, after it.
     */
    static String message(int status, @Nullable String contentType, @Nullable String body) {
        if (body != null) {
            try {
                if (Json.parse(body) instanceof JsonObject object && object.has("error")) {
                    // The JSON-RPC error /mcp answers a body past its limit with carries its message inside.
                    return object.get("error") instanceof JsonObject rpc && rpc.has("message")
                            ? rpc.getString("message") : object.getString("error");
                }
            } catch (RuntimeException e) {
                // Not JSON: the status line and the body are all there is to say.
            }
        }
        if (plainText(contentType) && body != null && !body.isBlank()) {
            return body.trim();
        }
        return "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body.trim().lines().findFirst().orElse(""));
    }

    /** Whether a body is text a Spider Sense sends: Markdown, or plain text. */
    private static boolean plainText(@Nullable String contentType) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("text/markdown") || type.startsWith("text/plain");
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

        UrlBuilder add(String key, @Nullable Long value) {
            return add(key, value == null ? null : value.toString());
        }

        @Override
        public String toString() {
            return url.toString();
        }
    }
}
