package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Every random choice a generator makes, drawn from one source and kept in order with its name
 * (AGENTS.md, "Randomness"), so the making of a level can be logged and replayed.
 */
public final class Recorder {

    /** One draw, by the name of the choice it made ({@code "room[2].x"}). */
    public record Named(String name, Draw draw) {
    }

    private final RandomGenerator rng;
    private final List<Named> draws;

    public Recorder(RandomGenerator rng) {
        this.rng = rng;
        this.draws = new ArrayList<>();
    }

    /** A value uniform in [{@code min}, {@code max}). */
    public double uniform(String name, double min, double max) {
        Draw.Uniform d = Draws.uniform(rng, min, max);
        draws.add(new Named(name, d));
        return d.value();
    }

    /** One of {@code n} options: a uniform draw over [0, n), floored, so the log needs no list of n weights. */
    public int index(String name, int n) {
        if (n < 1) {
            throw new IllegalArgumentException(name + ": nothing to choose from");
        }
        return Math.min(n - 1, (int) Math.floor(uniform(name, 0.0, n)));
    }

    public boolean bernoulli(String name, double p) {
        Draw.Bernoulli d = Draws.bernoulli(rng, p);
        draws.add(new Named(name, d));
        return d.value();
    }

    /** The draws so far, in order. */
    public List<Named> draws() {
        return List.copyOf(draws);
    }
}
