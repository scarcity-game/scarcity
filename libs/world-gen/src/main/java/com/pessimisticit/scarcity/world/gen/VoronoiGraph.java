package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Voronoi tessellation of the box [0, width] by [0, height] (#234, #312): {@code sites} points
 * drawn uniformly in the box ({@code voronoi.site[i].x}, {@code .y}), then {@code relaxations}
 * rounds of Lloyd relaxation, each moving every site to the centroid of its cell. Few rounds
 * leave cells of very different sizes, many make them even.
 *
 * <p>To get cells of area near {@code cellSize^2} (so a passage of one cell fits what
 * {@link WorldGen#cellSize} was made for), {@link WorldGen#sites} asks for
 * {@code width * height / cellSize^2} sites. A cell's shared edge is shorter than its side (a
 * hexagon of that area has sides near 0.62 {@code cellSize}), so {@link CaveGenerator} widens any
 * passage whose gap is narrower than the passer needs.
 *
 * <p>Cells are clipped to the box, convex and counter-clockwise. Vertices are computed once and
 * shared: cells that meet hold equal {@link Position}s ({@link VoronoiVertices}), so the walls of
 * neighbours leave no hole, and every edge between two cells is the same in both (no T-junction). {@code neighbours} share an edge, not only a vertex; cells with an
 * edge no other cell shares (on the box) are {@code onBorder}. A cell that collapses to nothing
 * (a site on top of another) is left out, so cell numbers are those of the cells that remain.
 */
public final class VoronoiGraph implements CellGraph {

    private final double width;
    private final double height;
    private final List<List<Position>> polygons;
    private final List<Position> centroids;
    private final List<List<Integer>> neighbours;
    private final BitSet border;
    private final double minEdge;

    public VoronoiGraph(double width, double height, int sites, int relaxations, Recorder recorder) {
        if (!(width > 0.0) || !(height > 0.0) || sites < 3 || relaxations < 0) {
            throw new IllegalArgumentException("voronoi: need a positive box, 3 or more sites and no negative relaxations");
        }
        this.width = width;
        this.height = height;
        List<Position> points = draw(sites, recorder);
        for (int round = 0; round < relaxations; round++) {
            points = VoronoiCells.clip(width, height, points).stream().map(VoronoiCells::centroid).toList();
        }
        this.polygons = VoronoiVertices.cells(width, height, points);
        this.centroids = polygons.stream().map(VoronoiCells::centroid).toList();
        Map<List<Position>, List<Integer>> holders = holders(polygons);
        this.border = border(holders, polygons.size());
        this.minEdge = minEdge(holders);
        this.neighbours = neighbours(holders, polygons);
    }

    private List<Position> draw(int sites, Recorder recorder) {
        List<Position> points = new ArrayList<>(sites);
        for (int i = 0; i < sites; i++) {
            double x = recorder.uniform("voronoi.site[" + i + "].x", 0.0, width);
            double y = recorder.uniform("voronoi.site[" + i + "].y", 0.0, height);
            points.add(new Position(x, y));
        }
        return points;
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
        return List.of(new Position(0.0, 0.0), new Position(width, height));
    }
}
