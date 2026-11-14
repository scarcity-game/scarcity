package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * How an entity picks its next action by itself (docs/actions.md,
 * "Behaviours"). The JSON {@code kind} selects the implementation; nothing
 * else looks at it. A new kind is one more record here.
 *
 * <p>Only the shape is checked when a behaviour is read. Whether it can start
 * (weights positive and finite, at least one choice, types listed in the
 * entity's {@code actions}) is decided when it is used: an invalid behaviour
 * starts nothing, it doesn't make the entity unreadable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Behaviour.Repeat.class, name = "repeat"),
    @JsonSubTypes.Type(value = Behaviour.Random.class, name = "random")
})
public sealed interface Behaviour permits Behaviour.Repeat, Behaviour.Random {

    /**
     * The next action to start. Whether its type is in the entity's
     * {@code actions} and its params are valid is the catalogue's check.
     *
     * @throws InvalidAction if this behaviour can't pick (a random one with
     *     no choices, or a weight that isn't positive and finite)
     */
    Pick next(RandomGenerator rng);

    /** A behaviour's choice; {@code draw} is the random pick, null when there was none. */
    record Pick(ActionSpec action, Draw draw) {
        public Pick {
            Check.required(action, "action");
        }
    }

    /** The same action every time; no draw. */
    record Repeat(@JsonProperty(required = true) ActionSpec action) implements Behaviour {
        public Repeat {
            Check.required(action, "action");
        }

        @Override
        public Pick next(RandomGenerator rng) {
            return new Pick(action, null);
        }
    }

    /** One of {@code choices}, with probability weight / sum of weights. */
    record Random(@JsonProperty(required = true) List<Choice> choices) implements Behaviour {
        public Random {
            choices = List.copyOf(Check.required(choices, "choices"));
        }

        @Override
        public Pick next(RandomGenerator rng) {
            Draw.Categorical draw;
            try {
                draw = Draws.categorical(rng, choices.stream().map(Choice::weight).toList());
            } catch (IllegalArgumentException e) {
                throw new InvalidAction("random behaviour: " + e.getMessage(), e);
            }
            return new Pick(choices.get(draw.value()).action(), draw);
        }
    }

    /** One choice of a {@link Random} behaviour. */
    record Choice(
            @JsonProperty(required = true) double weight,
            @JsonProperty(required = true) ActionSpec action) {
        public Choice {
            Check.required(action, "action");
        }
    }
}
