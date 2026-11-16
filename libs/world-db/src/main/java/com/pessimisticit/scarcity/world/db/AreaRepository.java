package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.ResourceNotFoundException;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.LogEntry;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loads an area into model objects and commits an advance of it
 * (docs/resources.md, docs/log.md). Joins the caller's transaction and
 * starts none; the caller holds the area's lock ({@link AreaLock#begin})
 * for both.
 */
public final class AreaRepository {

    private final ResourceStore resourceStore;
    private final ChangePublisher changePublisher;
    private final LogTable logTable;
    private final ResourceWrites resourceWrites;

    public AreaRepository(ResourceStore resourceStore, ChangePublisher changePublisher, JdbcTemplate jdbc, LogSettings logSettings) {
        this.resourceStore = resourceStore;
        this.changePublisher = changePublisher;
        this.logTable = new LogTable(jdbc, logSettings.retention());
        this.resourceWrites = new ResourceWrites(resourceStore);
    }

    /**
     * The area, its entities and the log roots of their actions. The resources come without {@code children}:
     * the engine doesn't use them, and a change says them only when they changed.
     *
     * @throws ResourceNotFoundException if there is no such area
     * @throws com.pessimisticit.scarcity.world.model.WorldJsonException if a state doesn't read
     */
    public AreaSnapshot load(String areaId) {
        Area area = resourceStore.get(areaId).filter(Area.class::isInstance).map(Area.class::cast)
                .map(a -> a.<Area>withChildren(null))
                .orElseThrow(() -> new ResourceNotFoundException(areaId));
        List<Entity> entities = entities(areaId);
        return new AreaSnapshot(area, entities, logTable.roots(areaId, actionSeqs(entities)));
    }

    private List<Entity> entities(String areaId) {
        List<Entity> entities = new ArrayList<>();
        int page = 0;
        Page<Resource> p;
        do {
            p = resourceStore.children(areaId, ResourceKind.ENTITY, page, ResourceStore.MAX_SIZE);
            p.items().forEach(e -> entities.add(((Entity) e).withChildren(null)));
            page++;
        } while ((long) page * ResourceStore.MAX_SIZE < p.total());
        return entities;
    }

    private static Set<Long> actionSeqs(List<Entity> entities) {
        Set<Long> seqs = new TreeSet<>();
        for (Entity e : entities) {
            ActionInProgress a = e.actionInProgress();
            if (a != null && a.seq() != null) {
                seqs.add(a.seq());
            }
        }
        return seqs;
    }

    /**
     * Commits {@code result}, the advance of {@code snapshot} that the engine
     * ran from {@link AreaHead#nextSeq()}: the area written once, each entity
     * once, one {@link ChangeBatch} with {@code logSeq}, the snapshot entry
     * when one was due, the entries, the head, and pruning after a snapshot.
     *
     * @param traceparent the advance's span, for the batch; null: none
     * @throws IllegalStateException if the result doesn't follow the head, or
     *     the store writes a version the engine didn't expect
     */
    public void commit(AreaHead head, AreaSnapshot snapshot, AdvanceResult result, String traceparent) {
        String areaId = head.areaId();
        checkFollows(head, snapshot, result);
        changePublisher.publish(new ChangeBatch(areaId, resourceWrites.write(areaId, result), result.lastSeq(), traceparent));
        List<LogEntry> entries = new ArrayList<>();
        if (head.snapshotDue()) {
            entries.add(LogEntry.snapshot(head.snapshotSeq(), snapshot));
        }
        entries.addAll(result.log());
        logTable.append(areaId, head.now(), entries);
        logTable.saveHead(areaId, result.lastSeq(), head.snapshotDue() ? head.now() : head.lastSnapshotAt());
        if (head.snapshotDue()) {
            logTable.prune(areaId, head.now());
        }
    }

    private static void checkFollows(AreaHead head, AreaSnapshot snapshot, AdvanceResult result) {
        if (!Objects.equals(head.areaId(), snapshot.area().id())) {
            throw new IllegalStateException("the head is " + head.areaId() + "'s, the snapshot "
                    + snapshot.area().id() + "'s");
        }
        long first = result.log().getFirst().seq();
        if (first != head.nextSeq()) {
            throw new IllegalStateException(head.areaId() + ": the advance's log starts at " + first
                    + ", the head's next seq is " + head.nextSeq());
        }
        if (result.areaVersion() != snapshot.area().version() + 1) {
            throw new IllegalStateException(head.areaId() + ": the advance writes version " + result.areaVersion()
                    + " of an area loaded at " + snapshot.area().version());
        }
    }
}
