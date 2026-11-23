package com.pessimisticit.scarcity.world.model;

import java.util.List;

/**
 * When a moving point first comes within a distance ({@code reach}) of a shape: the geometry that
 * contacts and line casts share (docs/resources.md, "Shapes"). The point is at {@code p} at time 0
 * and moves by ({@code vx}, {@code vy}) per unit of time, relative to the shape. A point already
 * within {@code reach} (or within {@link #TOUCH} of it) meets the shape at 0 if it is closing on it,
 * and never if it is not, so a mover that starts touching or overlapping can move out. Infinity when
 * they never meet.
 */
public final class Sweep {

    /** Distance within which two edges count as touching. */
    public static final double TOUCH = 1e-9;

    private Sweep() {
    }

    /** The first time {@code p} comes within {@code reach} of {@code centre}: a quadratic in time. */
    public static double circle(Position p, double vx, double vy, Position centre, double reach) {
        double dx = p.x() - centre.x();
        double dy = p.y() - centre.y();
        double closing = dx * vx + dy * vy;
        if (Math.hypot(dx, dy) <= reach + TOUCH) {
            return closing < 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        }
        double speed2 = vx * vx + vy * vy;
        double disc = closing * closing - speed2 * (dx * dx + dy * dy - reach * reach);
        if (speed2 == 0.0 || closing >= 0.0 || disc < 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return (-closing - Math.sqrt(disc)) / speed2;
    }

    /**
     * The first time {@code p} comes within {@code reach} of the convex, counter-clockwise polygon
     * {@code vertices}: the earliest of each edge (a line moved out by {@code reach}, within the
     * edge's span) and each vertex (a circle of radius {@code reach}).
     */
    public static double polygon(Position p, double vx, double vy, List<Position> vertices, double reach) {
        Nearest near = nearest(p, vertices);
        if (near.distance() <= reach + TOUCH) {
            return near.nx() * vx + near.ny() * vy < 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        }
        double t = Double.POSITIVE_INFINITY;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Position a = vertices.get(i);
            t = Math.min(t, edge(p, vx, vy, a, vertices.get((i + 1) % n), reach));
            t = Math.min(t, circle(p, vx, vy, a, reach));
        }
        return t;
    }

    /**
     * The distance from {@code p} to the polygon (0 inside it), and the direction away from it: from
     * the nearest boundary point to {@code p} outside, the outward normal of the nearest edge inside.
     */
    record Nearest(double distance, double nx, double ny) {
    }

    static Nearest nearest(Position p, List<Position> vertices) {
        Nearest outside = new Nearest(Double.POSITIVE_INFINITY, 0.0, 0.0);
        Nearest inside = new Nearest(0.0, 0.0, 0.0);
        double depth = Double.NEGATIVE_INFINITY;
        boolean in = true;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Position a = vertices.get(i);
            Position b = vertices.get((i + 1) % n);
            double len = Math.hypot(b.x() - a.x(), b.y() - a.y());
            double nx = (b.y() - a.y()) / len;
            double ny = -(b.x() - a.x()) / len;
            double side = (p.x() - a.x()) * nx + (p.y() - a.y()) * ny;
            in &= side <= 0.0;
            if (side > depth) {
                depth = side;
                inside = new Nearest(0.0, nx, ny);
            }
            Nearest toEdge = toSegment(p, a, b);
            if (toEdge.distance() < outside.distance()) {
                outside = toEdge;
            }
        }
        return in ? inside : outside;
    }

    /** The distance from {@code p} to the segment {@code ab}, and the unit direction from it to {@code p}. */
    private static Nearest toSegment(Position p, Position a, Position b) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double s = Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / (ex * ex + ey * ey), 0.0, 1.0);
        double dx = p.x() - (a.x() + s * ex);
        double dy = p.y() - (a.y() + s * ey);
        double d = Math.hypot(dx, dy);
        return d == 0.0 ? new Nearest(0.0, 0.0, 0.0) : new Nearest(d, dx / d, dy / d);
    }

    /**
     * When {@code p} reaches the edge {@code ab}'s line moved out by {@code reach}, if that point is
     * within the edge's span; infinity if it isn't, or if {@code p} isn't moving toward the edge.
     */
    private static double edge(Position p, double vx, double vy, Position a, Position b, double reach) {
        double len = Math.hypot(b.x() - a.x(), b.y() - a.y());
        double ex = (b.x() - a.x()) / len;
        double ey = (b.y() - a.y()) / len;
        double toward = -(vx * ey - vy * ex);
        double out = (p.x() - a.x()) * ey - (p.y() - a.y()) * ex;
        if (toward <= 0.0 || out < reach) {
            return Double.POSITIVE_INFINITY;
        }
        double t = (out - reach) / toward;
        double along = (p.x() + t * vx - a.x()) * ex + (p.y() + t * vy - a.y()) * ey;
        return along >= 0.0 && along <= len ? t : Double.POSITIVE_INFINITY;
    }
}
