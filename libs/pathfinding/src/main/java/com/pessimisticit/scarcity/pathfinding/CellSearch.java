package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A* over floor cells (docs/resources.md, "Cells", "Passable"), from the mover's cell to the
 * target's, expanding at most {@link Pathfinder.Settings#budget} cells. A mover of radius
 * {@code r} crosses from cell A to B when {@code r + TIGHT <= clearance} of both and {@code 2(r + TIGHT) <= width}
 * of their edge (the least {@link Erosion} can cut it to; a cell whose centroid is closer to walls
 * than that may be narrower across, corner to corner, than a leg can pass); the start and goal
 * cells are exempt from the clearance test, as the mover is in one and the target is in the other. The cost of a crossing is the distance from A's point
 * through the edge's midpoint to B's, where a cell's point is its centroid, the mover's position
 * in the start cell and the target in the goal cell; the heuristic is the straight line to the
 * target. A cell other movers are in costs more; within {@link Pathfinder.Settings#near} cells of
 * the start it isn't entered at all.
 */
final class CellSearch {

    /** The cells from start to goal, and the opening (index into the graph's arrays) into each but the first. */
    record Corridor(int[] cells, int[] links) {
    }

    private final FloorGraph graph;
    private final double radius;
    private final Pathfinder.Settings settings;
    private final int[] occupants;
    private final List<Integer> refused;
    private int expanded;

    CellSearch(FloorGraph graph, double radius, Pathfinder.Settings settings, int[] occupants) {
        this.graph = graph;
        this.radius = radius;
        this.settings = settings;
        this.occupants = occupants;
        this.refused = new ArrayList<>();
    }

    /** The cells left out because others were in them near the start, in the order they were refused. */
    List<Integer> refused() {
        return refused;
    }

    /** How many cells the last search expanded. */
    int expanded() {
        return expanded;
    }

    Optional<Corridor> search(int start, int goal, Position from, Position to) {
        int n = graph.size();
        double[] cost = new double[n];
        double[] estimate = new double[n];
        int[] previous = new int[n];
        int[] via = new int[n];
        int[] hops = new int[n];
        boolean[] closed = new boolean[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        IntHeap open = new IntHeap(estimate);
        cost[start] = 0.0;
        estimate[start] = from.distanceTo(to);
        previous[start] = -1;
        open.push(start);
        expanded = 0;
        while (!open.isEmpty()) {
            int u = open.pop();
            if (u == goal) {
                return Optional.of(corridor(goal, previous, via));
            }
            if (expanded == settings.budget()) {
                return Optional.empty();
            }
            expanded++;
            closed[u] = true;
            Position at = point(u, start, goal, from, to);
            for (int link = graph.first[u]; link < graph.first[u + 1]; link++) {
                int v = graph.to[link];
                if (closed[v] || !passable(link, v, goal) || refuse(v, goal, hops[u] + 1)) {
                    continue;
                }
                Position mid = Geometry.midpoint(graph.left[link], graph.right[link]);
                Position next = point(v, start, goal, from, to);
                double c = cost[u] + at.distanceTo(mid) + mid.distanceTo(next) + penalty(v);
                if (c < cost[v]) {
                    cost[v] = c;
                    estimate[v] = c + next.distanceTo(to);
                    previous[v] = u;
                    via[v] = link;
                    hops[v] = hops[u] + 1;
                    open.push(v);
                }
            }
        }
        return Optional.empty();
    }

    private Position point(int cell, int start, int goal, Position from, Position to) {
        if (cell == start) {
            return from;
        }
        return cell == goal ? to : graph.centroids.get(cell);
    }

    private boolean passable(int link, int v, int goal) {
        double least = radius + Pathfinder.TIGHT;
        return 2 * least <= graph.width[link] && (v == goal || least <= graph.clearance[v]);
    }

    private boolean refuse(int v, int goal, int hop) {
        if (occupants[v] == 0 || v == goal || hop > settings.near()) {
            return false;
        }
        refused.add(v);
        return true;
    }

    private double penalty(int v) {
        return occupants[v] * settings.crowding();
    }

    private static Corridor corridor(int goal, int[] previous, int[] via) {
        List<Integer> cells = new ArrayList<>();
        for (int c = goal; c >= 0; c = previous[c]) {
            cells.add(c);
        }
        int[] ordered = new int[cells.size()];
        int[] links = new int[cells.size() - 1];
        for (int i = 0; i < ordered.length; i++) {
            ordered[i] = cells.get(cells.size() - 1 - i);
            if (i > 0) {
                links[i - 1] = via[ordered[i]];
            }
        }
        return new Corridor(ordered, links);
    }
}
