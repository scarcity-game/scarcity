package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contact forecast (docs/actions.md, "Interruption"): the earliest time at which a moving
 * channel's edge touches another entity's shape or the boundary, from the straight lines and speeds
 * the channels have now. Computed again after every event, so what appears in a mover's way, or a
 * mover that starts toward another, is found at once.
 */
final class Forecast {

    /**
     * An entity's next event. A {@code contact} is the forecast first touch of the moving
     * {@code entity} with {@code other} (null: the boundary), ordered by the lower id of the two.
     */
    record Event(double time, String entity, String other, boolean contact) {
        Event(double time, String entity) {
            this(time, entity, null, false);
        }

        String id() {
            return other == null || entity.compareTo(other) < 0 ? entity : other;
        }

        boolean before(Event event) {
            return event == null || time < event.time || time == event.time && id().compareTo(event.id()) < 0;
        }
    }

    /** The {@code result} of a {@code contact} entry. */
    private record ContactBody(List<String> resources, List<Position> at, double distance) {
    }

    private final AreaRun areaRun;
    private final BiFunction<String, Entity, Optional<Motion>> motionOf;

    Forecast(AreaRun areaRun, BiFunction<String, Entity, Optional<Motion>> motionOf) {
        this.areaRun = areaRun;
        this.motionOf = motionOf;
    }

    /**
     * The earliest contact of the channels that are moving, from where they are as of {@code at};
     * null when none falls before its channel's end.
     */
    Event next(double at) {
        Map<String, Motion> motions = new TreeMap<>();
        Map<String, ActionInProgress> actions = new TreeMap<>();
        for (Map.Entry<String, Entity> e : areaRun.entities().entrySet()) {
            ActionInProgress action = e.getValue().actionInProgress();
            if (action != null && action.timing() == Timing.CHANNEL) {
                motionOf.apply(e.getKey(), e.getValue()).ifPresent(m -> motions.put(e.getKey(), m));
                actions.put(e.getKey(), action);
            }
        }
        Event best = null;
        for (Map.Entry<String, Motion> m : motions.entrySet()) {
            Contacts.Contact c = Contacts.first(areaRun, motions, m.getKey(), m.getValue());
            double time = at + c.time();
            if (time < actions.get(m.getKey()).end() - Contacts.TOUCH) {
                Event event = new Event(time, m.getKey(), c.other(), true);
                best = event.before(best) ? event : best;
            }
        }
        return best;
    }

    /**
     * The {@code contact} entry's result: the resources, where each is, and the distance between
     * them (the sum of the radii, or the mover's radius for a polygon).
     */
    ObjectNode result(Event event) {
        Entity mover = areaRun.peek(event.entity());
        Entity other = event.other() == null ? null : areaRun.peek(event.other());
        double radius = mover.radius() == null ? 0.0 : mover.radius();
        double reach = other == null ? radius : other.shape().contactDistance(radius);
        List<String> resources = List.of(event.entity(), event.other() == null ? "boundary" : event.other());
        List<Position> at = other == null ? List.of(mover.position()) : List.of(mover.position(), other.position());
        return WorldJson.toObject(new ContactBody(resources, at, reach));
    }
}
