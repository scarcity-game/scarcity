package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cells for a test room of rectangular walls: a grid of rectangles whose lines run along every
 * wall's sides, split so none is over {@code most} metres a side, each solid when its centre is
 * in a wall. Every line runs the whole room, so there is no T-junction.
 */
final class Grid {

    private Grid() {
    }

    static Map<String, Cell> cells(Collection<Entity> walls, double most) {
        TreeSet<Double> xs = new TreeSet<>();
        TreeSet<Double> ys = new TreeSet<>();
        for (Entity wall : walls) {
            Shape.BoundingBox box = wall.shape().boundingBox();
            xs.add(box.minX());
            xs.add(box.maxX());
            ys.add(box.minY());
            ys.add(box.maxY());
        }
        List<Double> x = split(xs, most);
        List<Double> y = split(ys, most);
        List<CellMesh.Piece> pieces = new ArrayList<>();
        for (int i = 0; i + 1 < x.size(); i++) {
            for (int j = 0; j + 1 < y.size(); j++) {
                Position centre = new Position((x.get(i) + x.get(i + 1)) / 2, (y.get(j) + y.get(j + 1)) / 2);
                boolean open = walls.stream().noneMatch(w -> inside(w.shape().boundingBox(), centre));
                pieces.add(new CellMesh.Piece(List.of(new Position(x.get(i), y.get(j)),
                        new Position(x.get(i + 1), y.get(j)), new Position(x.get(i + 1), y.get(j + 1)),
                        new Position(x.get(i), y.get(j + 1))), open));
            }
        }
        AtomicInteger next = new AtomicInteger();
        Map<String, Cell> cells = new TreeMap<>();
        CellMesh.generate(pieces, () -> "cell:00000000-0000-4000-8000-%012d".formatted(next.incrementAndGet()))
                .cells().forEach(c -> cells.put(c.id(), c));
        return cells;
    }

    private static boolean inside(Shape.BoundingBox box, Position p) {
        return p.x() > box.minX() && p.x() < box.maxX() && p.y() > box.minY() && p.y() < box.maxY();
    }

    private static List<Double> split(TreeSet<Double> lines, double most) {
        List<Double> out = new ArrayList<>();
        Double previous = null;
        for (double line : lines) {
            if (previous != null) {
                int parts = (int) Math.ceil((line - previous) / most);
                for (int k = 1; k < parts; k++) {
                    out.add(previous + (line - previous) * k / parts);
                }
            }
            out.add(line);
            previous = line;
        }
        return out;
    }
}
