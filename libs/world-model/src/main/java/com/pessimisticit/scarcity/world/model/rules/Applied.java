package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Objects;

/**
 * What {@link Action#apply} did: the entity's new state and the log entry's
 * {@code result} (null: none).
 */
public record Applied(Entity entity, Record result) {

    public Applied {
        Objects.requireNonNull(entity, "state");
    }

    public static Applied of(Entity entity) {
        return new Applied(entity, null);
    }
}
