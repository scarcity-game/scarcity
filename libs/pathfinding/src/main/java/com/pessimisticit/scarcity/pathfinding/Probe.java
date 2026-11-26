package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Whether the mover can take a leg: {@link Contacts#first} (the check {@code step} is interrupted
 * by) with the mover at the leg's start, moving along it, against some of the area's entities.
 * Not thread-safe: it moves the mover in its own copy of the entities while it checks.
 */
final class Probe {

    private final String moverId;
    private final Entity mover;
    private final double radius;
    private final SortedMap<String, Entity> entities;
    private final AreaView view;

    /** A probe against {@code entities}, which hold the mover. */
    Probe(String areaId, Area area, Map<String, Entity> entities, String moverId) {
        this.moverId = moverId;
        this.entities = new TreeMap<>(entities);
        this.mover = this.entities.get(moverId);
        this.radius = mover.radius() == null ? 0.0 : mover.radius();
        SortedMap<String, Entity> seen = Collections.unmodifiableSortedMap(this.entities);
        this.view = new AreaView() {
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
                return seen;
            }
        };
    }

    /** A probe against every entity of {@code view}. */
    static Probe of(AreaView view, String moverId) {
        return new Probe(view.areaId(), view.area(), view.entities(), moverId);
    }

    /** A probe against the mover and {@code obstacles} only, in the same area. */
    Probe with(List<Entity> obstacles, List<String> ids) {
        Map<String, Entity> some = new TreeMap<>();
        for (int i = 0; i < obstacles.size(); i++) {
            some.put(ids.get(i), obstacles.get(i));
        }
        some.put(moverId, mover);
        return new Probe(view.areaId(), view.area(), some, moverId);
    }

    String moverId() {
        return moverId;
    }

    Entity mover() {
        return mover;
    }

    /** The mover's radius, 0 for one without. */
    double radius() {
        return radius;
    }

    /** The entities checked against, the mover among them, by id. */
    SortedMap<String, Entity> entities() {
        return Collections.unmodifiableSortedMap(entities);
    }

    /**
     * Whether the mover, at {@code a} and moving toward {@code b}, gets there untouched: at its own
     * radius for the {@code last} leg (a target such as stairs may lie nearer a wall than the
     * margin), else at its radius grown by half the {@link Pathfinder#MARGIN}, so a {@code step}
     * along it isn't stopped by a rounding error.
     */
    boolean clear(Position a, Position b, boolean last) {
        return hit(a, b, last) == null;
    }

    /**
     * Whether the mover, at {@code p} with its radius grown by half the {@link Pathfinder#MARGIN},
     * touches no obstacle: a point a leg can end at.
     */
    boolean standable(Position p) {
        double reach = radius + Pathfinder.MARGIN / 2;
        return Pathfinder.obstacles(entities, moverId).allMatch(o -> {
            double dx = o.position().x() - p.x();
            double dy = o.position().y() - p.y();
            return !(dx == 0.0 && dy == 0.0) && o.shape().contact(p, dx, dy, reach) > 0.0;
        });
    }

    /** The entity the mover, at {@code a} and moving toward {@code b}, touches before it gets there (as {@link #clear}), or null. */
    Entity hit(Position a, Position b, boolean last) {
        double length = a.distanceTo(b);
        if (length == 0.0) {
            return null;
        }
        double r = last ? radius : radius + Pathfinder.MARGIN / 2;
        Entity there = mover.withPosition(a);
        entities.put(moverId, r > 0.0 ? there.withRadius(r) : there);
        Motion along = new Motion((b.x() - a.x()) / length, (b.y() - a.y()) / length);
        try {
            Contacts.Contact contact = Contacts.first(view, Map.of(), moverId, along);
            return contact.time() >= length ? null : entities.get(contact.other());
        } finally {
            entities.put(moverId, mover);
        }
    }
}
