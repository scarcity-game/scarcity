package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * The shortest path of straight legs round the obstacles a {@link Probe} checks against: the
 * waypoints are each obstacle's corners grown by the mover's radius and {@link Pathfinder#MARGIN}
 * ({@link Corners}), a leg joins two when the probe finds it clear, and A* finds the shortest
 * series of legs. Up to n² leg tests for n waypoints, each over every obstacle: for an area with
 * no cells (made before #353), and for mending a leg against the few obstacles near it.
 */
final class VisibilityGraph {

    /** How far inside the clearance of another obstacle still drops a corner, in m: rounding. */
    private static final double SLACK = 1e-6;

    private final Probe probe;

    VisibilityGraph(Probe probe) {
        this.probe = probe;
    }

    /**
     * The waypoints from {@code from} to {@code to}, {@code from} not among them; empty when there
     * is no way. The leg into {@code to} is checked at the mover's own radius when {@code last}.
     */
    Optional<List<Position>> search(Position from, Position to, boolean last) {
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
            relax(u, nodes, new Search(cost, previous, closed, open), last);
        }
        return Optional.empty();
    }

    /** The search's state: the cost to each node, the node before it, which are done, and the queue. */
    private record Search(double[] cost, int[] previous, boolean[] closed, PriorityQueue<double[]> open) {
    }

    /** Lowers the cost of every node a clear leg from {@code u} reaches more cheaply; node 1 is the target. */
    private void relax(int u, List<Position> nodes, Search s, boolean last) {
        Position to = nodes.get(1);
        for (int v = 1; v < nodes.size(); v++) {
            double c = s.cost()[u] + nodes.get(u).distanceTo(nodes.get(v));
            if (!s.closed()[v] && c < s.cost()[v] && probe.clear(nodes.get(u), nodes.get(v), last && v == 1)) {
                s.cost()[v] = c;
                s.previous()[v] = u;
                s.open().add(new double[] {c + nodes.get(v).distanceTo(to), v});
            }
        }
    }

    /**
     * {@code from} (0), {@code to} (1), then every obstacle's corners, by entity id, except those
     * within the clearance of another obstacle. Walls that meet (one per cell, #234) put a corner
     * at every shared vertex along a straight run, each exactly the clearance from the next wall;
     * a path never bends there, and a corner inside another obstacle is never reached.
     */
    private List<Position> nodes(Position from, Position to) {
        List<Entity> obstacles = Pathfinder.obstacles(probe.entities(), probe.moverId()).toList();
        List<Position> nodes = new ArrayList<>();
        nodes.add(from);
        nodes.add(to);
        for (Entity obstacle : obstacles) {
            for (Position corner : Corners.of(obstacle.shape(), probe.radius() + Pathfinder.MARGIN)) {
                if (clearOfOthers(corner, obstacle, obstacles)) {
                    nodes.add(corner);
                }
            }
        }
        return nodes;
    }

    /**
     * Whether {@code p} is more than the clearance (plus {@link #SLACK}) from every obstacle but
     * {@code own}. A point is within reach of a convex shape exactly when moving toward the
     * shape's centre closes on it at once.
     */
    private boolean clearOfOthers(Position p, Entity own, List<Entity> obstacles) {
        double reach = probe.radius() + Pathfinder.MARGIN + SLACK;
        for (Entity obstacle : obstacles) {
            if (obstacle == own) {
                continue;
            }
            Position c = obstacle.position();
            double dx = c.x() - p.x();
            double dy = c.y() - p.y();
            if (dx == 0.0 && dy == 0.0 || obstacle.shape().contact(p, dx, dy, reach) <= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static List<Position> route(List<Position> nodes, int[] previous) {
        List<Position> route = new ArrayList<>();
        for (int v = 1; v != 0; v = previous[v]) {
            route.add(nodes.get(v));
        }
        Collections.reverse(route);
        return List.copyOf(route);
    }
}
