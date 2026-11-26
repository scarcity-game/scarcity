package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
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

    /** One of {@code weights.size()} options, each as likely as its weight: a categorical draw. */
    public int categorical(String name, List<Double> weights) {
        Draw.Categorical d = Draws.categorical(rng, weights);
        draws.add(new Named(name, d));
        return d.value();
    }

    public boolean bernoulli(String name, double p) {
        Draw.Bernoulli d = Draws.bernoulli(rng, p);
        draws.add(new Named(name, d));
        return d.value();
    }

    /**
     * A fresh cell id from the same source. Ids are not choices: they are not recorded as draws, and
     * a level draws them after all its choices, so a seed's choices are the same with or without them.
     */
    public String newCellId() {
        return Ids.newId(ResourceKind.CELL, rng);
    }

    /** The draws so far, in order. */
    public List<Named> draws() {
        return List.copyOf(draws);
    }
}
