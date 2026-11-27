package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hexagonal lattice that hexagons (#419) and the rhombille tiling (#420) are both cut from:
 * pointy-top regular hexagons of circumradius {@code side}, their centres on the lattice of
 * {@code A = (√3 side, 0)} and {@code B = (√3 side / 2, 3 side / 2)} from a drawn origin.
 *
 * <p>Every vertex, a hexagon's corner or its centre, is {@code (i A + j B) / 3} for two integers
 * {@code (i, j)}: its key. A position is computed once per key, so cells that meet hold equal
 * {@link Position}s and the tiling is edge to edge with no T-junction. {@link #hexagons} are the
 * hexagons; {@link #rhombs} cut each into three rhombs through its centre. Both are clipped to the
 * box with {@link BoxClip}.
 */
final class HexLattice {

    private static final double ROOT3 = Math.sqrt(3.0);
    /** A hexagon's corners in thirds of (A, B) from its centre, counter-clockwise from 30 degrees. */
    private static final int[][] CORNERS = {{1, 1}, {-1, 2}, {-2, 1}, {-1, -1}, {1, -2}, {2, -1}};

    private final double width;
    private final double height;
    private final double side;
    private final double originX;
    private final double originY;
    private final Map<Long, Position> vertices;

    /** The lattice over the box, its origin drawn: {@code name.origin.x} and {@code .y}, within one period. */
    HexLattice(double width, double height, double side, String name, Recorder recorder) {
        if (!(width > 0.0) || !(height > 0.0) || !(side > 0.0)) {
            throw new IllegalArgumentException(name + ": need a positive box and side");
        }
        this.width = width;
        this.height = height;
        this.side = side;
        this.vertices = new HashMap<>();
        this.originX = recorder.uniform(name + ".origin.x", 0.0, ROOT3 * side);
        this.originY = recorder.uniform(name + ".origin.y", 0.0, 3.0 * side);
    }

    /** The hexagons that reach into the box, clipped to it. */
    List<List<Position>> hexagons() {
        List<List<Position>> cells = new ArrayList<>();
        forEachCentre((i, j) -> {
            List<Position> hexagon = new ArrayList<>(6);
            for (int[] corner : CORNERS) {
                hexagon.add(vertex(i + corner[0], j + corner[1]));
            }
            add(cells, hexagon);
        });
        return cells;
    }

    /** The rhombs, three to a hexagon: its centre and three corners in a row, each 60 and 120 degrees. */
    List<List<Position>> rhombs() {
        List<List<Position>> cells = new ArrayList<>();
        forEachCentre((i, j) -> {
            for (int k = 0; k < 6; k += 2) {
                List<Position> rhomb = new ArrayList<>(4);
                rhomb.add(vertex(i, j));
                for (int m = 0; m < 3; m++) {
                    int[] corner = CORNERS[(k + m) % 6];
                    rhomb.add(vertex(i + corner[0], j + corner[1]));
                }
                add(cells, rhomb);
            }
        });
        return cells;
    }

    private void add(List<List<Position>> cells, List<Position> polygon) {
        List<Position> piece = BoxClip.toBox(polygon, width, height);
        if (piece != null) {
            cells.add(piece);
        }
    }

    /** Calls {@code each} with the key (3 q, 3 r) of every hexagon centre whose hexagon may touch the box. */
    private void forEachCentre(Centre each) {
        // Centre (q, r) is at origin + q A + r B; r runs over the rows the box (plus a hexagon) touches.
        int rows = (int) Math.ceil((height + 2 * side) / (1.5 * side)) + 2;
        int rowStart = (int) Math.floor(-originY / (1.5 * side)) - 1;
        int cols = (int) Math.ceil((width + 2 * side) / (ROOT3 * side)) + 2;
        for (int r = rowStart; r <= rowStart + rows; r++) {
            int colStart = (int) Math.floor((-originX - r * ROOT3 * side / 2) / (ROOT3 * side)) - 1;
            for (int q = colStart; q <= colStart + cols; q++) {
                each.at(3 * q, 3 * r);
            }
        }
    }

    private Position vertex(int i, int j) {
        long key = ((long) i << 32) ^ (j & 0xffffffffL);
        return vertices.computeIfAbsent(key, k -> new Position(
                originX + (i * ROOT3 * side + j * ROOT3 * side / 2) / 3,
                originY + j * 1.5 * side / 3));
    }

    /** What {@link #forEachCentre} calls for each centre. */
    private interface Centre {
        void at(int i, int j);
    }
}
