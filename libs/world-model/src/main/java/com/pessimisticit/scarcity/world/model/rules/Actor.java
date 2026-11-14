package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.Objects;

/** The entity an action runs on: its id, its state and the area around it. */
public record Actor(String id, EntityState state, AreaView area) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(area, "area");
    }
}
