package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Check;

/**
 * What a {@code throw} throws (docs/actions.md, "Action types"): the
 * projectile's state without a position, its flight speed, its damage, its
 * break chance {@code p}, and the debris it leaves, which decays.
 */
public record Projectile(
        @JsonProperty(required = true) ObjectNode state,
        @JsonProperty(required = true) double speed,
        @JsonProperty(required = true) DamageRoll damage,
        @JsonProperty(required = true) double p,
        @JsonProperty(required = true) ObjectNode debris,
        @JsonProperty(required = true) Decay.Params decay) {

    public Projectile {
        state = Templates.checked(state, "state");
        Check.positive(speed, "speed");
        Check.required(damage, "damage");
        Check.probability(p, "p");
        debris = Templates.checked(debris, "debris");
        Check.required(decay, "decay");
    }

    /** A copy: the record stays immutable. */
    @Override
    public ObjectNode state() {
        return state.deepCopy();
    }

    /** A copy: the record stays immutable. */
    @Override
    public ObjectNode debris() {
        return debris.deepCopy();
    }
}
