package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Area;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one advance did, for the caller to commit in one transaction.
 *
 * @param area the area's new state (its {@code time} is the stop point)
 * @param areaVersion the area's version to write: the snapshot's + 1 (every advance writes the area)
 * @param writes each entity written once: changed ones by id, then created
 *     ones in the order they were made, then destroyed ones by id. One
 *     created and destroyed in the advance isn't here; it is only in the log
 * @param log the entries, seq from {@code nextSeq} with no gaps, versions resolved
 * @param blocked entities whose behaviour couldn't start in this advance,
 *     with the reason (for the caller's warning); they start nothing
 */
public record AdvanceResult(
        Area area,
        long areaVersion,
        List<Write> writes,
        List<LogEntry> log,
        Map<String, String> blocked) {

    public AdvanceResult {
        writes = List.copyOf(writes);
        log = List.copyOf(log);
        blocked = Map.copyOf(blocked);
    }

    /** The seq of the last entry; the caller's {@code logSeq}. */
    public long lastSeq() {
        return log.getLast().seq();
    }

    /** The ids added to the area's children, in order. */
    public List<String> created() {
        return ids(Write.Op.CREATED);
    }

    /** The ids removed from the area's children. */
    public List<String> destroyed() {
        return ids(Write.Op.DESTROYED);
    }

    public Optional<Write> write(String id) {
        return writes.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    private List<String> ids(Write.Op op) {
        return writes.stream().filter(w -> w.op() == op).map(Write::id).toList();
    }
}
