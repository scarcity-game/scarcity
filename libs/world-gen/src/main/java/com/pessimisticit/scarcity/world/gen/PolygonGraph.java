package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link CellGraph} from its polygons alone: what any tessellation made of polygons that meet
 * edge to edge needs. Each polygon is convex and counter-clockwise, and cells that meet hold equal
 * {@link Position}s at their shared vertices, so an edge between two cells is the same in both
 * (no T-junction). {@code neighbours} share an edge, not only a vertex; a cell with an edge no
 * other cell shares is {@code onBorder}; centroids are {@link VoronoiCells#centroid}'s.
 */
final class PolygonGraph implements CellGraph {

    private final List<List<Position>> polygons;
    private final List<Position> centroids;
    private final List<List<Integer>> neighbours;
    private final BitSet border;
    private final double minEdge;
    private final List<Position> bounds;

    /** The graph of {@code polygons}, within the box from {@code lower} to {@code upper}. */
    PolygonGraph(List<List<Position>> polygons, Position lower, Position upper) {
        this.polygons = List.copyOf(polygons);
        this.centroids = this.polygons.stream().map(VoronoiCells::centroid).toList();
        Map<List<Position>, List<Integer>> holders = holders(this.polygons);
        this.border = border(holders, this.polygons.size());
        this.minEdge = minEdge(holders);
        this.neighbours = neighbours(holders, this.polygons);
        this.bounds = List.of(lower, upper);
    }

    /** Which cells hold each edge: two for an edge between cells, one for an edge on the box. */
    private static Map<List<Position>, List<Integer>> holders(List<List<Position>> polygons) {
        Map<List<Position>, List<Integer>> holders = new HashMap<>();
        for (int c = 0; c < polygons.size(); c++) {
            for (List<Position> edge : edges(polygons.get(c))) {
                holders.computeIfAbsent(edge, e -> new ArrayList<>()).add(c);
            }
        }
        return holders;
    }

    private static BitSet border(Map<List<Position>, List<Integer>> holders, int cells) {
        BitSet border = new BitSet(cells);
        holders.values().stream().filter(h -> h.size() == 1).forEach(h -> border.set(h.getFirst()));
        return border;
    }

    private static double minEdge(Map<List<Position>, List<Integer>> holders) {
        return holders.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .mapToDouble(e -> e.getKey().get(0).distanceTo(e.getKey().get(1))).min().orElse(Double.POSITIVE_INFINITY);
    }

    private static List<List<Integer>> neighbours(Map<List<Position>, List<Integer>> holders,
            List<List<Position>> polygons) {
        List<List<Integer>> neighbours = new ArrayList<>(polygons.size());
        for (int c = 0; c < polygons.size(); c++) {
            List<Integer> mine = new ArrayList<>();
            for (List<Position> edge : edges(polygons.get(c))) {
                for (int other : holders.get(edge)) {
                    if (other != c) {
                        mine.add(other);
                    }
                }
            }
            neighbours.add(mine);
        }
        return neighbours;
    }

    /** The polygon's edges as unordered pairs (the lower vertex first, by x then y). */
    private static List<List<Position>> edges(List<Position> polygon) {
        List<List<Position>> edges = new ArrayList<>(polygon.size());
        for (int k = 0; k < polygon.size(); k++) {
            Position a = polygon.get(k);
            Position b = polygon.get((k + 1) % polygon.size());
            boolean aFirst = a.x() < b.x() || (a.x() == b.x() && a.y() < b.y());
            edges.add(aFirst ? List.of(a, b) : List.of(b, a));
        }
        return edges;
    }

    @Override
    public int size() {
        return polygons.size();
    }

    @Override
    public List<Position> polygon(int cell) {
        return polygons.get(cell);
    }

    @Override
    public Position centroid(int cell) {
        return centroids.get(cell);
    }

    @Override
    public List<Integer> neighbours(int cell) {
        return neighbours.get(cell);
    }

    @Override
    public boolean onBorder(int cell) {
        return border.get(cell);
    }

    @Override
    public double minEdge() {
        return minEdge;
    }

    @Override
    public List<Position> bounds() {
        return bounds;
    }
}
