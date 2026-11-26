package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * Convex polygons by the square buckets their bounding boxes touch, so finding the polygon that
 * holds a point looks at a few, whatever the area's size. A bucket is about one polygon wide.
 */
final class Buckets {

    private static final int[] NONE = new int[0];

    private final double minX;
    private final double minY;
    private final double size;
    private final int cols;
    private final int rows;
    private final int[][] buckets;

    Buckets(List<List<Position>> polygons) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (List<Position> polygon : polygons) {
            for (Position p : polygon) {
                x0 = Math.min(x0, p.x());
                y0 = Math.min(y0, p.y());
                x1 = Math.max(x1, p.x());
                y1 = Math.max(y1, p.y());
            }
        }
        boolean empty = polygons.isEmpty();
        minX = empty ? 0.0 : x0;
        minY = empty ? 0.0 : y0;
        double width = empty ? 1.0 : Math.max(x1 - x0, 1e-9);
        double height = empty ? 1.0 : Math.max(y1 - y0, 1e-9);
        size = Math.max(Math.sqrt(width * height / Math.max(1, polygons.size())), 1e-6);
        cols = (int) Math.min(4096, Math.floor(width / size) + 1);
        rows = (int) Math.min(4096, Math.floor(height / size) + 1);
        buckets = fill(polygons);
    }

    private int[][] fill(List<List<Position>> polygons) {
        List<List<Integer>> lists = new ArrayList<>(cols * rows);
        for (int b = 0; b < cols * rows; b++) {
            lists.add(new ArrayList<>(2));
        }
        for (int i = 0; i < polygons.size(); i++) {
            double x0 = Double.POSITIVE_INFINITY;
            double y0 = Double.POSITIVE_INFINITY;
            double x1 = Double.NEGATIVE_INFINITY;
            double y1 = Double.NEGATIVE_INFINITY;
            for (Position p : polygons.get(i)) {
                x0 = Math.min(x0, p.x());
                y0 = Math.min(y0, p.y());
                x1 = Math.max(x1, p.x());
                y1 = Math.max(y1, p.y());
            }
            for (int r = row(y0); r <= row(y1); r++) {
                for (int c = col(x0); c <= col(x1); c++) {
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

    private int col(double x) {
        return (int) Math.clamp(Math.floor((x - minX) / size), 0, cols - 1);
    }

    private int row(double y) {
        return (int) Math.clamp(Math.floor((y - minY) / size), 0, rows - 1);
    }

    /** The polygons whose bounding boxes touch the bucket {@code p} falls in, ascending. */
    int[] near(Position p) {
        if (buckets.length == 0) {
            return NONE;
        }
        return buckets[row(p.y()) * cols + col(p.x())];
    }
}
