package com.pessimisticit.scarcity.world;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

/**
 * The log entries of one tick. Created by {@link AreaLog#begin}; the Ticker adds entries as events
 * happen and calls {@link #commit} once the resources are written, so the versions are known.
 */
public final class TickLog {

    private final AreaLog areaLog;
    private final String areaId;
    private final AreaLog.Head head;
    private final Instant now;
    private final List<LogEntry> entries = new ArrayList<>();
    private long lastSeq;
    private boolean snapshotWritten;

    TickLog(AreaLog areaLog, String areaId, AreaLog.Head head, Instant now) {
        this.areaLog = areaLog;
        this.areaId = areaId;
        this.head = head;
        this.now = now;
        this.lastSeq = head.lastSeq();
    }

    /** Allocates the next seq. */
    public long nextSeq() {
        return ++lastSeq;
    }

    public boolean snapshotDue() {
        return areaLog.snapshotDue(head, now);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void anchored(String reason, double time, ObjectNode areaState) {
        long seq = nextSeq();
        entries.add(new LogEntry(seq, time, "clock-anchored", null, seq, null, reason, null, noDraws(),
                List.of(new LogChange(areaId, ResourceType.AREA.wire(), "changed", null, areaState.deepCopy()))));
    }

    /** @param areaVersionOrNull null: the version this tick writes for the area (resolved at commit) */
    public void snapshot(double time, Long areaVersionOrNull, ObjectNode areaState,
            Map<String, ObjectNode> entityStates, Map<String, Long> entityVersions) {
        long seq = nextSeq();
        List<LogChange> changes = new ArrayList<>();
        changes.add(new LogChange(areaId, ResourceType.AREA.wire(), "snapshot", areaVersionOrNull,
                areaState.deepCopy()));
        for (Map.Entry<String, ObjectNode> e : new TreeMap<>(entityStates).entrySet()) {
            changes.add(new LogChange(e.getKey(), ResourceType.ENTITY.wire(), "snapshot",
                    entityVersions.get(e.getKey()), e.getValue().deepCopy()));
        }
        entries.add(new LogEntry(seq, time, "snapshot", null, seq, null, null, null, noDraws(), changes));
        snapshotWritten = true;
    }

    /** @param seq allocated earlier with {@link #nextSeq()} */
    public void actionStart(long seq, double time, String entityId, ObjectNode action, ArrayNode draws,
            ObjectNode entityState) {
        entries.add(new LogEntry(seq, time, "action-start", null, seq, entityId, null, stripped(action), draws,
                List.of(entityChange(entityId, entityState))));
    }

    public void actionEnd(Long causeSeq, double time, String entityId, ObjectNode action, ObjectNode entityState) {
        long seq = nextSeq();
        entries.add(new LogEntry(seq, time, "action-end", causeSeq, causeSeq != null ? causeSeq : seq, entityId,
                null, stripped(action), noDraws(), List.of(entityChange(entityId, entityState))));
    }

    /** Resolves the versions the tick wrote and stores the entries; a tick without entries writes nothing. */
    public void commit(String area, Map<String, Long> writtenVersions) {
        for (LogEntry e : entries) {
            for (LogChange c : e.changes) {
                if (c.version == null) {
                    Long v = writtenVersions.get(c.id);
                    if (v == null) {
                        throw new IllegalStateException("log entry " + e.seq + " lists " + c.id
                                + " but the tick wrote no version of it");
                    }
                    c.version = v;
                }
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        areaLog.append(area, now, entries);
        areaLog.saveHead(area, lastSeq, snapshotWritten ? now : head.lastSnapshotAt());
        if (snapshotWritten) {
            areaLog.prune(area, now);
        }
    }

    private static LogChange entityChange(String id, ObjectNode state) {
        return new LogChange(id, ResourceType.ENTITY.wire(), "changed", null, state.deepCopy());
    }

    private static ObjectNode stripped(JsonNode action) {
        ObjectNode copy = action.deepCopy();
        copy.remove("draw");
        return copy;
    }

    private static ArrayNode noDraws() {
        return JsonNodeFactory.instance.arrayNode();
    }
}
