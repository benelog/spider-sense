package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LazyTest {

    @Test
    void theValueIsComputedOnTheFirstGetAndOnlyThen() {
        AtomicInteger reads = new AtomicInteger();
        Lazy<List<String>> lazy = Lazy.of(() -> {
            reads.incrementAndGet();
            return List.of("a");
        });

        assertThat(reads).hasValue(0);
        assertThat(lazy.get()).containsExactly("a");
        assertThat(lazy.get()).isSameAs(lazy.get());
        assertThat(reads).hasValue(1);
    }
}
