package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Draws the next choice by weight from the ones that may be made now: among the entity's
 * {@code actions}, weight above 0 and not skipped. A skipped choice (a 422, or
 * {@link Choice.Unavailable}) sits out for {@code skipFor}.
 */
@Service
public class Chooser {

    /** How long a rejected or unavailable choice sits out. */
    static final Duration SKIP_FOR = Duration.ofMinutes(1);

    private final List<Choice> choices;
    private final Map<Choice, Double> weights = new HashMap<>();
    private final Map<Choice, Instant> skippedUntil = new HashMap<>();
    private final RandomGenerator rng;
    private final Clock clock;
    private final Duration skipFor;

    /** Every {@link Choice} bean, weighted by {@code orbital.weights}. */
    @Autowired
    public Chooser(List<Choice> choices, OrbitalProperties props, RandomGenerator rng, Clock clock) {
        this(choices, props.weights(), rng, clock, SKIP_FOR);
    }

    /**
     * @param weights choice name to weight (each {@code >= 0}); every name must be one of
     *                {@code choices}, a missing one is 0
     */
    Chooser(List<Choice> choices, Map<String, Double> weights, RandomGenerator rng, Clock clock,
            Duration skipFor) {
        this.choices = List.copyOf(choices);
        this.rng = rng;
        this.clock = clock;
        this.skipFor = skipFor;
        Map<String, Choice> byName = new HashMap<>();
        this.choices.forEach(c -> byName.put(c.name(), c));
        weights.forEach((name, weight) -> {
            Choice choice = byName.get(name);
            if (choice == null) {
                throw new IllegalArgumentException("orbital.weights: unknown choice '" + name + "', known "
                        + byName.keySet());
            }
            this.weights.put(choice, weight);
        });
    }

    /** The next pick among {@code actions} (the entity's), or empty if none may be made now. */
    public synchronized Optional<Pick> pick(Collection<String> actions) {
        Instant now = clock.instant();
        List<Choice> open = new ArrayList<>();
        List<Double> openWeights = new ArrayList<>();
        for (Choice choice : choices) {
            double weight = weights.getOrDefault(choice, 0.0);
            Instant until = skippedUntil.get(choice);
            if (weight > 0 && actions.contains(choice.name()) && (until == null || !now.isBefore(until))) {
                open.add(choice);
                openWeights.add(weight);
            }
        }
        if (open.isEmpty()) {
            return Optional.empty();
        }
        Draw.Categorical draw = Draws.categorical(rng, openWeights);
        return Optional.of(new Pick(open.get(draw.value()), draw, open.stream().map(Choice::name).toList()));
    }

    /** {@code choice} sits out for {@code skipFor} from now. */
    public synchronized void skip(Choice choice) {
        skippedUntil.put(choice, clock.instant().plus(skipFor));
    }

    /**
     * A drawn choice.
     *
     * @param draw    the categorical draw over the open choices' weights; {@code value} is
     *                the index into {@code names}
     * @param names   the open choices, in the order of {@code draw.weights}
     */
    public record Pick(Choice choice, Draw.Categorical draw, List<String> names) {
        public Pick {
            names = List.copyOf(names);
        }
    }
}
