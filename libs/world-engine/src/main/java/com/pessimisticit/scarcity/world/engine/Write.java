package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.Objects;

/**
 * One entity the advance writes, with its final state.
 *
 * @param version the version to write: 1 for {@code CREATED}, the
 *     snapshot's + 1 for {@code CHANGED}, null for {@code DESTROYED}
 * @param state null for {@code DESTROYED}
 */
public record Write(String id, Op op, Long version, EntityState state) {

    /** What happens to the row. */
    public enum Op { CREATED, CHANGED, DESTROYED }

    public Write {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(op, "op");
    }
}
