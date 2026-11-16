package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.Objects;

/** The entity an action runs on: its id, its state and the area around it. */
public record Actor(String id, EntityState entityState, AreaView areaView) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entityState, "state");
        Objects.requireNonNull(areaView, "area");
    }
}
