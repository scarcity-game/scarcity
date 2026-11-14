package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

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

    /** The same action every time; no draw. */
    record Repeat(@JsonProperty(required = true) ActionSpec action) implements Behaviour {
        public Repeat {
            Check.required(action, "action");
        }
    }

    /** One of {@code choices}, with probability weight / sum of weights. */
    record Random(@JsonProperty(required = true) List<Choice> choices) implements Behaviour {
        public Random {
            choices = List.copyOf(Check.required(choices, "choices"));
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
