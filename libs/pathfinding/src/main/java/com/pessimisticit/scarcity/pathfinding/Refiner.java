package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rounds the funnel's bends ({@link Rounding}) until every leg is clear, or it can do no more. The
 * funnel only turns round the corners of openings, and the rounded legs sit a little off its
 * lines, so a leg can still touch a wall: then the wall's corner nearest the leg becomes a circle
 * too, on the side of the leg it is on. An arc that swings into another wall is drawn tighter; an
 * arc that turns back on itself is a bend the path doesn't need, and goes.
 */
final class Refiner {

    private final Probe probe;
    private final double clearance;
    /** The least radius a circle is tightened to: a leg round it is still clear of its corner. */
    private final double tightest;
    /** The centres of the circles removed as backward in this {@link #refine}. */
    private final Set<Position> dropped;

    Refiner(Probe probe) {
        this.probe = probe;
        this.dropped = new HashSet<>();
        this.clearance = probe.radius() + Pathfinder.MARGIN;
        this.tightest = probe.radius() + Pathfinder.TIGHT;
    }

    /** The waypoints after {@code from} round {@code circles} (the first {@code from}, the last the goal). */
    List<Position> refine(Position from, List<Rounding.Circle> circles) {
        List<Rounding.Circle> c = new ArrayList<>(circles);
        Rounding.Rounded rounded = Rounding.round(c);
        for (int round = 0; round < 4 * circles.size() + 16; round++) {
            if (!rounded.backward().isEmpty()) {
                dropped.add(c.remove((int) rounded.backward().getFirst()).centre());
            } else if (!adjust(from, c, rounded)) {
                return rounded.points();
            }
            rounded = Rounding.round(c);
        }
        return rounded.points();
    }

    /** Changes {@code c} for the first leg of {@code rounded} that isn't clear; false if all are, or there's nothing to do. */
    private boolean adjust(Position from, List<Rounding.Circle> c, Rounding.Rounded rounded) {
        List<Position> points = rounded.points();
        Position at = from;
        for (int k = 0; k < points.size(); k++) {
            Position next = points.get(k);
            Entity hit = probe.hit(at, next, k == points.size() - 1);
            if (hit != null) {
                int before = k == 0 ? 0 : rounded.owners().get(k - 1);
                return change(c, before, rounded.owners().get(k), at, next, hit.shape());
            }
            at = next;
        }
        return false;
    }

    private boolean change(List<Rounding.Circle> c, int before, int after, Position a, Position b, Shape shape) {
        Rounding.Circle corner = corner(shape, a, b, c);
        if (corner == null || before == after) {
            return tighten(c, after);
        }
        c.add(after, corner);
        return true;
    }

    /** Whether {@code p} is a circle's centre already, or was one the path turned back on (adding it again would loop). */
    private boolean taken(List<Rounding.Circle> c, Position p) {
        return dropped.contains(p) || c.stream().anyMatch(existing -> existing.centre().equals(p));
    }

    private boolean tighten(List<Rounding.Circle> c, int i) {
        Rounding.Circle circle = c.get(i);
        if (circle.radius() <= tightest + 1e-9) {
            return false;
        }
        c.set(i, circle.withRadius(Math.max(tightest, (circle.radius() + tightest) / 2)));
        return true;
    }

    /**
     * The circle to go round so the leg {@code a b} misses {@code shape}: the disc itself, or the
     * polygon's corner nearest the leg that isn't among {@code c} yet; null when there is none.
     */
    private Rounding.Circle corner(Shape shape, Position a, Position b, List<Rounding.Circle> c) {
        Position centre = null;
        double radius = clearance;
        if (shape instanceof Shape.Circle disc) {
            centre = taken(c, disc.position()) ? null : disc.position();
            radius = disc.radius() + clearance;
        } else {
            for (Position v : ((Shape.Polygon) shape).vertices()) {
                if (!taken(c, v) && (centre == null
                        || Geometry.segmentDistance(a, b, v) < Geometry.segmentDistance(a, b, centre))) {
                    centre = v;
                }
            }
        }
        return centre == null ? null : new Rounding.Circle(centre, Geometry.cross(a, b, centre) >= 0.0 ? 1 : -1, radius);
    }
}
