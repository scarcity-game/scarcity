package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code cols} by {@code rows} squares of side {@code size}, from (0, 0): cell
 * {@code row * cols + col} spans [col * size, (col + 1) * size] by [row * size, (row + 1) * size].
 * Neighbours are east, north, west, south. Its walls are {@link CellGraph#walls}: one square per
 * solid cell that touches an open one.
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
