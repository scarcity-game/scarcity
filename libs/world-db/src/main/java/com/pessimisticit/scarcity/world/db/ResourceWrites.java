package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.ChildrenWrite;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.WriteResult;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Write;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One advance's resource writes, each resource once, and the batch's
 * changes (deltas, docs/resources.md "Change notifications") in this order:
 * the area's update (with the ids it gained and lost), then the entities in
 * the result's order (changed by id, created, destroyed). An update is the
 * difference from the snapshot the advance ran on, which is the stored state
 * at the version before.
 *
 * <p>The engine chose every version; a store that writes another one means
 * someone wrote the area without its lock, and the transaction fails.
 */
final class ResourceWrites {

    private final ResourceStore resourceStore;

    ResourceWrites(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    List<Change> write(AreaSnapshot snapshot, AdvanceResult result) {
        Map<String, Entity> before = new HashMap<>();
        snapshot.entities().forEach(e -> before.put(e.id(), e));
        List<Change> changes = new ArrayList<>();
        changes.add(writeArea(snapshot.area(), result));
        for (Write w : result.writes()) {
            changes.add(change(w, before));
        }
        return changes;
    }

    private Change writeArea(Area before, AdvanceResult result) {
        String areaId = before.id();
        Area area = result.area();
        long version;
        if (result.created().isEmpty() && result.destroyed().isEmpty()) {
            version = resourceStore.update(area).version();
        } else {
            ChildrenWrite cw = resourceStore.applyChildren(areaId, creates(result), result.destroyed(), area);
            version = cw.parentVersion();
        }
        expect(areaId, result.areaVersion(), version);
        return Change.update(before, area.withVersion(version), result.created(), result.destroyed());
    }

    private static List<Resource> creates(AdvanceResult result) {
        return result.writes().stream()
                .filter(w -> w.op() == Write.Op.CREATED)
                .<Resource>map(Write::entity)
                .toList();
    }

    /** The change for one entity; a changed one is written here (created and destroyed ones were, with the area). */
    private Change change(Write w, Map<String, Entity> before) {
        return switch (w.op()) {
            case DESTROYED -> Change.deleted(w.id(), ResourceKind.ENTITY);
            case CREATED -> Change.created(w.id(), ResourceKind.ENTITY, w.version());
            case CHANGED -> {
                WriteResult written = resourceStore.update(w.entity());
                expect(w.id(), w.version(), written.version());
                Entity old = before.get(w.id());
                if (old == null) {
                    throw new IllegalStateException(w.id() + " changed but was not in the snapshot");
                }
                yield Change.update(old, w.entity().withVersion(written.version()), null, null);
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
