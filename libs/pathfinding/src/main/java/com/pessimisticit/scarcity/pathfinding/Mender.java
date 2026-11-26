package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The last resort for a leg that still isn't clear: a visibility graph ({@link VisibilityGraph})
 * over the obstacles near it, from the leg's start to the first later waypoint the mover can
 * stand at (one within its clearance of a wall can't be reached; the goal always counts). If that
 * finds nothing the legs stay as they are: the {@code step} is cut short and the caller plans
 * again.
 */
final class Mender {

    /** How far round the stretch mended obstacles are taken into account, in m. */
    private static final double NEARBY = 2.0;

    private final Probe probe;
    private int mended;

    Mender(Probe probe) {
        this.probe = probe;
    }

    /** How many stretches were mended. */
    int mended() {
        return mended;
    }

    List<Position> mend(Position from, List<Position> points) {
        List<Position> path = new ArrayList<>();
        Position at = from;
        int i = 0;
        while (i < points.size()) {
            Position next = points.get(i);
            if (probe.clear(at, next, i == points.size() - 1)) {
                add(path, at, next);
                at = next;
                i++;
                continue;
            }
            int to = standable(points, i);
            Optional<List<Position>> round = nearby(at, points.subList(i, to + 1))
                    .search(at, points.get(to), to == points.size() - 1);
            if (round.isPresent()) {
                mended++;
                path.addAll(round.get());
                at = points.get(to);
                i = to + 1;
            } else {
                add(path, at, next);
                at = next;
                i++;
            }
        }
        return List.copyOf(path);
    }

    private static void add(List<Position> path, Position at, Position next) {
        if (!next.equals(at)) {
            path.add(next);
        }
    }

    /** The first index from {@code i} whose point is clear of every obstacle by the checked radius; the last if none is. */
    private int standable(List<Position> points, int i) {
        for (int k = i; k < points.size() - 1; k++) {
            if (probe.standable(points.get(k))) {
                return k;
            }
        }
        return points.size() - 1;
    }

    /** A visibility graph over the obstacles whose bounding boxes come within {@link #NEARBY} of the stretch from {@code a}. */
    private VisibilityGraph nearby(Position a, List<Position> stretch) {
        List<Entity> near = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        probe.entities().forEach((id, e) -> {
            if (!id.equals(probe.moverId()) && e.shape() != null && !e.overlap() && near(e, a, stretch)) {
                near.add(e);
                ids.add(id);
            }
        });
        return new VisibilityGraph(probe.with(near, ids));
    }

    private static boolean near(Entity e, Position a, List<Position> stretch) {
        Position at = a;
        for (Position p : stretch) {
            if (Geometry.segmentDistance(at, p, e.position()) - Pathfinder.extent(e.shape()) <= NEARBY) {
                return true;
            }
            at = p;
        }
        return false;
    }
}
