package com.pessimisticit.scarcity.world.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.ChildrenWrite;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.resourcemodel.WriteResult;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.Write;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayList;
import java.util.List;

/**
 * One advance's resource writes, each resource once, and the batch's
 * changes in the order docs/resources.md gives: the area (with
 * {@code children} when the list changed), then the entities in the
 * result's order (changed by id, created, destroyed).
 *
 * <p>The engine chose every version; a store that writes another one means
 * someone wrote the area without its lock, and the transaction fails.
 */
final class ResourceWrites {

    private final ResourceStore resourceStore;

    ResourceWrites(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    List<Change> write(String areaId, AdvanceResult result) {
        List<Change> changes = new ArrayList<>();
        changes.add(writeArea(areaId, result));
        for (Write w : result.writes()) {
            changes.add(change(w));
        }
        return changes;
    }

    private Change writeArea(String areaId, AdvanceResult result) {
        ObjectNode state = WorldJson.toObject(result.areaState());
        if (result.created().isEmpty() && result.destroyed().isEmpty()) {
            WriteResult w = resourceStore.updateState(areaId, state);
            expect(areaId, result.areaVersion(), w.version());
            return Change.changed(areaId, ResourceType.AREA, w.version(), state);
        }
        ChildrenWrite cw = resourceStore.applyChildren(areaId, creates(result), result.destroyed(), state);
        expect(areaId, result.areaVersion(), cw.parentVersion());
        return Change.changed(areaId, ResourceType.AREA, cw.parentVersion(), state, cw.children());
    }

    private static List<Resource> creates(AdvanceResult result) {
        return result.writes().stream()
                .filter(w -> w.op() == Write.Op.CREATED)
                .map(w -> new Resource(w.id(), ResourceType.ENTITY, 1, List.of(), WorldJson.toObject(w.entityState())))
                .toList();
    }

    /** The change for one entity; a changed one is written here (created and destroyed ones were, with the area). */
    private Change change(Write w) {
        return switch (w.op()) {
            case DESTROYED -> Change.deleted(w.id(), ResourceType.ENTITY);
            case CREATED -> Change.changed(w.id(), ResourceType.ENTITY, 1, WorldJson.toObject(w.entityState()));
            case CHANGED -> {
                ObjectNode state = WorldJson.toObject(w.entityState());
                WriteResult written = resourceStore.updateState(w.id(), state);
                expect(w.id(), w.version(), written.version());
                yield Change.changed(w.id(), ResourceType.ENTITY, written.version(), state);
            }
        };
    }

    private static void expect(String id, long expected, long written) {
        if (expected != written) {
            throw new IllegalStateException(id + ": the advance expected version " + expected + " but the store wrote "
                    + written + "; was the area written without its lock?");
        }
    }
}
