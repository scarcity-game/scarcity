package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Departure;
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

import java.util.random.RandomGenerator;

/** The area during one advance: every entity's current state and what happened to each. */
final class AreaRun implements AreaView {

    private final String areaId;
    private Area area;
    private final SortedMap<String, Entity> entities;
    private final SortedMap<String, Entity> view;
    private final Map<String, Long> versions;
    private final SortedSet<String> touched;
    private final Set<String> created;
    private final Set<String> destroyed;
    private final List<Departure> departures;
    private final Holdings holdings;
    private final Map<String, String> storedHolders;
    private final SortedMap<String, Cell> cells;
    private final SortedMap<String, Cell> cellView;
    private final Map<String, Long> cellVersions;
    private final SortedSet<String> touchedCells;

    AreaRun(AreaSnapshot areaSnapshot) {
        this.entities = new TreeMap<>();
        this.view = Collections.unmodifiableSortedMap(entities);
        this.versions = new HashMap<>();
        this.touched = new TreeSet<>();
        this.created = new LinkedHashSet<>();
        this.destroyed = new HashSet<>();
        this.departures = new ArrayList<>();
        this.areaId = areaSnapshot.area().id();
        this.area = areaSnapshot.area();
        for (Entity e : areaSnapshot.entities()) {
            entities.put(e.id(), e);
            versions.put(e.id(), e.version());
        }
        this.holdings = new Holdings(areaSnapshot.items());
        this.storedHolders = Holdings.holders(areaSnapshot.items());
        this.cells = new TreeMap<>(areaSnapshot.cells());
        this.cellView = Collections.unmodifiableSortedMap(cells);
        this.cellVersions = new HashMap<>();
        areaSnapshot.cells().values().forEach(c -> cellVersions.put(c.id(), c.version()));
        this.touchedCells = new TreeSet<>();
    }

    @Override
    public SortedMap<String, Cell> cells() {
        return cellView;
    }

    /** Replaces a cell's state; it will be written. */
    void putCell(Cell state) {
        if (!cells.containsKey(state.id())) {
            throw new IllegalArgumentException("cell: no " + state.id() + " in " + areaId);
        }
        cells.put(state.id(), state);
        touchedCells.add(state.id());
    }

    @Override
    public SortedMap<String, Item> items() {
        return holdings.items();
    }

    @Override
    public List<String> itemsIn(String parentId) {
        return holdings.in(parentId);
    }

    /** Replaces an item's state; it will be written. */
    void putItem(String id, Item state) {
        holdings.put(id, state);
    }

    /** Adds an item to the entity {@code parent}, with a fresh id drawn from {@code rng}. */
    String createItem(RandomGenerator rng, String parent, Item state) {
        peek(parent);
        return holdings.create(rng, parent, state);
    }

    /** Removes an item and returns the entity that held it. */
    String destroyItem(String id) {
        return holdings.destroy(id);
    }

    /** The entity that holds the item. */
    String holderOf(String id) {
        return holdings.holderOf(id);
    }

    /** Whether the entity holds any item. */
    boolean holdsItems(String id) {
        return holdings.holds(id);
    }

    @Override
    public String areaId() {
        return areaId;
    }

    @Override
    public Area area() {
        return area;
    }

    @Override
    public SortedMap<String, Entity> entities() {
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
    void put(String id, Entity state) {
        peek(id);
        entities.put(id, state);
        touched.add(id);
    }

    /**
     * Adds an entity: with its own id if it has one (one that arrives keeps its id), else with a fresh
     * id drawn from {@code rng} (docs/action-types.md, "Effects").
     */
    String create(RandomGenerator rng, String parent, Entity state) {
        if (!areaId.equals(parent)) {
            throw new IllegalArgumentException("parent: entities are created in the area " + areaId
                    + ", not in " + parent);
        }
        String id = state.id() != null ? state.id() : Ids.newId(ResourceKind.ENTITY, rng);
        while (state.id() == null && taken(id)) {
            id = Ids.newId(ResourceKind.ENTITY, rng);
        }
        if (taken(id)) {
            throw new IllegalArgumentException("entity: " + id + " is already in " + areaId);
        }
        entities.put(id, state);
        created.add(id);
        return id;
    }

    private boolean taken(String id) {
        return entities.containsKey(id) || destroyed.contains(id) || versions.containsKey(id);
    }

    void depart(Departure departure) {
        departures.add(departure);
    }

    List<Departure> departures() {
        return List.copyOf(departures);
    }

    /** Removes an entity, which must hold no items, and returns its last state. */
    Entity destroy(String id) {
        Entity state = peek(id);
        if (holdings.holds(id)) {
            throw new IllegalStateException(id + " still holds items " + holdings.in(id));
        }
        entities.remove(id);
        destroyed.add(id);
        return state;
    }

    /**
     * The game needs a decision (docs/actions.md, "Game time"): a controlled entity is idle, none
     * is left, or no player or npc is here any more, so the area stops.
     */
    boolean needsDecision() {
        return !occupied() || entities.values().stream().noneMatch(e -> e.controller() != null) || anyIdle();
    }

    /** A controlled entity is idle: someone has to decide. */
    boolean anyIdle() {
        return entities.values().stream().anyMatch(e -> e.controller() != null && e.idle());
    }

    /** A player or npc is here, or arriving: only then does the area run and give turns (docs/actions.md, "Turns"). */
    boolean occupied() {
        return entities.values().stream().anyMatch(Entity::occupies);
    }

    /** The version a log change of {@code id} carries: what the advance writes, or null if it writes none. */
    Long writtenVersion(String id) {
        if (id.startsWith(ResourceKind.ITEM.prefix() + ":")) {
            return holdings.writtenVersion(id);
        }
        if (id.startsWith(ResourceKind.CELL.prefix() + ":")) {
            return cellVersions.get(id) + 1;
        }
        if (destroyed.contains(id)) {
            return null;
        }
        return created.contains(id) ? createdVersion(id) + itemBump(id) : versions.get(id) + 1;
    }

    /**
     * One more for an entity made in this advance whose items changed too: it is written at its first
     * version, then its items with one bump (docs/resources.md, "Versions").
     */
    private long itemBump(String id) {
        return holdings.holderChanged(id) ? 1L : 0L;
    }

    /**
     * The first version of an entity made in this advance: 1, or, for one that arrives (it is stored, holding the
     * version it had in the area it left), the next after that (docs/resources.md, "Versions").
     */
    private long createdVersion(String id) {
        Entity state = entities.get(id);
        return state.stored() ? state.version() + 1 : 1L;
    }

    List<Write> writes() {
        List<Write> out = new ArrayList<>();
        SortedSet<String> changed = new TreeSet<>(touched);
        entities.keySet().stream().filter(holdings::holderChanged).forEach(changed::add);
        changed.stream()
                .filter(id -> !created.contains(id) && !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CHANGED, versions.get(id) + 1,
                        entities.get(id).withId(id).withVersion(versions.get(id) + 1))));
        created.stream()
                .filter(id -> !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CREATED, createdVersion(id) + itemBump(id),
                        entities.get(id).withId(id).withVersion(createdVersion(id) + itemBump(id)))));
        destroyed.stream()
                .filter(id -> !created.contains(id))
                .sorted()
                .forEach(id -> out.add(new Write(id, Write.Op.DESTROYED, null, null)));
        touchedCells.forEach(id -> out.add(new Write(id, Write.Op.CHANGED, cellVersions.get(id) + 1,
                cells.get(id).withId(id).<Cell>withVersion(cellVersions.get(id) + 1), null)));
        out.addAll(holdings.writes(storedHolders));
        return out;
    }
}
