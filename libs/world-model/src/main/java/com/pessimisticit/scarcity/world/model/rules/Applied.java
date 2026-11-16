package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.Objects;

/**
 * What {@link Action#apply} did: the entity's new state and the log entry's
 * {@code result} (null: none).
 */
public record Applied(EntityState entityState, Record result) {

    public Applied {
        Objects.requireNonNull(entityState, "state");
    }

    public static Applied of(EntityState entityState) {
        return new Applied(entityState, null);
    }
}
