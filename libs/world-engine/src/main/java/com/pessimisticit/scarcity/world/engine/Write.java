package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.Objects;

/**
 * One entity, item or cell the advance writes, with its final state.
 *
 * @param parent the entity an item is in; null for an entity (its parent is the area)
 * @param version the version to write: 1 for {@code CREATED}, the
 *     snapshot's + 1 for {@code CHANGED}, null for {@code DESTROYED}
 * @param state null for {@code DESTROYED}
 */
public record Write(String id, Op op, Long version, Resource resource, String parent) {

    /** What happens to the row. */
    public enum Op { CREATED, CHANGED, DESTROYED }

    public Write {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(op, "op");
    }

    /** An entity's write. */
    public Write(String id, Op op, Long version, Entity entity) {
        this(id, op, version, entity, null);
    }

    /** Whether it writes an item (in {@link #parent}), not an entity. */
    public boolean item() {
        return parent != null;
    }

    /** Whether it writes a cell (always a change: a cell's kind, neighbours and clearance). */
    public boolean cell() {
        return id.startsWith(ResourceKind.CELL.prefix() + ":");
    }

    /** The entity written; null for {@code DESTROYED}. */
    public Entity entity() {
        if (item()) {
            throw new IllegalStateException(id + " is an item, in " + parent);
        }
        return (Entity) resource;
    }
}
