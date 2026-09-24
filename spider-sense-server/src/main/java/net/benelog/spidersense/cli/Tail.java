package net.benelog.spidersense.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * {@code tail}: the CLI's window on {@code GET /api/events}.
 *
 * <p>Every other command asks one question and prints one answer. This one stays
 * open, because an agent that has just sent a request should be able to watch it
 * land rather than sleep and ask {@code findings} again (cli.adoc#tail).
 *
 * <p>It is also the one command with no file to fall back to: a tingle is an
 * event, not a row, and the H2 file cannot be followed. Nothing at {@code --url}
 * is therefore a message on stderr and exit {@code 2}, never a quiet read of
 * yesterday's database.
 *
 * <p>The line is rendered here rather than by the server, which is the exception
 * to the CLI's rule of printing what it was given: the stream carries one JSON
 * object per event and no rendering, and cli.adoc#tail fixes the columns.
 */
final class Tail {

    /** The kinds a tingle can be; {@code --kind} takes one of them (api.adoc#tingle). */
    private static final Set<String> KINDS = Set.of("slow-request", "slow-query", "error");

    private static final Duration CONNECT = Duration.ofSeconds(2);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final int KIND_WIDTH = 14;
    private static final int SERVICE_WIDTH = 16;

    /** What one {@code tail} was asked to show, and what ends it. */
    record Watch(@Nullable String kind, @Nullable String service, @Nullable Long untilTraces,
            @Nullable Long timeoutMs, boolean json) {
    }

    private final Watch watch;
    private final PrintStream out;

    /** The store's trace count when the first {@code stats} arrived; -1 until then. */
    private long baseline = -1;

    private Tail(Watch watch, PrintStream out) {
        this.watch = watch;
        this.out = out;
    }

    static int run(Options options, String defaultUrl, PrintStream out, PrintStream err) {
        String base = trimSlash(options.value("url", defaultUrl));
        Watch watch = watch(options);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).build()) {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                return unreachable(err, base, "HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                return follow(body, watch, out);
            }
        } catch (IOException e) {
            return unreachable(err, base, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Cli.OK;
        }
    }

    /** The options {@code tail} takes, read once so a bad one is refused before the stream. */
    static Watch watch(Options options) {
        String kind = options.valueOrNull("kind");
        if (kind != null && !KINDS.contains(kind)) {
            throw new Options.Usage("--kind is one of " + String.join(", ", new java.util.TreeSet<>(KINDS)));
        }
        Long timeout = options.has("timeout")
                ? Selectors.durationMillis(options.valueOrNull("timeout"))
                : null;
        return new Watch(kind, options.valueOrNull("service"), options.optionalLong("until-traces"),
                timeout, options.flag("json"));
    }

    /**
     * The stream, with a timeout that closes it from the side.
     *
     * <p>A blocking read cannot be told to stop, so the deadline is a daemon that
     * closes the socket under it; the read then ends, which is what {@code tail}
     * wanted. An interrupted or broken stream ends the same way, with exit
     * {@code 0}: the command was watching, and watching is over.
     */
    private static int follow(InputStream body, Watch watch, PrintStream out) {
        Thread deadline = null;
        Long timeoutMs = watch.timeoutMs();
        if (timeoutMs != null) {
            deadline = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException interrupted) {
                    return;
                }
                try {
                    body.close();
                } catch (IOException ignored) {
                    // the read is ending either way
                }
            }, "spider-sense-tail-timeout");
            deadline.setDaemon(true);
            deadline.start();
        }
        try {
            return read(body, watch, out);
        } catch (IOException e) {
            return Cli.OK;
        } finally {
            if (deadline != null) {
                deadline.interrupt();
            }
        }
    }

    /**
     * The seam: an event stream in, lines out, no socket anywhere.
     *
     * <p>Server-sent events are three rules — a {@code field: value} line, a blank
     * line that ends an event, and a line starting with {@code :} that is a comment
     * — and this reads exactly those, because the keepalive comment must not be
     * mistaken for data.
     */
    static int read(InputStream sse, Watch watch, PrintStream out) throws IOException {
        return new Tail(watch, out).parse(sse);
    }

    private int parse(InputStream sse) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(sse, StandardCharsets.UTF_8));
        String name = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (name != null && data.length() > 0 && handle(name, data.toString())) {
                    return Cli.OK;
                }
                name = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                name = value;
            } else if ("data".equals(field)) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        return Cli.OK;
    }

    /** One event; true when it is the one the command was waiting for. */
    private boolean handle(String name, String data) {
        Json.JsonObject event;
        try {
            event = Json.parse(data).asObject();
        } catch (RuntimeException notJson) {
            return false;
        }
        if ("tingle".equals(name)) {
            if (matches(event)) {
                print(watch.json() ? json(name, event) : line(event));
            }
            return false;
        }
        if (!"stats".equals(name)) {
            return false;
        }
        if (watch.json()) {
            print(json(name, event));
        }
        long traces = event.optLong("traces", 0);
        if (baseline < 0) {
            baseline = traces;
            return false;
        }
        return watch.untilTraces() != null && traces - baseline >= watch.untilTraces();
    }

    private boolean matches(Json.JsonObject tingle) {
        if (watch.kind() != null && !watch.kind().equals(AttrJson.optionalString(tingle, "kind"))) {
            return false;
        }
        return watch.service() == null
                || watch.service().equals(AttrJson.optionalString(tingle, "service"));
    }

    /**
     * {@code 12:37:28.565  slow-query    spring-orders   SELECT orders  1,532 ms  <trace id>}:
     * the tingle's own fields, in the order cli.adoc#tail names them, two spaces apart.
     */
    private static String line(Json.JsonObject tingle) {
        StringBuilder text = new StringBuilder(clock(tingle.optLong("at", 0))).append("  ")
                .append(pad(tingle.optString("kind", "—"), KIND_WIDTH))
                .append(pad(tingle.optString("service", "—"), SERVICE_WIDTH));
        List<String> rest = new ArrayList<>(3);
        for (String key : List.of("title", "detail", "traceId")) {
            String value = AttrJson.optionalString(tingle, key);
            if (value != null && !value.isBlank()) {
                rest.add(oneLine(value));
            }
        }
        return text.append(String.join("  ", rest)).toString();
    }

    /** The event as it arrived, with the name it arrived under: one object per line. */
    private static String json(String name, Json.JsonObject event) {
        Json.JsonObject object = Json.obj().put("event", name);
        for (Map.Entry<String, Json.JsonValue> member : event) {
            object.put(member.getKey(), member.getValue());
        }
        return object.toJson();
    }

    /** Flushed line by line: a tail nobody sees until the buffer fills is not one. */
    private void print(String text) {
        out.println(text);
        out.flush();
    }

    private static int unreachable(PrintStream err, String base, String reason) {
        err.println("spider-sense: no Spider Sense at " + base + " (" + reason
                + "); there is no file to tail");
        return Cli.USAGE;
    }

    private static String clock(long at) {
        return CLOCK.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }

    private static String trimSlash(String base) {
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
