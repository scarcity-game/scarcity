package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An area and its entities as the caller loaded them, with their versions.
 *
 * @param roots the log {@code root} of each {@code action.seq} in the
 *     entities, so an action's end joins its start's tree. A seq that is
 *     missing (its entry was pruned) is its own root.
 */
public record AreaSnapshot(Area area, List<Entity> entities, Map<Long, Long> roots) {

    public AreaSnapshot {
        Objects.requireNonNull(area, "area");
        entities = List.copyOf(entities);
        roots = Map.copyOf(roots);
        Set<String> ids = new HashSet<>();
        for (Entity e : entities) {
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException("entities: " + e.id() + " is listed twice");
            }
            checkPending(e, area.state().time());
        }
    }

    public static AreaSnapshot of(Area area, List<Entity> entities) {
        return new AreaSnapshot(area, entities, Map.of());
    }

    /** An action that ends before the area's time would make time run backwards. */
    private static void checkPending(Entity e, double time) {
        ActionInProgress a = e.state().action();
        if (a != null && a.end() < time) {
            throw new IllegalArgumentException("entities: " + e.id() + ": action ends at " + a.end()
                    + ", before the area's time " + time);
        }
    }
}
