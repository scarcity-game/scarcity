package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An entity that doesn't exist yet, as an action or effect names it in its
 * params: a projectile, its debris. It is the {@code prototype}'s name
 * ({@link Prototypes}); {@link #at} makes the idle entity at a position.
 */
public record EntityTemplate(@JsonProperty(required = true) String prototype) {

    public EntityTemplate {
        Check.text(prototype, "prototype");
        Prototypes.get(prototype);
    }

    /** The prototype's entity created at {@code position}, idle. */
    public Entity at(Position position) {
        return Prototypes.get(prototype).withPosition(position);
    }
}
