package com.pessimisticit.scarcity.world.model;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Random draws that return their own record (docs/actions.md, "Behaviours"
 * and "Effect types"). The caller logs the record with what it was for.
 */
public final class Draws {

    private static final int MAX_ATTEMPTS = 10_000;

    private Draws() {
    }

    /** {@code mean + sd * N(0, 1)}; {@code sd} may be 0. */
    public static Draw.Gaussian gaussian(RandomGenerator rng, double mean, double sd) {
        Check.finite(mean, "mean");
        Check.nonNegative(sd, "sd");
        return new Draw.Gaussian(mean, sd, null, mean + sd * rng.nextGaussian());
    }

    /**
     * A gaussian truncated to {@code [min, max)} by rejection: only the
     * accepted value is drawn into the record.
     */
    public static Draw.Gaussian truncatedGaussian(RandomGenerator rng, double mean, double sd, double min, double max) {
        Check.finite(mean, "mean");
        Check.positive(sd, "sd");
        Draw.Truncate truncate = new Draw.Truncate(min, max);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            double value = mean + sd * rng.nextGaussian();
            if (value >= min && value < max) {
                return new Draw.Gaussian(mean, sd, truncate, value);
            }
        }
        throw new IllegalStateException("truncated gaussian: no value in [" + min + ", " + max + ") after "
                + MAX_ATTEMPTS + " draws");
    }

    /** True when {@code u < p}, {@code u} uniform in [0, 1). */
    public static Draw.Bernoulli bernoulli(RandomGenerator rng, double p) {
        Check.probability(p, "p");
        double u = rng.nextDouble();
        return new Draw.Bernoulli(p, u, u < p);
    }

    /** Picks an index with probability weight / sum; the weights must be positive and finite. */
    public static Draw.Categorical categorical(RandomGenerator rng, List<Double> weights) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("weights: at least one is needed");
        }
        weights.forEach(w -> Check.positive(w, "weights"));
        double u = rng.nextDouble();
        return new Draw.Categorical(weights, u, pick(weights, u));
    }

    /**
     * The first index whose cumulative weight divided by the sum is greater
     * than {@code u}. Assumes positive finite weights and {@code u} in [0, 1).
     */
    public static int pick(List<Double> weights, double u) {
        double total = 0.0;
        for (double w : weights) {
            total += w;
        }
        double cumulative = 0.0;
        for (int i = 0; i < weights.size(); i++) {
            cumulative += weights.get(i);
            if (cumulative / total > u) {
                return i;
            }
        }
        return weights.size() - 1;
    }
}
