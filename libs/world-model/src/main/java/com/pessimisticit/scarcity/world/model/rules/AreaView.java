package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** A read-only view of an area and its entities, as they are now. */
public interface AreaView {

    String areaId();

    Area area();

    /** The entities of the area, ascending by id (created ones included, destroyed ones not). */
    SortedMap<String, Entity> entities();

    /** The area's items (in its stashes), by id; none unless the view holds them. */
    default SortedMap<String, Item> items() {
        return Collections.emptySortedMap();
    }

    /** The ids of the items {@code parentId} holds, in the order they were added; none unless the view holds items. */
    default List<String> itemsIn(String parentId) {
        return List.of();
    }

    default Item peekItem(String id) {
        Item state = items().get(id);
        if (state == null) {
            throw new IllegalArgumentException("no item " + id + " in " + areaId());
        }
        return state;
    }

    /** The area's cells, by id (docs/resources.md, "Cells"); none unless the view holds them. */
    default SortedMap<String, Cell> cells() {
        return Collections.emptySortedMap();
    }

    default boolean exists(String id) {
        return entities().containsKey(id);
    }

    /**
     * The entity's state.
     *
     * @throws IllegalArgumentException if there is no such entity
     */
    default Entity peek(String id) {
        Entity state = entities().get(id);
        if (state == null) {
            throw new IllegalArgumentException("no entity " + id + " in " + areaId());
        }
        return state;
    }

    static AreaView of(String areaId, Area area, Map<String, Entity> entities) {
        return new Snapshot(areaId, area, entities);
    }

    /**
     * A fixed view with the area's cells, each wall resolved against the cell it names
     * ({@link Entity#resolve}).
     *
     * @throws IllegalArgumentException if a wall names a cell that isn't among {@code cells}
     */
    static AreaView of(String areaId, Area area, Map<String, Entity> entities, Map<String, Cell> cells) {
        Map<String, Entity> resolved = new TreeMap<>();
        entities.forEach((id, e) -> resolved.put(id, resolve(e, cells)));
        return new WithCells(areaId, area, Collections.unmodifiableSortedMap(new TreeMap<>(resolved)),
                Collections.unmodifiableSortedMap(new TreeMap<>(cells)));
    }

    /** {@code entity}, resolved against its cell if it names one. */
    static Entity resolve(Entity entity, Map<String, Cell> cells) {
        if (entity.cell() == null) {
            return entity;
        }
        Cell cell = cells.get(entity.cell());
        if (cell == null) {
            throw new IllegalArgumentException("cell: " + entity.id() + " names " + entity.cell() + ", not in the view");
        }
        return entity.resolve(cell);
    }

    /** A fixed view that holds the area's cells. */
    record WithCells(String areaId, Area area, SortedMap<String, Entity> entities, SortedMap<String, Cell> cells)
            implements AreaView {
    }

    /** A fixed view, for validating a start and for tests. */
    record Snapshot(String areaId, Area area, SortedMap<String, Entity> entities) implements AreaView {

        Snapshot(String areaId, Area area, Map<String, Entity> entities) {
            this(areaId, area, Collections.unmodifiableSortedMap(new TreeMap<>(entities)));
        }
    }
}
