package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.ResourceNotFoundException;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.LogEntry;
import com.pessimisticit.scarcity.world.engine.LoggedDraw;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

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
    private final TurnOutbox turnOutbox;

    /** An area repository with no intelligence services: no turn is ever dispatched. */
    public AreaRepository(ResourceStore resourceStore, ChangePublisher changePublisher, JdbcTemplate jdbc, LogSettings logSettings) {
        this(resourceStore, changePublisher, jdbc, logSettings, TurnOutbox.none(jdbc));
    }

    /** @param turnOutbox where every commit writes or clears its area's turn (docs/actions.md, "Intelligence services") */
    public AreaRepository(ResourceStore resourceStore, ChangePublisher changePublisher, JdbcTemplate jdbc,
            LogSettings logSettings, TurnOutbox turnOutbox) {
        this.resourceStore = resourceStore;
        this.changePublisher = changePublisher;
        this.logTable = new LogTable(jdbc, logSettings.retention());
        this.resourceWrites = new ResourceWrites(resourceStore);
        this.turnOutbox = turnOutbox;
    }

    /**
     * The area, its entities, their items, its cells and the log roots of their actions; walls come resolved against
     * their cells. The resources come without {@code children}:
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
        return new AreaSnapshot(area, entities, items(areaId), logTable.roots(areaId, actionSeqs(entities)), cells(areaId));
    }

    private SortedMap<String, Cell> cells(String areaId) {
        SortedMap<String, Cell> cells = new TreeMap<>();
        children(areaId, ResourceKind.CELL).forEach(r -> cells.put(r.id(), (Cell) r));
        return cells;
    }

    private SortedMap<String, List<Item>> items(String areaId) {
        SortedMap<String, List<Item>> items = new TreeMap<>();
        resourceStore.inArea(areaId, ResourceKind.ITEM).forEach((holder, list) ->
                items.put(holder, list.stream().map(r -> ((Item) r).<Item>withChildren(null)).toList()));
        return items;
    }

    private List<Entity> entities(String areaId) {
        List<Entity> entities = new ArrayList<>();
        children(areaId, ResourceKind.ENTITY).forEach(e -> entities.add(((Entity) e).withChildren(null)));
        return entities;
    }

    /** Every child of {@code kind}, all pages. */
    private List<Resource> children(String areaId, ResourceKind kind) {
        List<Resource> found = new ArrayList<>();
        int page = 0;
        Page<Resource> p;
        do {
            p = resourceStore.children(areaId, kind, page, ResourceStore.MAX_SIZE);
            found.addAll(p.items());
            page++;
        } while ((long) page * ResourceStore.MAX_SIZE < p.total());
        return found;
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
     * when one was due, the entries, the head, pruning after a snapshot, and the area's turn in the
     * {@link TurnOutbox}.
     *
     * @param traceparent the advance's span, for the batch; null: none
     * @throws IllegalStateException if the result doesn't follow the head, or
     *     the store writes a version the engine didn't expect
     */
    public void commit(AreaHead head, AreaSnapshot snapshot, AdvanceResult result, String traceparent) {
        commit(head, snapshot, result, traceparent, true);
    }

    /**
     * {@link #commit(AreaHead, AreaSnapshot, AdvanceResult, String)}, publishing its batch only if
     * {@code publish}: a crossing publishes a reload for the area instead ({@link #publishReload}).
     */
    public void commit(AreaHead head, AreaSnapshot snapshot, AdvanceResult result, String traceparent,
            boolean publish) {
        String areaId = head.areaId();
        checkFollows(head, snapshot, result);
        List<Change> changes = resourceWrites.write(snapshot, result);
        if (publish) {
            changePublisher.publish(ChangeBatch.of(areaId, changes, head.lastSeq(), result.lastSeq(), traceparent));
        }
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
        turnOutbox.offer(areaId, result.area());
    }

    /**
     * Offers {@code area}, as stored, to the {@link TurnOutbox} without an advance: a turn stored
     * before it could be dispatched (world's startup sweep). The caller holds the area's lock.
     */
    public void offerTurn(String areaId, Area area) {
        turnOutbox.offer(areaId, area);
    }

    /**
     * Begins the log of an area that generation just made (docs/log.md, {@code snapshot}): its first
     * snapshot, with {@code generation} and every draw that made it. Nothing is published; the caller
     * takes the lock again ({@link AreaLock#begin}) for anything it runs next.
     *
     * @throws IllegalStateException if the area already has a log
     */
    public void startLog(AreaHead head, AreaSnapshot snapshot, ObjectNode generation, List<LoggedDraw> draws) {
        if (head.lastSeq() != 0) {
            throw new IllegalStateException(head.areaId() + " already has a log, up to " + head.lastSeq());
        }
        logTable.append(head.areaId(), head.now(),
                List.of(LogEntry.snapshot(head.snapshotSeq(), snapshot, generation, draws)));
        logTable.saveHead(head.areaId(), head.snapshotSeq(), head.now());
    }

    /**
     * Tells subscribers to load {@code areaId} afresh (docs/resources.md, "Reload"): it was made in this
     * transaction, or an entity crossed into it. {@code logSeq} is its log head after the transaction's writes.
     */
    public void publishReload(String areaId, long logSeq, String traceparent) {
        changePublisher.publish(ChangeBatch.reload(areaId, logSeq, traceparent));
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
