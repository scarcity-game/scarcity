package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * What the caller (#112) will do around the engine, in memory: hold the
 * area, apply each result as the next snapshot, keep the log and the roots,
 * and check {@link Invariants} after every advance.
 */
final class Store {

    private final Catalogue catalogue;
    private Area area;
    private final SortedMap<String, Entity> entities;
    private final Map<Long, Long> roots;
    private final List<LogEntry> log;
    private long nextSeq;

    Store(Area area, Catalogue catalogue) {
        this.entities = new TreeMap<>();
        this.roots = new HashMap<>();
        this.log = new ArrayList<>();
        this.nextSeq = 1;
        this.area = area;
        this.catalogue = catalogue;
    }

    Store(Area area) {
        this(area, Catalogue.standard());
    }

    Store put(String id, Entity state) {
        entities.put(id, state.withId(id).withVersion(1));
        return this;
    }

    /** Continue an older log: the next entry is {@code seq}, and {@code roots} are known. */
    Store logFrom(long seq, Map<Long, Long> knownRoots) {
        nextSeq = seq;
        roots.putAll(knownRoots);
        return this;
    }

    AreaSnapshot snapshot() {
        return new AreaSnapshot(area, List.copyOf(entities.values()), roots);
    }

    java.util.Optional<AdvanceResult> refresh(long seq) {
        return Engine.refresh(snapshot(), seq, catalogue);
    }

    AdvanceResult advance(Intent intent, RandomGenerator rng) {
        AreaSnapshot before = snapshot();
        AdvanceResult r = Engine.advance(before, intent, rng, nextSeq, catalogue);
        Invariants.check(before, r, nextSeq);
        apply(r);
        return r;
    }

    private void apply(AdvanceResult r) {
        area = r.area();
        for (Write w : r.writes()) {
            if (w.op() == Write.Op.DESTROYED) {
                entities.remove(w.id());
            } else {
                entities.put(w.id(), w.entity());
            }
        }
        r.log().forEach(e -> roots.put(e.seq(), e.root()));
        log.addAll(r.log());
        nextSeq = r.lastSeq() + 1;
        Invariants.afterApply(area, entities.values());
    }

    Area area() {
        return area;
    }

    Entity state(String id) {
        return entities.get(id);
    }

    long version(String id) {
        return entities.get(id).version();
    }

    boolean has(String id) {
        return entities.containsKey(id);
    }

    SortedMap<String, Entity> entities() {
        return entities;
    }

    List<LogEntry> log() {
        return log;
    }

    long nextSeq() {
        return nextSeq;
    }
}
