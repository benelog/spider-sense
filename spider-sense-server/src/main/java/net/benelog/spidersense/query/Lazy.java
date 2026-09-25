package net.benelog.spidersense.query;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * A value computed on its first use and kept: one read that several rules of one
 * answer share, made only if one of them asks.
 *
 * <p>Not thread-safe: it lives for the length of one answer, on the thread that
 * computes it.
 */
final class Lazy<T> implements Supplier<T> {

    private final Supplier<T> compute;
    private @Nullable T value;

    private Lazy(Supplier<T> compute) {
        this.compute = compute;
    }

    static <T> Lazy<T> of(Supplier<T> compute) {
        return new Lazy<>(compute);
    }

    @Override
    public T get() {
        T loaded = value;
        if (loaded == null) {
            loaded = compute.get();
            value = loaded;
        }
        return loaded;
    }
}
