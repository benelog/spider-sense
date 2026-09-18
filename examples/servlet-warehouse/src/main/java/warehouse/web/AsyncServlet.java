package warehouse.web;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/async?ms=}: the request leaves {@code service()} immediately
 * and is answered later, on a thread that is not the request thread.
 *
 * <p>This is here to check that the server span lasts until {@code complete()}
 * rather than ending when {@code doGet} returns: a request asking for 700 ms
 * should be a 700 ms span, not a 1 ms one. The timeout is 1500 ms, so
 * {@code ?ms=3000} is answered 503 by the {@code AsyncListener} at 1500 ms and
 * the scheduled completion finds the work already done.
 */
public class AsyncServlet extends HttpServlet {

    private static final long DEFAULT_MS = 700;
    private static final long TIMEOUT_MS = 1_500;

    private final transient ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "warehouse-async");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        long wait = Math.clamp(parse(request.getParameter("ms")), 0, 10_000);

        AsyncContext async = request.startAsync();
        async.setTimeout(TIMEOUT_MS);

        // Both the timeout and the scheduled completion race for the response;
        // whichever arrives first wins and the other does nothing.
        AtomicBoolean answered = new AtomicBoolean();
        async.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                if (answered.compareAndSet(false, true)) {
                    HttpServletResponse late = (HttpServletResponse) async.getResponse();
                    Out.json(late, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            """
                            {"error":"timed out"}""");
                    async.complete();
                }
            }

            @Override
            public void onComplete(AsyncEvent event) {
                // Nothing to release.
            }

            @Override
            public void onError(AsyncEvent event) {
                answered.set(true);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // Never re-dispatched.
            }
        });

        scheduler.schedule(() -> answer(async, answered, HttpServletResponse.SC_OK,
                """
                {"waitedMs":%d}""".formatted(wait)), wait, TimeUnit.MILLISECONDS);

        scheduler.schedule(() -> answer(async, answered, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                """
                {"error":"timed out"}"""), TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Whichever of the three deadlines gets here first answers; the others do nothing. */
    private void answer(AsyncContext async, AtomicBoolean answered, int status, String body) {
        if (!answered.compareAndSet(false, true)) {
            return;
        }
        try {
            Out.json((HttpServletResponse) async.getResponse(), status, body);
            async.complete();
        } catch (IOException | IllegalStateException e) {
            async.complete();
        }
    }

    /** Releases the one thread the servlet owns. */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private long parse(String value) {
        try {
            return value == null ? DEFAULT_MS : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return DEFAULT_MS;
        }
    }
}
