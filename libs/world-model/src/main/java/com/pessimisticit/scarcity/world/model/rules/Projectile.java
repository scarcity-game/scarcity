package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.ItemTemplate;

/**
 * What a {@code throw} throws (docs/action-types.md, "Action types"): the
 * projectile's state without a position, its flight speed, its damage, its
 * break chance {@code p}, and the items it lands as: {@code item} whole, {@code debris} broken.
 */
public record Projectile(
        @JsonProperty(required = true) EntityTemplate state,
        @JsonProperty(required = true) double speed,
        @JsonProperty(required = true) DamageRoll damageRoll,
        @JsonProperty(required = true) double p,
        @JsonProperty(required = true) ItemTemplate item,
        @JsonProperty(required = true) ItemTemplate debris) {

    public Projectile {
        Check.required(state, "state");
        Check.positive(speed, "speed");
        Check.required(damageRoll, "damage");
        Check.probability(p, "p");
        Check.required(item, "item");
        Check.required(debris, "debris");
    }
}
