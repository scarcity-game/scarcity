package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Item;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An area, its entities and their items as the caller loaded them, with their versions.
 *
 * @param items each holder's items (a stash's, docs/resources.md "Items and stashes"), by the
 *     holder's id, in the order they were added; every holder is among the entities
 * @param roots the log {@code root} of each {@code action.seq} in the
 *     entities, so an action's end joins its start's tree. A seq that is
 *     missing (its entry was pruned) is its own root.
 */
public record AreaSnapshot(Area area, List<Entity> entities, SortedMap<String, List<Item>> items,
        Map<Long, Long> roots) {

    public AreaSnapshot {
        Objects.requireNonNull(area, "area");
        entities = List.copyOf(entities);
        SortedMap<String, List<Item>> held = new TreeMap<>();
        items.forEach((holder, list) -> held.put(holder, List.copyOf(list)));
        items = Collections.unmodifiableSortedMap(held);
        roots = Map.copyOf(roots);
        Set<String> ids = new HashSet<>();
        for (Entity e : entities) {
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException("entities: " + e.id() + " is listed twice");
            }
            checkPending(e, area.time());
        }
        for (Map.Entry<String, List<Item>> h : items.entrySet()) {
            if (!ids.contains(h.getKey())) {
                throw new IllegalArgumentException("items: " + h.getKey() + " holds items but isn't among the entities");
            }
            for (Item item : h.getValue()) {
                if (!ids.add(item.id())) {
                    throw new IllegalArgumentException("items: " + item.id() + " is listed twice");
                }
            }
        }
    }

    /** Without items. */
    public AreaSnapshot(Area area, List<Entity> entities, Map<Long, Long> roots) {
        this(area, entities, new TreeMap<>(), roots);
    }

    public static AreaSnapshot of(Area area, List<Entity> entities) {
        return new AreaSnapshot(area, entities, Map.of());
    }

    /** An action that ends before the area's time would make time run backwards. */
    private static void checkPending(Entity e, double time) {
        ActionInProgress a = e.actionInProgress();
        if (a != null && a.end() < time) {
            throw new IllegalArgumentException("entities: " + e.id() + ": action ends at " + a.end()
                    + ", before the area's time " + time);
        }
    }
}
