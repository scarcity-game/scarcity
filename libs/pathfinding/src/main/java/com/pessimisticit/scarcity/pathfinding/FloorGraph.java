package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An area's floor cells as arrays (docs/resources.md, "Cells"): cell {@code i} is the {@code i}th
 * floor cell by id, with its polygon, centroid and {@code clearance}, and its openings, one per
 * edge shared with another floor cell: that cell, the edge's {@code width}, and its ends as seen
 * crossing out of {@code i} (the edges are counter-clockwise, so the edge's end is on the left).
 * The openings of cell {@code i} are {@code first[i]} to {@code first[i + 1] - 1}. Also what a
 * path must keep clear of: the solid cells, and the floor's edges where the tiling ends.
 */
final class FloorGraph {

    final List<List<Position>> polygons;
    final List<Position> centroids;
    final double[] clearance;
    final int[] first;
    final int[] to;
    final double[] width;
    final Position[] left;
    final Position[] right;
    /** The polygons of the solid cells, by id, then each edge where the tiling ends next to floor (two points). */
    final List<List<Position>> solids;
    private final Buckets buckets;

    FloorGraph(Map<String, Cell> cells) {
        polygons = new ArrayList<>();
        centroids = new ArrayList<>();
        solids = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : cells.values()) {
            if (cell.kind().open()) {
                index.put(cell.id(), polygons.size());
                polygons.add(cell.polygon());
                centroids.add(cell.position());
            } else {
                solids.add(cell.polygon());
            }
        }
        int n = polygons.size();
        clearance = new double[n];
        first = new int[n + 1];
        List<int[]> links = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        List<Position[]> ends = new ArrayList<>();
        for (Cell cell : cells.values()) {
            Integer i = index.get(cell.id());
            if (i == null) {
                continue;
            }
            clearance[i] = cell.clearance();
            first[i] = links.size();
            openings(cell, index, links, widths, ends);
        }
        first[n] = links.size();
        to = links.stream().mapToInt(l -> l[0]).toArray();
        width = widths.stream().mapToDouble(Double::doubleValue).toArray();
        left = ends.stream().map(e -> e[0]).toArray(Position[]::new);
        right = ends.stream().map(e -> e[1]).toArray(Position[]::new);
        buckets = new Buckets(polygons);
    }

    private void openings(Cell cell, Map<String, Integer> index, List<int[]> links, List<Double> widths,
            List<Position[]> ends) {
        List<Position> polygon = cell.polygon();
        for (int k = 0; k < polygon.size(); k++) {
            Cell.Neighbour across = cell.neighbours().get(k);
            Position a = polygon.get(k);
            Position b = polygon.get((k + 1) % polygon.size());
            Integer j = across.cell() == null ? null : index.get(across.cell());
            if (across.cell() == null) {
                solids.add(List.of(a, b));
            } else if (j != null && across.width() != null) {
                links.add(new int[] {j});
                widths.add(across.width());
                ends.add(new Position[] {b, a});
            }
        }
    }

    int size() {
        return polygons.size();
    }

    /** The floor cell that holds {@code p}; failing that, the nearest one; -1 when there is no floor. */
    int locate(Position p) {
        int holding = containing(p);
        if (holding >= 0) {
            return holding;
        }
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < size(); i++) {
            double d = distance(polygons.get(i), p);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return nearest;
    }

    /** The floor cell that holds {@code p}, or -1. */
    int containing(Position p) {
        for (int i : buckets.near(p)) {
            if (distance(polygons.get(i), p) == 0.0) {
                return i;
            }
        }
        return -1;
    }

    /** 0 inside the convex counter-clockwise {@code polygon} or on its edge, else the distance to it. */
    static double distance(List<Position> polygon, Position p) {
        boolean inside = true;
        double best = Double.POSITIVE_INFINITY;
        for (int k = 0; k < polygon.size(); k++) {
            Position a = polygon.get(k);
            Position b = polygon.get((k + 1) % polygon.size());
            if (Geometry.cross(a, b, p) < 0.0) {
                inside = false;
            }
            best = Math.min(best, Geometry.segmentDistance(a, b, p));
        }
        return inside ? 0.0 : best;
    }
}
