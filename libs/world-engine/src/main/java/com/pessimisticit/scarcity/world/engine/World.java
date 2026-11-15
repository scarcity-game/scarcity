package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** The area during one advance: every entity's current state and what happened to each. */
final class World implements AreaView {

    private final String areaId;
    private AreaState area;
    private final SortedMap<String, EntityState> entities = new TreeMap<>();
    private final SortedMap<String, EntityState> view = Collections.unmodifiableSortedMap(entities);
    private final Map<String, Long> versions = new HashMap<>();
    private final SortedSet<String> touched = new TreeSet<>();
    private final Set<String> created = new LinkedHashSet<>();
    private final Set<String> destroyed = new HashSet<>();

    World(AreaSnapshot snapshot) {
        this.areaId = snapshot.area().id();
        this.area = snapshot.area().state();
        for (Entity e : snapshot.entities()) {
            entities.put(e.id(), e.state());
            versions.put(e.id(), e.version());
        }
    }

    @Override
    public String areaId() {
        return areaId;
    }

    @Override
    public AreaState area() {
        return area;
    }

    @Override
    public SortedMap<String, EntityState> entities() {
        return view;
    }

    /** The version the snapshot had; null for an entity created in this advance. */
    Long version(String id) {
        return versions.get(id);
    }

    void moveTo(double time) {
        area = area.withTime(time);
    }

    void setQueue(List<Turn> queue) {
        area = area.withQueue(queue);
    }

    /** Replaces an entity's state; it will be written. */
    void put(String id, EntityState state) {
        peek(id);
        entities.put(id, state);
        touched.add(id);
    }

    /** Adds an entity with a fresh id drawn from {@code rng} (docs/action-types.md, "Effects"). */
    String create(RandomGenerator rng, String parent, EntityState state) {
        if (!areaId.equals(parent)) {
            throw new IllegalArgumentException("parent: entities are created in the area " + areaId
                    + ", not in " + parent);
        }
        String id = newId(rng);
        while (entities.containsKey(id) || destroyed.contains(id) || versions.containsKey(id)) {
            id = newId(rng);
        }
        entities.put(id, state);
        created.add(id);
        return id;
    }

    /** Removes an entity and returns its last state. */
    EntityState destroy(String id) {
        EntityState state = peek(id);
        entities.remove(id);
        destroyed.add(id);
        return state;
    }

    /** A controlled entity is idle, or none is left: the game needs a decision (docs/actions.md, "Game time"). */
    boolean needsDecision() {
        List<EntityState> controlled = entities.values().stream().filter(e -> e.controller() != null).toList();
        return controlled.isEmpty() || controlled.stream().anyMatch(EntityState::idle);
    }

    /** The version a log change of {@code id} carries: what the advance writes, or null if it writes none. */
    Long writtenVersion(String id) {
        if (destroyed.contains(id)) {
            return null;
        }
        return created.contains(id) ? 1L : versions.get(id) + 1;
    }

    List<Write> writes() {
        List<Write> out = new ArrayList<>();
        touched.stream()
                .filter(id -> !created.contains(id) && !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CHANGED, versions.get(id) + 1, entities.get(id))));
        created.stream()
                .filter(id -> !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CREATED, 1L, entities.get(id))));
        destroyed.stream()
                .filter(id -> !created.contains(id))
                .sorted()
                .forEach(id -> out.add(new Write(id, Write.Op.DESTROYED, null, null)));
        return out;
    }

    /** A version 4 UUID from two longs of the area's generator, as world did before (#109). */
    static String newId(RandomGenerator rng) {
        long msb = (rng.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (rng.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return "entity:" + new UUID(msb, lsb);
    }
}
