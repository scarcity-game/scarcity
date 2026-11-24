package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * The candidate waypoints around one obstacle, for a mover that keeps {@code clearance} (its
 * radius plus a margin) from it: the corners of the obstacle grown by {@code clearance}. A path
 * around a convex obstacle bends only at these. {@link Pathfinder} drops those within the
 * clearance of another obstacle.
 */
final class Corners {

    /** A circle is grown into a regular polygon with this many corners, outside the grown circle. */
    static final int CIRCLE_CORNERS = 8;

    /** Below this (1 + cos of the turn at a corner), a corner is sharp and gets three points on an arc. */
    private static final double SHARP = 0.5;

    private Corners() {
    }

    static List<Position> of(Shape shape, double clearance) {
        return switch (shape) {
            case Shape.Circle c -> circle(c, clearance);
            case Shape.Polygon p -> polygon(p.vertices(), clearance);
        };
    }

    private static List<Position> circle(Shape.Circle c, double clearance) {
        double reach = (c.radius() + clearance) / Math.cos(Math.PI / CIRCLE_CORNERS);
        List<Position> corners = new ArrayList<>(CIRCLE_CORNERS);
        for (int k = 0; k < CIRCLE_CORNERS; k++) {
            double a = 2 * Math.PI * k / CIRCLE_CORNERS;
            corners.add(new Position(c.position().x() + reach * Math.cos(a), c.position().y() + reach * Math.sin(a)));
        }
        return corners;
    }

    /**
     * Each vertex of the counter-clockwise polygon moved out to where its two edges, each moved
     * out by {@code clearance}, meet. A sharp vertex, where that point would be far out, gets
     * three points on an arc around it instead.
     */
    private static List<Position> polygon(List<Position> vertices, double clearance) {
        int n = vertices.size();
        List<Position> corners = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Position v = vertices.get(i);
            double[] in = normal(vertices.get((i + n - 1) % n), v);
            double[] out = normal(v, vertices.get((i + 1) % n));
            double cos = in[0] * out[0] + in[1] * out[1];
            if (1.0 + cos >= SHARP) {
                double k = clearance / (1.0 + cos);
                corners.add(new Position(v.x() + k * (in[0] + out[0]), v.y() + k * (in[1] + out[1])));
            } else {
                corners.addAll(arc(v, in, out, clearance));
            }
        }
        return corners;
    }

    /** Points at {@code n1}, the bisector and {@code n2} from {@code v}, far enough out that the chords between them clear it. */
    private static List<Position> arc(Position v, double[] n1, double[] n2, double clearance) {
        double bx = n1[0] + n2[0];
        double by = n1[1] + n2[1];
        double len = Math.hypot(bx, by);
        double[] mid = len == 0.0 ? new double[] {n1[0], n1[1]} : new double[] {bx / len, by / len};
        double half = Math.acos(Math.clamp(n1[0] * mid[0] + n1[1] * mid[1], -1.0, 1.0));
        double reach = clearance / Math.cos(half / 2);
        List<Position> points = new ArrayList<>(3);
        for (double[] d : List.of(n1, mid, n2)) {
            points.add(new Position(v.x() + reach * d[0], v.y() + reach * d[1]));
        }
        return points;
    }

    /** The outward unit normal of the edge {@code a} to {@code b} of a counter-clockwise polygon. */
    private static double[] normal(Position a, Position b) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double len = Math.hypot(ex, ey);
        return new double[] {ey / len, -ex / len};
    }
}
