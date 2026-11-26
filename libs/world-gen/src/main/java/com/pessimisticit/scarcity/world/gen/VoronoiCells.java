package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Voronoi cells of some sites in a box, by half-plane clipping: each cell starts as the box and
 * is cut by the bisector with every site near enough to matter. Each corner of a cell remembers
 * the two lines it lies on ({@link Corner}), so {@link VoronoiVertices} can compute every shared
 * vertex once and give each cell that has it the same position.
 */
final class VoronoiCells {

    /** The box's sides, as the lines of a {@link Corner}: negative, so never a site's index. */
    static final int BOTTOM = -1;
    static final int RIGHT = -2;
    static final int TOP = -3;
    static final int LEFT = -4;

    /**
     * A vertex of a cell being clipped, and the two lines it lies on: a site's index {@code j} for
     * the bisector between the cell's site and site {@code j}, or a side of the box.
     */
    record Corner(Position at, int lineA, int lineB) {

        /** The line this corner shares with {@code next}: the edge between them lies on it. */
        int lineTo(Corner next) {
            return lineA == next.lineA || lineA == next.lineB ? lineA : lineB;
        }
    }

    private VoronoiCells() {
    }

    /** One convex, counter-clockwise polygon per site, clipped to [0, width] by [0, height]. */
    static List<List<Position>> clip(double width, double height, List<Position> sites) {
        return corners(width, height, sites).stream()
                .map(cell -> cell.stream().map(Corner::at).toList()).toList();
    }

    /** One cell per site, as its corners. */
    static List<List<Corner>> corners(double width, double height, List<Position> sites) {
        List<List<Corner>> cells = new ArrayList<>(sites.size());
        for (int i = 0; i < sites.size(); i++) {
            cells.add(cell(width, height, sites, i));
        }
        return cells;
    }

    private static List<Corner> cell(double width, double height, List<Position> sites, int i) {
        Position site = sites.get(i);
        List<Corner> polygon = List.of(new Corner(new Position(0.0, 0.0), LEFT, BOTTOM),
                new Corner(new Position(width, 0.0), BOTTOM, RIGHT),
                new Corner(new Position(width, height), RIGHT, TOP),
                new Corner(new Position(0.0, height), TOP, LEFT));
        for (long key : byDistance(sites, i)) {
            int j = (int) key;
            Position other = sites.get(j);
            if (site.distanceTo(other) > 2.0 * reach(polygon, site) + 1e-9) {
                break;
            }
            polygon = cut(polygon, site, other, j);
        }
        return polygon;
    }

    /** The other sites, nearest first, as {@code (float distance bits << 32) | index}: a sortable long. */
    private static long[] byDistance(List<Position> sites, int i) {
        long[] keys = new long[sites.size() - 1];
        int n = 0;
        for (int j = 0; j < sites.size(); j++) {
            if (j != i) {
                float d = (float) sites.get(i).distanceTo(sites.get(j));
                keys[n++] = ((long) Float.floatToIntBits(d) << 32) | j;
            }
        }
        Arrays.sort(keys);
        return keys;
    }

    private static double reach(List<Corner> polygon, Position site) {
        double far = 0.0;
        for (Corner v : polygon) {
            far = Math.max(far, site.distanceTo(v.at()));
        }
        return far;
    }

    /**
     * The part of {@code polygon} nearer {@code site} than {@code other}, site {@code j}
     * (Sutherland-Hodgman, one half-plane). A new corner lies on the bisector and on the edge's line.
     */
    private static List<Corner> cut(List<Corner> polygon, Position site, Position other, int j) {
        double nx = other.x() - site.x();
        double ny = other.y() - site.y();
        if (nx == 0.0 && ny == 0.0) {
            return polygon;
        }
        double limit = (nx * (site.x() + other.x()) + ny * (site.y() + other.y())) / 2.0;
        List<Corner> out = new ArrayList<>(polygon.size() + 1);
        for (int k = 0; k < polygon.size(); k++) {
            Corner a = polygon.get(k);
            Corner b = polygon.get((k + 1) % polygon.size());
            double sa = nx * a.at().x() + ny * a.at().y() - limit;
            double sb = nx * b.at().x() + ny * b.at().y() - limit;
            if (sa <= 0.0) {
                out.add(a);
            }
            if ((sa < 0.0 && sb > 0.0) || (sa > 0.0 && sb < 0.0)) {
                double t = sa / (sa - sb);
                Position at = new Position(a.at().x() + t * (b.at().x() - a.at().x()), a.at().y() + t * (b.at().y() - a.at().y()));
                out.add(new Corner(at, a.lineTo(b), j));
            }
        }
        return out;
    }

    /** The centre of mass of a polygon, or its vertex average when it has no area. */
    static Position centroid(List<Position> polygon) {
        double twiceArea = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int k = 0; k < polygon.size(); k++) {
            Position a = polygon.get(k);
            Position b = polygon.get((k + 1) % polygon.size());
            double cross = a.x() * b.y() - b.x() * a.y();
            twiceArea += cross;
            cx += cross * (a.x() + b.x());
            cy += cross * (a.y() + b.y());
        }
        if (!(twiceArea > 1e-12)) {
            return new Position(polygon.stream().mapToDouble(Position::x).average().orElse(0.0),
                    polygon.stream().mapToDouble(Position::y).average().orElse(0.0));
        }
        return new Position(cx / (3.0 * twiceArea), cy / (3.0 * twiceArea));
    }
}
