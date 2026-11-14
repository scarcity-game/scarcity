package com.pessimisticit.scarcity.world.model.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/** A generator that returns scripted uniforms and gaussians, and fails when one runs out. */
final class ScriptedRandom implements RandomGenerator {

    private final Deque<Double> uniforms = new ArrayDeque<>();
    private final Deque<Double> normals = new ArrayDeque<>();

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
        throw new AssertionError("nextLong is not scripted");
    }

    private static double next(Deque<Double> q, String what) {
        if (q.isEmpty()) {
            throw new AssertionError("no scripted " + what + " left");
        }
        return q.removeFirst();
    }
}
