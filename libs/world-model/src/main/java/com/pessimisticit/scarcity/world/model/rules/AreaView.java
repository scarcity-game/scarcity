package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** A read-only view of an area and its entities, as they are now. */
public interface AreaView {

    String areaId();

    Area area();

    /** The entities of the area, ascending by id (created ones included, destroyed ones not). */
    SortedMap<String, Entity> entities();

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

    /** A fixed view, for validating a start and for tests. */
    record Snapshot(String areaId, Area area, SortedMap<String, Entity> entities) implements AreaView {

        Snapshot(String areaId, Area area, Map<String, Entity> entities) {
            this(areaId, area, Collections.unmodifiableSortedMap(new TreeMap<>(entities)));
        }
    }
}
