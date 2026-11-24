package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.ChildrenWrite;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.WriteResult;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Write;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One advance's resource writes, each resource once, and the batch's
 * changes (deltas, docs/resources.md "Change notifications") in this order:
 * the area's update (with the ids it gained and lost), then the entities in
 * the result's order (changed by id, created, destroyed), then the items the
 * same way. An update is the difference from the snapshot the advance ran
 * on, which is the stored state at the version before.
 *
 * <p>An entity whose items changed (a stash, docs/resources.md "Items and
 * stashes") gets one {@code applyChildren} for all of it, after the area's, so
 * a new stash exists before its first item; a stash that goes is emptied
 * first, as a resource with children can't be deleted.
 *
 * <p>The engine chose every version; a store that writes another one means
 * someone wrote the area without its lock, and the transaction fails.
 */
final class ResourceWrites {

    private final ResourceStore resourceStore;

    ResourceWrites(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    /** One holder's item creates and deletes. */
    private record Held(List<Resource> creates, List<String> deletes) {
        Held() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    List<Change> write(AreaSnapshot snapshot, AdvanceResult result) {
        Map<String, Resource> before = new HashMap<>();
        snapshot.entities().forEach(e -> before.put(e.id(), e));
        snapshot.items().values().forEach(list -> list.forEach(i -> before.put(i.id(), i)));
        Map<String, Held> held = held(result);
        Set<String> gone = result.writes().stream()
                .filter(w -> !w.item() && w.op() == Write.Op.DESTROYED).map(Write::id).collect(Collectors.toSet());
        held.forEach((holder, h) -> {
            if (gone.contains(holder)) {
                resourceStore.applyChildren(holder, List.of(), h.deletes(), null);
            }
        });
        List<Change> changes = new ArrayList<>();
        changes.add(writeArea(snapshot.area(), result, held.keySet()));
        for (Write w : result.writes()) {
            Held h = held.get(w.id());
            changes.add(h != null && !gone.contains(w.id()) ? holder(w, h, before) : change(w, before));
        }
        checkHolders(result, held, gone);
        return changes;
    }

    /** Each holder's item creates (in order) and deletes, by holder id, in the order first met. */
    private static Map<String, Held> held(AdvanceResult result) {
        Map<String, Held> held = new LinkedHashMap<>();
        for (Write w : result.writes()) {
            if (w.item() && w.op() == Write.Op.CREATED) {
                held.computeIfAbsent(w.parent(), p -> new Held()).creates().add(w.resource());
            } else if (w.item() && w.op() == Write.Op.DESTROYED) {
                held.computeIfAbsent(w.parent(), p -> new Held()).deletes().add(w.id());
            }
        }
        return held;
    }

    /** A holder with no write of its own can't happen: the engine writes every holder whose items changed. */
    private static void checkHolders(AdvanceResult result, Map<String, Held> held, Set<String> gone) {
        for (String holder : held.keySet()) {
            if (!gone.contains(holder) && result.write(holder).isEmpty()) {
                throw new IllegalStateException(holder + "'s items changed but the advance doesn't write it");
            }
        }
    }

    private Change writeArea(Area before, AdvanceResult result, Set<String> holders) {
        String areaId = before.id();
        Area area = result.area();
        long version;
        if (result.created().isEmpty() && result.destroyed().isEmpty()) {
            version = resourceStore.update(area).version();
        } else {
            ChildrenWrite cw = resourceStore.applyChildren(areaId, creates(result, holders), result.destroyed(), area);
            version = cw.parentVersion();
        }
        expect(areaId, result.areaVersion(), version);
        return Change.update(before, area.withVersion(version), result.created(), result.destroyed());
    }

    /**
     * The new entities, at the version they are inserted at: one less than the engine's for a holder of
     * new items, which its own {@code applyChildren} bumps after.
     */
    private static List<Resource> creates(AdvanceResult result, Set<String> holders) {
        return result.writes().stream()
                .filter(w -> w.op() == Write.Op.CREATED && !w.item())
                .map(w -> holders.contains(w.id()) ? w.resource().<Resource>withVersion(w.version() - 1) : w.resource())
                .toList();
    }

    /**
     * An entity whose items changed: its items and, for a changed one, its state, with one bump. A new
     * one was inserted with the area at version 1, and this makes it the version the engine chose.
     */
    private Change holder(Write w, Held h, Map<String, Resource> before) {
        Resource state = w.op() == Write.Op.CHANGED ? w.resource() : null;
        ChildrenWrite cw = resourceStore.applyChildren(w.id(), h.creates(), h.deletes(), state);
        expect(w.id(), w.version(), cw.parentVersion());
        List<String> added = h.creates().stream().map(Resource::id).toList();
        if (w.op() == Write.Op.CREATED) {
            return Change.created(w.id(), ResourceKind.ENTITY, w.version());
        }
        return Change.update(old(w, before), w.resource().withVersion(cw.parentVersion()), added, h.deletes());
    }

    /**
     * The change for one entity or item that isn't a holder of changed items; a changed one is written
     * here (created and destroyed ones were, with their parent).
     */
    private Change change(Write w, Map<String, Resource> before) {
        ResourceKind kind = w.item() ? ResourceKind.ITEM : ResourceKind.ENTITY;
        return switch (w.op()) {
            case DESTROYED -> Change.deleted(w.id(), kind);
            case CREATED -> Change.created(w.id(), kind, w.version());
            case CHANGED -> {
                WriteResult written = resourceStore.update(w.resource());
                expect(w.id(), w.version(), written.version());
                yield Change.update(old(w, before), w.resource().withVersion(written.version()), null, null);
            }
        };
    }

    private static Resource old(Write w, Map<String, Resource> before) {
        Resource old = before.get(w.id());
        if (old == null) {
            throw new IllegalStateException(w.id() + " changed but was not in the snapshot");
        }
        return old;
    }

    private static void expect(String id, long expected, long written) {
        if (expected != written) {
            throw new IllegalStateException(id + ": the advance expected version " + expected + " but the store wrote "
                    + written + "; was the area written without its lock?");
        }
    }
}
