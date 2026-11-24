package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Draws the next choice by weight. The candidates are the {@link #eligible} ones (among the
 * entity's {@code actions}, weight above 0); {@link Player} plans each and the draw is only among
 * those whose plan is feasible. Nothing is remembered between turns: a choice that was
 * infeasible, or that world refused, is a candidate again on the next turn.
 */
@Service
public class Chooser {

    private final List<Choice> choices;
    private final Map<Choice, Double> weights;
    private final RandomGenerator rng;

    /** Every {@link Choice} bean, weighted by {@code controller.weights}. */
    @Autowired
    public Chooser(List<Choice> choices, ControllerProperties controllerProperties, RandomGenerator rng) {
        this(choices, controllerProperties.weights(), rng);
    }

    /**
     * @param weights choice name to weight (each {@code >= 0}); every name must be one of
     *                {@code choices}, a missing one is 0
     */
    public Chooser(List<Choice> choices, Map<String, Double> weights, RandomGenerator rng) {
        this.weights = new HashMap<>();
        this.choices = List.copyOf(choices);
        this.rng = rng;
        Map<String, Choice> byName = new HashMap<>();
        this.choices.forEach(c -> byName.put(c.name(), c));
        weights.forEach((name, weight) -> {
            Choice choice = byName.get(name);
            if (choice == null) {
                throw new IllegalArgumentException("controller.weights: unknown choice '" + name + "', known "
                        + byName.keySet());
            }
            this.weights.put(choice, weight);
        });
    }

    /** The choices an entity with {@code actions} may take at all: among them, weight above 0, in order. */
    public List<Choice> eligible(Collection<String> actions) {
        List<Choice> eligible = new ArrayList<>();
        for (Choice choice : choices) {
            if (weights.getOrDefault(choice, 0.0) > 0 && actions.contains(choice.name())) {
                eligible.add(choice);
            }
        }
        return List.copyOf(eligible);
    }

    /** The next pick among the {@link #eligible} choices for {@code actions}, or empty if there are none. */
    public Optional<Pick> pick(Collection<String> actions) {
        return pick(actions, choice -> true);
    }

    /**
     * The next pick among the {@link #eligible} choices for {@code actions} that {@code feasible}
     * accepts, or empty if none is.
     */
    public synchronized Optional<Pick> pick(Collection<String> actions, Predicate<Choice> feasible) {
        List<Choice> open = new ArrayList<>();
        List<Double> openWeights = new ArrayList<>();
        for (Choice choice : eligible(actions)) {
            if (feasible.test(choice)) {
                open.add(choice);
                openWeights.add(weights.get(choice));
            }
        }
        if (open.isEmpty()) {
            return Optional.empty();
        }
        Draw.Categorical draw = Draws.categorical(rng, openWeights);
        return Optional.of(new Pick(open.get(draw.value()), draw, open.stream().map(Choice::name).toList()));
    }

    /**
     * A drawn choice.
     *
     * @param draw    the categorical draw over the feasible choices' weights; {@code value} is
     *                the index into {@code names}
     * @param names   the feasible choices, in the order of {@code draw.weights}
     */
    public record Pick(Choice choice, Draw.Categorical draw, List<String> names) {
        public Pick {
            names = List.copyOf(names);
        }
    }
}
