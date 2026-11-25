package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Cell;
import java.util.List;

/**
 * What one change to a {@link CellMesh} did, each list ascending by id: cells that changed (a
 * version +1 for the caller), cells it created (no version yet), and the ids of cells it deleted.
 */
public record MeshChange(List<Cell> changed, List<Cell> created, List<String> deleted) {

    /** A change that changed nothing. */
    public static final MeshChange NONE = new MeshChange(List.of(), List.of(), List.of());

    public MeshChange {
        changed = List.copyOf(changed);
        created = List.copyOf(created);
        deleted = List.copyOf(deleted);
    }
}
