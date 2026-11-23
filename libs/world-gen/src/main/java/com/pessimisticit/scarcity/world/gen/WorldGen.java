package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
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

    /**
     * Squares with rooms and corridors over a {@code width} by {@code height} metre box from
     * (0, 0), its cells sized for {@code passerRadius}. The outer ring of cells is rock.
     */
    public static Layout squares(double width, double height, double passerRadius,
            RoomsAndCorridors.Settings settings, RandomGenerator rng) {
        double size = cellSize(passerRadius);
        SquareGrid grid = new SquareGrid((int) Math.floor(width / size), (int) Math.floor(height / size), size);
        Recorder recorder = new Recorder(rng);
        return layout(grid, new RoomsAndCorridors(settings).generate(grid, recorder), recorder);
    }

    /**
     * The layout of {@code plan} on {@code graph}: the stairs up at the first room's centre, the
     * stairs down at the centre of the room farthest from it through open cells, and a spawn at
     * every other room cell.
     */
    static Layout layout(CellGraph graph, RoomsAndCorridors.Plan plan, Recorder recorder) {
        BitSet open = plan.open();
        int up = plan.rooms().getFirst().centre();
        int[] steps = steps(graph, open, up);
        int down = plan.rooms().get(1).centre();
        for (RoomsAndCorridors.Room room : plan.rooms()) {
            if (steps[room.centre()] > steps[down]) {
                down = room.centre();
            }
        }
        List<Position> spawns = new ArrayList<>();
        for (RoomsAndCorridors.Room room : plan.rooms()) {
            for (int c : room.cells()) {
                if (c != up && c != down) {
                    spawns.add(graph.centroid(c));
                }
            }
        }
        List<List<Position>> cells = open.stream().mapToObj(graph::polygon).toList();
        return new Layout(graph.walls(open), cells, graph.centroid(up), graph.centroid(down), spawns,
                recorder.draws());
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
