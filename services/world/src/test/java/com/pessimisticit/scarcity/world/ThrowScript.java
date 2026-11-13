package com.pessimisticit.scarcity.world;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A generator for the pebble throw tests: {@code nextDouble} and {@code nextGaussian} each play their own
 * script in order and then return 0.0 forever; {@code nextLong} (used for ids) is a seeded generator.
 */
final class ThrowScript implements RandomGenerator {

    private final Deque<Double> doubles = new ArrayDeque<>();
    private final Deque<Double> gaussians = new ArrayDeque<>();
    private final SplittableRandom ids = new SplittableRandom(1);

    ThrowScript uniform(double... values) {
        for (double v : values) {
            doubles.add(v);
        }
        return this;
    }

    ThrowScript normal(double... values) {
        for (double v : values) {
            gaussians.add(v);
        }
        return this;
    }

    @Override
    public double nextDouble() {
        Double v = doubles.poll();
        return v == null ? 0.0 : v;
    }

    @Override
    public double nextGaussian() {
        Double v = gaussians.poll();
        return v == null ? 0.0 : v;
    }

    @Override
    public long nextLong() {
        return ids.nextLong();
    }
}
