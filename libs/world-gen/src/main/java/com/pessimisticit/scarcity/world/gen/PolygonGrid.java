package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Polygons (or points, as one-vertex polygons) by the square buckets their bounding boxes touch,
 * so a question about what is near a point or a segment looks at the few polygons near it, not
 * all of them (#371). Not thread-safe. Answers are exactly those of a scan over every polygon: the grid only
 * skips polygons whose boxes are too far to matter.
 */
final class PolygonGrid {

    private final List<List<Position>> polygons;
    private final double[] boxes;
    private final double minX;
    private final double minY;
    private final double size;
    private final int cols;
    private final int rows;
    private final int[][] buckets;
    /** Per polygon, the query that last saw it: so a query reports each once. Not thread-safe. */
    private final int[] stamp;
    private int query;

    /** A grid over {@code polygons} with buckets about {@code size} metres square. */
    PolygonGrid(List<List<Position>> polygons, double size) {
        this.polygons = List.copyOf(polygons);
        this.boxes = new double[4 * polygons.size()];
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < polygons.size(); i++) {
            box(polygons.get(i), i);
            x0 = Math.min(x0, boxes[4 * i]);
            y0 = Math.min(y0, boxes[4 * i + 1]);
            x1 = Math.max(x1, boxes[4 * i + 2]);
            y1 = Math.max(y1, boxes[4 * i + 3]);
        }
        boolean empty = polygons.isEmpty();
        this.minX = empty ? 0.0 : x0;
        this.minY = empty ? 0.0 : y0;
        this.size = size;
        this.cols = empty ? 1 : (int) Math.min(2048, Math.floor((x1 - x0) / size) + 1);
        this.rows = empty ? 1 : (int) Math.min(2048, Math.floor((y1 - y0) / size) + 1);
        this.buckets = fill();
        this.stamp = new int[polygons.size()];
    }

    private void box(List<Position> polygon, int i) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (Position p : polygon) {
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            x1 = Math.max(x1, p.x());
            y1 = Math.max(y1, p.y());
        }
        boxes[4 * i] = x0;
        boxes[4 * i + 1] = y0;
        boxes[4 * i + 2] = x1;
        boxes[4 * i + 3] = y1;
    }

    private int[][] fill() {
        List<List<Integer>> lists = new ArrayList<>(cols * rows);
        for (int b = 0; b < cols * rows; b++) {
            lists.add(new ArrayList<>(2));
        }
        for (int i = 0; i < polygons.size(); i++) {
            for (int r = row(boxes[4 * i + 1]); r <= row(boxes[4 * i + 3]); r++) {
                for (int c = col(boxes[4 * i]); c <= col(boxes[4 * i + 2]); c++) {
                    lists.get(r * cols + c).add(i);
                }
            }
        }
        int[][] filled = new int[lists.size()][];
        for (int b = 0; b < filled.length; b++) {
            filled[b] = lists.get(b).stream().mapToInt(Integer::intValue).toArray();
        }
        return filled;
    }

    int size() {
        return polygons.size();
    }

    List<Position> polygon(int i) {
        return polygons.get(i);
    }

    private int col(double x) {
        return (int) Math.clamp(Math.floor((x - minX) / size), 0, cols - 1);
    }

    private int row(double y) {
        return (int) Math.clamp(Math.floor((y - minY) / size), 0, rows - 1);
    }

    /**
     * Calls {@code each} once for every polygon whose bounding box comes within {@code reach} of
     * the box from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}), in no set order. Every
     * polygon nearer than {@code reach} to a point of that box is among them.
     */
    void near(double x0, double y0, double x1, double y1, double reach, IntConsumer each) {
        double ax = x0 - reach;
        double ay = y0 - reach;
        double bx = x1 + reach;
        double by = y1 + reach;
        query++;
        for (int r = row(ay); r <= row(by); r++) {
            for (int c = col(ax); c <= col(bx); c++) {
                for (int i : buckets[r * cols + c]) {
                    if (stamp[i] != query && overlaps(i, ax, ay, bx, by)) {
                        stamp[i] = query;
                        each.accept(i);
                    }
                }
            }
        }
    }

    private boolean overlaps(int i, double ax, double ay, double bx, double by) {
        boolean x = boxes[4 * i] <= bx && boxes[4 * i + 2] >= ax;
        return x && boxes[4 * i + 1] <= by && boxes[4 * i + 3] >= ay;
    }

    /** How far the bounding boxes span, corner to corner: no two polygons are further apart. */
    double span() {
        return Math.hypot(cols * size, rows * size);
    }
}
