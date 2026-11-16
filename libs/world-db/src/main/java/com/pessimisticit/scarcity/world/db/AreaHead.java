package com.pessimisticit.scarcity.world.db;

import java.time.Instant;
import java.util.Objects;

/**
 * An area's log head, locked by {@link AreaLock#begin} for the rest of the
 * caller's transaction: what the advance needs to know before it runs.
 *
 * @param lastSeq the last seq the log handed out (0: no log yet)
 * @param lastSnapshotAt when the last snapshot was written; null: never
 * @param now the advance's wall time: every entry's {@code wallTime}
 * @param snapshotDue whether the commit writes a snapshot entry first (docs/log.md, "Snapshots and pruning")
 */
public record AreaHead(String areaId, long lastSeq, Instant lastSnapshotAt, Instant now, boolean snapshotDue) {

    public AreaHead {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(now, "now");
    }

    /** The seq of the snapshot entry, when one is due. */
    public long snapshotSeq() {
        return lastSeq + 1;
    }

    /** The seq to give the engine: the first after the snapshot, if one is due. */
    public long nextSeq() {
        return snapshotDue ? lastSeq + 2 : lastSeq + 1;
    }
}
