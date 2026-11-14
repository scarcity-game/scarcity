package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.Objects;

/**
 * What {@link Action#apply} did: the entity's new state and the log entry's
 * {@code result} (null: none).
 */
public record Applied(EntityState state, Record result) {

    public Applied {
        Objects.requireNonNull(state, "state");
    }

    public static Applied of(EntityState state) {
        return new Applied(state, null);
    }
}
