package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Makes a {@link Layout} (#234, #310): a tessellation, a generator over its cells, then the
 * walls, the stairs and the spawn points. The same random source and arguments give the same
 * layout.
 */
public final class WorldGen {

    /** The smallest cell side, in metres. */
    public static final double MIN_CELL = 0.8;

    /** The part of a one-cell passage the largest passer's diameter may fill. */
    public static final double FILL = 0.75;

    private WorldGen() {
    }

    /** The cell side for a tessellation the largest entity, of radius {@code passerRadius}, must pass through. */
    public static double cellSize(double passerRadius) {
        return Math.max(MIN_CELL, 2 * passerRadius / FILL);
    }

    /** The room a passer keeps from every wall beyond its own radius, in metres: a disc of radius {@code passerRadius + MARGIN} fits. */
    public static final double MARGIN = 0.1;

    /**
     * A level of any tessellation and generator (#386) over a {@code width} by {@code height}
     * metre box from (0, 0): cells of mean area {@code cellSize(passerRadius)²}, then the plan for
     * a disc of radius {@code passerRadius + MARGIN}, then its walls, cells, stairs and spawns.
     */
    public static Layout make(double width, double height, double passerRadius, Tessellation tessellation,
            Generator generator, RandomGenerator rng) {
        return make(width, height, passerRadius, new Outline.Box(), tessellation, generator, rng);
    }

    /**
     * {@link #make} in the shape of {@code outline} (#387): the outline is drawn first, then the
     * tessellation over the whole box, of which only the cells whose centroid is inside the outline
     * are kept, and of those the most that are joined edge to edge; then the generator. The
     * outline's edge cells are the layout's {@link Layout#outer()}.
     */
    public static Layout make(double width, double height, double passerRadius, Outline outline,
            Tessellation tessellation, Generator generator, RandomGenerator rng) {
        Recorder recorder = new Recorder(rng);
        List<Position> shape = outline.draw(width, height, recorder);
        CellGraph whole = tessellation.make(width, height, cellSize(passerRadius), recorder);
        CellGraph graph = shape == null ? whole : trim(whole, shape);
        Plan plan = generator.generate(graph, recorder, passerRadius + MARGIN);
        return layout(graph, plan, recorder);
    }

    /** The cells of {@code graph} whose centroid is inside {@code shape}: the most of them that are joined edge to edge. */
    static CellGraph trim(CellGraph graph, List<Position> shape) {
        BitSet inside = new BitSet(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            if (Outline.contains(shape, graph.centroid(c))) {
                inside.set(c);
            }
        }
        BitSet biggest = new BitSet();
        BitSet left = (BitSet) inside.clone();
        while (!left.isEmpty()) {
            BitSet part = Connectivity.reachable(graph, inside, left.nextSetBit(0));
            left.andNot(part);
            if (part.cardinality() > biggest.cardinality()) {
                biggest = part;
            }
        }
        if (biggest.isEmpty()) {
            throw new IllegalArgumentException("outline: no cell's centroid is inside it");
        }
        return PolygonGraph.part(biggest.stream().mapToObj(graph::polygon).toList());
    }

    /** Squares with rooms and corridors (#234): {@link #make} with {@link Tessellation.Squares}. */
    public static Layout squares(double width, double height, double passerRadius,
            RoomsAndCorridors.Settings settings, RandomGenerator rng) {
        return make(width, height, passerRadius, new Tessellation.Squares(), new RoomsAndCorridors(settings), rng);
    }

    /** The dive game's Voronoi levels: {@code relaxations} rounds of Lloyd relaxation, then the cave generator's settings. */
    public record VoronoiSettings(int relaxations, CaveGenerator.Settings caves) {
        public static final VoronoiSettings DEFAULT = new VoronoiSettings(
                Tessellation.Voronoi.DEFAULT.relaxations(), CaveGenerator.Settings.DEFAULT);
    }

    /** The sites a Voronoi box needs for cells of mean area {@code cellSize(passerRadius)^2}. */
    public static int sites(double width, double height, double passerRadius) {
        return Tessellation.Voronoi.sites(width, height, cellSize(passerRadius));
    }

    /** A Voronoi tessellation with caves in it (#312): {@link #make} with {@link Tessellation.Voronoi}. */
    public static Layout voronoi(double width, double height, double passerRadius, VoronoiSettings settings,
            RandomGenerator rng) {
        return make(width, height, passerRadius, new Tessellation.Voronoi(settings.relaxations()),
                new CaveGenerator(settings.caves()), rng);
    }

    /** The dive game's Penrose levels: rhombs of side {@code edge} times {@link #cellSize}, then the cave generator's settings. */
    public record PenroseSettings(double edge, CaveGenerator.Settings caves) {
        public static final PenroseSettings DEFAULT = new PenroseSettings(
                Tessellation.Penrose.MEAN_AREA_EDGE, CaveGenerator.Settings.DEFAULT);
    }

    /** A Penrose rhomb tiling with caves in it: {@link #make} with {@link Tessellation.Penrose}. */
    public static Layout penrose(double width, double height, double passerRadius, PenroseSettings settings,
            RandomGenerator rng) {
        return make(width, height, passerRadius, new Tessellation.Penrose(settings.edge()),
                new CaveGenerator(settings.caves()), rng);
    }

    /** The layout of {@code plan} on {@code graph}. */
    static Layout layout(CellGraph graph, Plan plan, Recorder recorder) {
        return layout(graph, plan.open(), plan.up(), plan.down(), plan.spawns(), recorder);
    }

    /** The layout of the {@code open} cells: stairs on the cells {@code up} and {@code down}, a spawn at each of {@code spawnCells}. */
    static Layout layout(CellGraph graph, BitSet open, int up, int down, List<Integer> spawnCells, Recorder recorder) {
        List<Position> spawns = spawnCells.stream().map(graph::centroid).toList();
        List<List<Position>> openCells = open.stream().mapToObj(graph::polygon).toList();
        List<Cell> cells = cells(graph, open, recorder);
        return new Layout(graph.walls(open), cells, outer(graph, cells), openCells, graph.centroid(up),
                graph.centroid(down), spawns, recorder.draws());
    }

    /**
     * The cells of {@code graph}, open as in {@code open}, in a mesh: ids from the recorder's source
     * (undrawn, after every choice), so a seed makes the same ids.
     */
    static List<Cell> cells(CellGraph graph, BitSet open, Recorder recorder) {
        List<CellMesh.Piece> pieces = new ArrayList<>(graph.size());
        for (int cell = 0; cell < graph.size(); cell++) {
            pieces.add(new CellMesh.Piece(graph.polygon(cell), open.get(cell)));
        }
        return CellMesh.generate(pieces, recorder::newCellId).cells();
    }

    /** The ids of the cells on the graph's edge, in the order of {@code cells}. */
    static List<String> outer(CellGraph graph, List<Cell> cells) {
        Set<List<Position>> edge = new HashSet<>();
        for (int c = 0; c < graph.size(); c++) {
            if (graph.onBorder(c)) {
                edge.add(graph.polygon(c));
            }
        }
        return cells.stream().filter(cell -> edge.contains(cell.polygon())).map(Cell::id).toList();
    }

    /** How many open cells away from {@code start} each cell is; -1 when it can't be reached. */
    static int[] steps(CellGraph graph, BitSet open, int start) {
        int[] steps = new int[graph.size()];
        Arrays.fill(steps, -1);
        Deque<Integer> queue = new ArrayDeque<>();
        steps[start] = 0;
        queue.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int n : graph.neighbours(cell)) {
                if (open.get(n) && steps[n] < 0) {
                    steps[n] = steps[cell] + 1;
                    queue.add(n);
                }
            }
        }
        return steps;
    }
}
