package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draw;

/** A damage draw: gaussian {@code mean}, {@code sd}, truncated to [{@code min}, {@code max}). */
public record DamageRoll(
        @JsonProperty(required = true) double mean,
        @JsonProperty(required = true) double sd,
        @JsonProperty(required = true) double min,
        @JsonProperty(required = true) double max) {

    public DamageRoll {
        Check.finite(mean, "mean");
        Check.positive(sd, "sd");
        new Draw.Truncate(min, max);
    }
}
