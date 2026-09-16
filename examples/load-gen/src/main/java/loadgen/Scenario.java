package loadgen;

import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * One thing a user might do, and how often relative to the others.
 * Most scenarios are a single step; the checkout scenario is three chained ones.
 */
public record Scenario(String name, int weight, Function<Random, List<Step>> build) {

    public List<Step> steps(Random random) {
        return build.apply(random);
    }
}
