package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Objects;

/**
 * What {@link Action#progress} did: the entity's new state, the
 * {@code action-progress} entry's {@code result} (null: none), and whether the
 * action was stopped and so ends now.
 */
public record Progress(Entity entity, Record result, boolean stopped) {

    public Progress {
        Objects.requireNonNull(entity, "entity");
    }
}
