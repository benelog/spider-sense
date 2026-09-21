package loadgen;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Sends a steady, jittered trickle of requests to silk-bookstore, spring-orders and
 * servlet-warehouse so the Spider Sense dashboards have something to show without anyone clicking.
 * Requests go through java.net.http.HttpClient, which the OpenTelemetry agent instruments,
 * so running this under the agent makes some traces start here rather than in the servers.
 */
public final class LoadGen {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long STATUS_INTERVAL_MILLIS = 10_000;

    private final Options options;
    private final Stats stats = new Stats();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean finished = new AtomicBoolean();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private LoadGen(Options options) {
        this.options = options;
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        new LoadGen(options).run();
    }

    private void run() throws Exception {
        System.out.printf("load-gen: bookstore=%s orders=%s warehouse=%s rps=%.1f duration=%s seed=%d concurrency=%d%n",
                options.bookstore(), options.orders(), options.warehouse(), options.rps(),
                options.durationSeconds() == 0 ? "until Ctrl-C" : options.durationSeconds() + "s",
                options.seed(), options.concurrency());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            if (finished.compareAndSet(false, true)) {
                System.out.println();
                System.out.println("load-gen interrupted: " + stats.line());
            }
        }, "load-gen-shutdown"));

        waitForHealth("silk-bookstore", options.bookstore() + "/api/health");
        waitForHealth("spring-orders", options.orders() + "/api/health");
        waitForHealth("servlet-warehouse", options.warehouse() + "/api/health");

        Random random = new Random(options.seed());
        Semaphore inFlight = new Semaphore(options.concurrency());
        ExecutorService workers = Executors.newFixedThreadPool(options.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "load-gen-worker");
            thread.setDaemon(true);
            return thread;
        });

        long started = System.currentTimeMillis();
        long deadline = options.durationSeconds() == 0
                ? Long.MAX_VALUE
                : started + options.durationSeconds() * 1000L;
        long nextStatus = started + STATUS_INTERVAL_MILLIS;

        while (running.get() && System.currentTimeMillis() < deadline) {
            Scenario scenario = Scenarios.pick(random);
            List<Step> steps = scenario.steps(random);
            if (inFlight.tryAcquire()) {
                workers.execute(() -> {
                    try {
                        execute(steps);
                    } finally {
                        inFlight.release();
                    }
                });
            }

            // Jitter the gap between 50% and 150% of the nominal interval.
            long gap = Math.max(1, (long) (options.intervalMillis() * (0.5 + random.nextDouble())));
            Thread.sleep(gap);

            if (System.currentTimeMillis() >= nextStatus) {
                System.out.println(timestamp() + " " + stats.line());
                nextStatus += STATUS_INTERVAL_MILLIS;
            }
        }

        running.set(false);
        workers.shutdown();
        workers.awaitTermination(15, TimeUnit.SECONDS);
        finished.set(true);
        System.out.printf("load-gen finished after %d s: %s%n",
                (System.currentTimeMillis() - started) / 1000, stats.line());
    }

    /** One scenario: steps in order, stopping early if a chained step has no id to substitute. */
    private void execute(List<Step> steps) {
        Long chainedId = null;
        for (Step step : steps) {
            Step resolved = step;
            if (step.needsId()) {
                if (chainedId == null) {
                    return;
                }
                resolved = step.withId(chainedId);
            }
            String body = send(resolved);
            if (body == null) {
                return;
            }
            if (chainedId == null) {
                chainedId = idIn(body);
            }
        }
    }

    /** Returns the response body, or null when the request failed outright. */
    private @Nullable String send(Step step) {
        URI uri = step.uri(options);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        if (step.method().equals("POST")) {
            String contentType = step.contentType();
            String body = step.body();
            builder.header("Content-Type", contentType == null ? Step.JSON : contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        builder.header("Accept", "application/json, text/html;q=0.8");

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            stats.record(response.statusCode(), millisSince(started));
            return response.statusCode() < 400 ? response.body() : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.recordFailure(millisSince(started));
            return null;
        } catch (Exception e) {
            // The app may be down or still starting; that is counted, never fatal.
            stats.recordFailure(millisSince(started));
            return null;
        }
    }

    /** Retries a health endpoint until it answers or the wait budget runs out; never fails the run. */
    private void waitForHealth(String name, String url) throws InterruptedException {
        if (options.waitSeconds() == 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + options.waitSeconds() * 1000L;
        boolean announced = false;
        while (System.currentTimeMillis() < deadline && running.get()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 500) {
                    System.out.println("ready: " + name + " at " + url);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!announced) {
                    System.out.println("waiting for " + name + " at " + url
                            + " (" + reason(e) + "), up to " + options.waitSeconds() + "s");
                    announced = true;
                }
            }
            Thread.sleep(1000);
        }
        System.out.println("giving up on " + name + " at " + url + "; sending traffic anyway, failures are counted");
    }

    /** A short "why" for the waiting message: a refused connection is the usual one. */
    private static String reason(Exception e) {
        Throwable deepest = e;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return "connection refused";
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
                return "timed out";
            }
            deepest = cause;
        }
        return deepest.getClass().getSimpleName();
    }

    private static @Nullable Long idIn(String body) {
        Matcher matcher = ID.matcher(body);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String timestamp() {
        return LocalTime.now(ZoneId.systemDefault()).withNano(0).toString();
    }
}
