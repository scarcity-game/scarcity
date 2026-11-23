package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * {@code cols} by {@code rows} squares of side {@code size}, from (0, 0): cell
 * {@code row * cols + col} spans [col * size, (col + 1) * size] by [row * size, (row + 1) * size].
 * Neighbours are east, north, west, south. Its walls are the solid cells merged into maximal
 * rectangles, row by row, so a run of rock is one wall.
 */
public final class SquareGrid implements CellGraph {

    private final int cols;
    private final int rows;
    private final double size;

    public SquareGrid(int cols, int rows, double size) {
        if (cols < 3 || rows < 3) {
            throw new IllegalArgumentException("a square grid needs at least 3 x 3 cells, got " + cols + " x " + rows);
        }
        if (!(size > 0.0) || !Double.isFinite(size)) {
            throw new IllegalArgumentException("size: must be positive and finite, got " + size);
        }
        this.cols = cols;
        this.rows = rows;
        this.size = size;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public double cellSize() {
        return size;
    }

    public int cell(int col, int row) {
        return row * cols + col;
    }

    @Override
    public int size() {
        return cols * rows;
    }

    @Override
    public List<Position> polygon(int cell) {
        int c = cell % cols;
        int r = cell / cols;
        return rectangle(c, r, c + 1, r + 1);
    }

    @Override
    public Position centroid(int cell) {
        return new Position((cell % cols + 0.5) * size, (cell / cols + 0.5) * size);
    }

    @Override
    public List<Integer> neighbours(int cell) {
        int c = cell % cols;
        int r = cell / cols;
        List<Integer> out = new ArrayList<>(4);
        if (c + 1 < cols) {
            out.add(cell + 1);
        }
        if (r + 1 < rows) {
            out.add(cell + cols);
        }
        if (c > 0) {
            out.add(cell - 1);
        }
        if (r > 0) {
            out.add(cell - cols);
        }
        return out;
    }

    @Override
    public boolean onBorder(int cell) {
        return neighbours(cell).size() < 4;
    }

    @Override
    public double minEdge() {
        return size;
    }

    @Override
    public List<Position> bounds() {
        return List.of(new Position(0.0, 0.0), new Position(x(cols), y(rows)));
    }

    @Override
    public List<Entity> walls(BitSet open) {
        BitSet claimed = new BitSet(size());
        List<Entity> walls = new ArrayList<>();
        for (int cell = 0; cell < size(); cell++) {
            if (free(cell, open, claimed)) {
                walls.add(wall(claim(cell, open, claimed)));
            }
        }
        return walls;
    }

    /** The widest run of free rock east of {@code cell}, grown north while the whole run is free. */
    private int[] claim(int cell, BitSet open, BitSet claimed) {
        int c0 = cell % cols;
        int r0 = cell / cols;
        int c1 = c0 + 1;
        while (c1 < cols && free(cell(c1, r0), open, claimed)) {
            c1++;
        }
        int r1 = r0 + 1;
        while (r1 < rows && runFree(c0, c1, r1, open, claimed)) {
            r1++;
        }
        for (int r = r0; r < r1; r++) {
            claimed.set(cell(c0, r), cell(c1 - 1, r) + 1);
        }
        return new int[] {c0, r0, c1, r1};
    }

    private boolean runFree(int c0, int c1, int row, BitSet open, BitSet claimed) {
        for (int c = c0; c < c1; c++) {
            if (!free(cell(c, row), open, claimed)) {
                return false;
            }
        }
        return true;
    }

    private static boolean free(int cell, BitSet open, BitSet claimed) {
        return !open.get(cell) && !claimed.get(cell);
    }

    private Entity wall(int[] box) {
        return Prototypes.get("stone-wall").withPolygon(rectangle(box[0], box[1], box[2], box[3]));
    }

    /** The rectangle between grid lines. Every vertex on a grid line is computed the same way, so walls meet exactly. */
    private List<Position> rectangle(int c0, int r0, int c1, int r1) {
        return List.of(new Position(x(c0), y(r0)), new Position(x(c1), y(r0)),
                new Position(x(c1), y(r1)), new Position(x(c0), y(r1)));
    }

    private double x(int col) {
        return col * size;
    }

    private double y(int row) {
        return row * size;
    }
}
