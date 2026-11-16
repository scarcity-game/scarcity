package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Objects;

/** The entity an action runs on: its id, its state and the area around it. */
public record Actor(String id, Entity entity, AreaView areaView) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entity, "state");
        Objects.requireNonNull(areaView, "area");
    }
}
