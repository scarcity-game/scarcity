package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
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
    private final SortedMap<String, Item> items = new TreeMap<>();
    private final Map<String, String> holderOf = new HashMap<>();
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
        SortedMap<String, List<Item>> held = new TreeMap<>();
        items.forEach((id, item) -> held.computeIfAbsent(holderOf.get(id), h -> new ArrayList<>()).add(item));
        return new AreaSnapshot(area, List.copyOf(entities.values()), held, roots);
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

    /** Warms the area up to {@code until} and applies the result, if anything happened. */
    java.util.Optional<AdvanceResult> warmUp(double until, RandomGenerator rng) {
        AreaSnapshot before = snapshot();
        java.util.Optional<AdvanceResult> r = Engine.warmUp(before, until, rng, nextSeq, catalogue);
        r.ifPresent(result -> {
            Invariants.checkWarmUp(before, result, nextSeq);
            apply(result);
        });
        return r;
    }

    private void apply(AdvanceResult r) {
        area = r.area();
        for (Write w : r.writes()) {
            if (w.item()) {
                applyItem(w);
            } else if (w.op() == Write.Op.DESTROYED) {
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

    private void applyItem(Write w) {
        if (w.op() == Write.Op.DESTROYED) {
            items.remove(w.id());
            holderOf.remove(w.id());
        } else {
            items.put(w.id(), (Item) w.resource());
            holderOf.put(w.id(), w.parent());
        }
    }

    /** The items {@code holder} holds, by id. */
    SortedMap<String, Item> itemsIn(String holder) {
        SortedMap<String, Item> out = new TreeMap<>();
        items.forEach((id, item) -> {
            if (holder.equals(holderOf.get(id))) {
                out.put(id, item);
            }
        });
        return out;
    }

    SortedMap<String, Item> items() {
        return items;
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
