package com.pessimisticit.scarcity.world.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Scripted uniforms and gaussians that fail when one runs out (so a test
 * also proves which draws happened); {@code nextLong}, used for ids, is a
 * seeded generator.
 */
final class ScriptedRandom implements RandomGenerator {

    private final Deque<Double> uniforms;
    private final Deque<Double> normals;
    private final SplittableRandom ids;

    ScriptedRandom() {
        this.uniforms = new ArrayDeque<>();
        this.normals = new ArrayDeque<>();
        this.ids = new SplittableRandom(1);
    }

    ScriptedRandom uniform(double... us) {
        for (double u : us) {
            uniforms.add(u);
        }
        return this;
    }

    ScriptedRandom normal(double... zs) {
        for (double z : zs) {
            normals.add(z);
        }
        return this;
    }

    boolean exhausted() {
        return uniforms.isEmpty() && normals.isEmpty();
    }

    @Override
    public double nextDouble() {
        return next(uniforms, "uniform");
    }

    @Override
    public double nextGaussian() {
        return next(normals, "gaussian");
    }

    @Override
    public long nextLong() {
        return ids.nextLong();
    }

    private static double next(Deque<Double> q, String what) {
        if (q.isEmpty()) {
            throw new AssertionError("no scripted " + what + " left");
        }
        return q.removeFirst();
    }
}
