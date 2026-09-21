package net.benelog.spidersense.store;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingest notifications for the SSE subscribers.
 *
 * <p>Each subscriber gets a bounded queue of its own. A browser tab that stopped
 * reading must not hold the ingest path up, so a full queue drops its oldest
 * event rather than blocking: a missed tingle is a cosmetic loss, a stalled
 * OTLP export is not.
 */
public final class EventBus {

    /**
     * One SSE frame waiting to go out: the event name and the value it carries
     * ({@link Tingle}, {@link ServiceInfo}). Rendering is the API layer's job,
     * so the store never builds JSON.
     */
    public record Event(String name, Object payload) {
    }

    private static final int QUEUE_CAPACITY = 512;

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong lastIngestAt = new AtomicLong();

    public Subscription subscribe() {
        Subscription subscription = new Subscription();
        subscriptions.add(subscription);
        return subscription;
    }

    public void publish(String name, Object payload) {
        Event event = new Event(name, payload);
        for (Subscription subscription : subscriptions) {
            subscription.offer(event);
        }
    }

    public <T> void publishAll(String name, List<T> payloads) {
        for (T payload : payloads) {
            publish(name, payload);
        }
    }

    /** Called whenever anything was stored; the SSE loop uses it to coalesce {@code stats}. */
    public void ingested(long at) {
        lastIngestAt.accumulateAndGet(at, Math::max);
    }

    public long lastIngestAt() {
        return lastIngestAt.get();
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    /** An open subscription; closing it removes the queue from the bus. */
    public final class Subscription implements AutoCloseable {

        private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        private Subscription() {
        }

        void offer(Event event) {
            while (!queue.offer(event)) {
                if (queue.poll() == null) {
                    return;
                }
            }
        }

        /** The next event, or null when none arrived within {@code timeoutMillis}. */
        public Event poll(long timeoutMillis) throws InterruptedException {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            subscriptions.remove(this);
        }
    }
}
