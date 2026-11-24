package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The shortest path of straight legs for a circle from where it is to a point, around every
 * obstacle of the area: an entity with a shape that doesn't {@code overlap}, walls among them
 * (docs/resources.md, "Shapes"). A visibility graph: the waypoints are the corners of each
 * obstacle grown by the mover's radius and {@link #MARGIN} ({@link Corners}), a leg joins two of
 * them when the mover can take it, and A* finds the shortest series of legs.
 *
 * <p>A leg is clear when {@link Contacts#first} (the check {@code step} is interrupted by) finds
 * nothing before its end for the mover, at the leg's start, moving along it, with its radius
 * grown by half the margin: so a {@code step} along it isn't stopped by a rounding error. The
 * last leg, into the target, is checked at the mover's own radius, as the target may lie closer
 * to a wall than the margin. Other movers count where they are now; the caller plans again when
 * they have moved.
 */
public final class Pathfinder {

    /** How much further than its radius a path keeps the mover from an obstacle's corners, in m. */
    public static final double MARGIN = 0.05;

    private final String moverId;
    private final Entity mover;
    private final double radius;
    private final SortedMap<String, Entity> entities;
    private final AreaView probe;

    private Pathfinder(AreaView view, String moverId) {
        this.moverId = moverId;
        this.mover = view.peek(moverId);
        this.radius = mover.radius() == null ? 0.0 : mover.radius();
        this.entities = new TreeMap<>(view.entities());
        String areaId = view.areaId();
        Area area = view.area();
        SortedMap<String, Entity> seen = Collections.unmodifiableSortedMap(entities);
        this.probe = new AreaView() {
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

    /**
     * The waypoints from the entity {@code moverId}'s position to {@code to}: each one the end of
     * a leg, the last one {@code to}, the mover's position not among them. Empty when the mover
     * can't reach {@code to}; an empty list when it is there.
     */
    public static Optional<List<Position>> path(AreaView view, String moverId, Position to) {
        return new Pathfinder(view, moverId).search(to);
    }

    private Optional<List<Position>> search(Position to) {
        Position from = mover.position();
        if (from.equals(to)) {
            return Optional.of(List.of());
        }
        List<Position> nodes = nodes(from, to);
        int n = nodes.size();
        double[] cost = new double[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] previous = new int[n];
        boolean[] closed = new boolean[n];
        cost[0] = 0.0;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> a[0] != b[0]
                ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        open.add(new double[] {from.distanceTo(to), 0});
        while (!open.isEmpty()) {
            int u = (int) open.poll()[1];
            if (closed[u]) {
                continue;
            }
            if (u == 1) {
                return Optional.of(route(nodes, previous));
            }
            closed[u] = true;
            for (int v = 1; v < n; v++) {
                double c = cost[u] + nodes.get(u).distanceTo(nodes.get(v));
                if (!closed[v] && c < cost[v] && clear(nodes.get(u), nodes.get(v), v == 1)) {
                    cost[v] = c;
                    previous[v] = u;
                    open.add(new double[] {c + nodes.get(v).distanceTo(to), v});
                }
            }
        }
        return Optional.empty();
    }

    /** {@code from} (0), {@code to} (1), then every obstacle's corners, by entity id. */
    private List<Position> nodes(Position from, Position to) {
        List<Position> nodes = new ArrayList<>();
        nodes.add(from);
        nodes.add(to);
        for (Map.Entry<String, Entity> e : entities.entrySet()) {
            Shape shape = e.getValue().shape();
            if (shape != null && !e.getValue().overlap() && !e.getKey().equals(moverId)) {
                nodes.addAll(Corners.of(shape, radius + MARGIN));
            }
        }
        return nodes;
    }

    private static List<Position> route(List<Position> nodes, int[] previous) {
        List<Position> route = new ArrayList<>();
        for (int v = 1; v != 0; v = previous[v]) {
            route.add(nodes.get(v));
        }
        Collections.reverse(route);
        return List.copyOf(route);
    }

    /** Whether the mover, at {@code a} and moving toward {@code b}, gets there untouched. */
    private boolean clear(Position a, Position b, boolean last) {
        double length = a.distanceTo(b);
        if (length == 0.0) {
            return true;
        }
        double r = last ? radius : radius + MARGIN / 2;
        Entity there = mover.withPosition(a);
        entities.put(moverId, r > 0.0 ? there.withRadius(r) : there);
        Motion along = new Motion((b.x() - a.x()) / length, (b.y() - a.y()) / length);
        try {
            return Contacts.first(probe, Map.of(), moverId, along).time() >= length;
        } finally {
            entities.put(moverId, mover);
        }
    }
}
