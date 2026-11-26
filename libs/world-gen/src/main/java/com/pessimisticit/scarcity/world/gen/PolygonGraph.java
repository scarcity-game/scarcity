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
 *
 * <p>The constructor checks the tiling: every edge is held by exactly two cells, or by one and lies
 * along a side of the box (the side's coordinate exactly), else it throws
 * {@link IllegalArgumentException} naming the cell and the edge's endpoints. A T-junction, a gap
 * and a vertex that is nearly but not exactly shared all fail it.
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
        check(holders, this.polygons, lower, upper);
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

    /** Throws unless every edge is held by two cells, or by one and on a side of the box. First fault by cell and edge. */
    private static void check(Map<List<Position>, List<Integer>> holders, List<List<Position>> polygons,
            Position lower, Position upper) {
        for (int c = 0; c < polygons.size(); c++) {
            for (List<Position> edge : edges(polygons.get(c))) {
                List<Integer> cells = holders.get(edge);
                if (cells.size() > 2) {
                    throw new IllegalArgumentException("cells " + cells + " all hold the edge "
                            + edge.get(0) + " to " + edge.get(1) + ": an edge is shared by two cells at most");
                }
                if (cells.size() == 1 && !onSide(edge, lower, upper)) {
                    throw new IllegalArgumentException("cell " + c + " has the edge " + edge.get(0) + " to "
                            + edge.get(1) + " with no cell across it, inside the box " + lower + " to " + upper
                            + " (a T-junction or a gap)");
                }
            }
        }
    }

    private static boolean onSide(List<Position> edge, Position lower, Position upper) {
        Position a = edge.get(0);
        Position b = edge.get(1);
        boolean onX = a.x() == b.x() && (a.x() == lower.x() || a.x() == upper.x());
        boolean onY = a.y() == b.y() && (a.y() == lower.y() || a.y() == upper.y());
        return onX || onY;
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
