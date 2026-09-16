package loadgen;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters plus a ring of the last {@link #RING} request durations,
 * which is all the history the status line needs.
 */
public final class Stats {

    public static final int RING = 1000;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong clientErrors = new AtomicLong();
    private final AtomicLong serverErrors = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private final long[] durations = new long[RING];
    private int next;
    private int filled;

    public void record(int statusCode, long millis) {
        sent.incrementAndGet();
        if (statusCode >= 500) {
            serverErrors.incrementAndGet();
        } else if (statusCode >= 400) {
            clientErrors.incrementAndGet();
        } else {
            ok.incrementAndGet();
        }
        addDuration(millis);
    }

    public void recordFailure(long millis) {
        sent.incrementAndGet();
        failed.incrementAndGet();
        addDuration(millis);
    }

    private synchronized void addDuration(long millis) {
        durations[next] = millis;
        next = (next + 1) % RING;
        if (filled < RING) {
            filled++;
        }
    }

    /** A copy of the ring, oldest to newest, sorted. */
    public synchronized long[] sortedDurations() {
        long[] copy = Arrays.copyOf(durations, filled);
        Arrays.sort(copy);
        return copy;
    }

    public long average() {
        long[] sorted = sortedDurations();
        if (sorted.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return sum / sorted.length;
    }

    public long percentile(int percentile) {
        long[] sorted = sortedDurations();
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }

    public long sent() {
        return sent.get();
    }

    public long ok() {
        return ok.get();
    }

    public long clientErrors() {
        return clientErrors.get();
    }

    public long serverErrors() {
        return serverErrors.get();
    }

    public long failed() {
        return failed.get();
    }

    public String line() {
        return String.format("sent=%d ok=%d 4xx=%d 5xx=%d failed=%d avg=%dms p95=%dms",
                sent(), ok(), clientErrors(), serverErrors(), failed(), average(), percentile(95));
    }
}
