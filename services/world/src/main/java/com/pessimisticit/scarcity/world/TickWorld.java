package com.pessimisticit.scarcity.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Per-tick data holder.
 */
final class TickWorld {
    final String areaId;
    final ObjectNode areaState;       // mutable
    boolean areaDirty;
    final Map<String, ObjectNode> entities;   // the entities of the area by id (a TreeMap, ascending id); created ones are added, destroyed ones removed
    final Set<String> dirty = new HashSet<>();               // existing entities to write
    final Set<String> blocked = new HashSet<>();
    final Set<String> created = new LinkedHashSet<>();       // created this tick, in creation order (includes those destroyed later)
    final Set<String> destroyed = new HashSet<>();           // destroyed this tick (includes those created this tick)
    final Map<String, String> parents = new HashMap<>();     // created id -> parent id
    private int events;

    TickWorld(String areaId, ObjectNode areaState, Map<String, ObjectNode> entities) {
        this.areaId = areaId;
        this.areaState = areaState;
        this.entities = entities;
    }

    /** True if created and destroyed in this tick: never stored. */
    boolean ephemeral(String id) { return created.contains(id) && destroyed.contains(id); }

    /** Counts one event or effect; throws IllegalStateException("more than 100000 events in one tick") on the 100,001st. */
    void count() { if (++events > Ticker.MAX_EVENTS_PER_TICK) throw new IllegalStateException("more than " + Ticker.MAX_EVENTS_PER_TICK + " events in one tick"); }
}
