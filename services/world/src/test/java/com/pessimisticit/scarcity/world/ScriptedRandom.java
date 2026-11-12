package com.pessimisticit.scarcity.world;

import java.util.random.RandomGenerator;

public class ScriptedRandom implements RandomGenerator {
    private final double[] values;
    private final boolean constant;
    private int next = 0;
    private int draws = 0;

    public ScriptedRandom(double... values) {
        this(values, false);
    }

    private ScriptedRandom(double[] values, boolean constant) {
        this.values = values;
        this.constant = constant;
    }

    public static ScriptedRandom constant(double v) {
        return new ScriptedRandom(new double[]{v}, true);
    }

    public int draws() {
        return draws;
    }

    @Override
    public double nextDouble() {
        draws++;
        if (constant) {
            return values[0];
        }
        if (next >= values.length) {
            throw new IllegalStateException("script exhausted");
        }
        return values[next++];
    }

    @Override
    public long nextLong() {
        throw new UnsupportedOperationException();
    }
}
