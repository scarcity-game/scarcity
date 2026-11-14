package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityTemplate;

/**
 * What a {@code throw} throws (docs/actions.md, "Action types"): the
 * projectile's state without a position, its flight speed, its damage, its
 * break chance {@code p}, and the debris it leaves, which decays.
 */
public record Projectile(
        @JsonProperty(required = true) EntityTemplate state,
        @JsonProperty(required = true) double speed,
        @JsonProperty(required = true) DamageRoll damage,
        @JsonProperty(required = true) double p,
        @JsonProperty(required = true) EntityTemplate debris,
        @JsonProperty(required = true) Decay.Params decay) {

    public Projectile {
        Check.required(state, "state");
        Check.positive(speed, "speed");
        Check.required(damage, "damage");
        Check.probability(p, "p");
        Check.required(debris, "debris");
        Check.required(decay, "decay");
    }
}
