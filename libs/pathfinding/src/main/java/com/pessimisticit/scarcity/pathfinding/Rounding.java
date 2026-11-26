package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * A path round circles made into legs a disc can take: each corner the path turns round is a
 * circle of its clearance, the legs run along the tangents from one circle (or point) to the next,
 * and the arc round each circle is a few points on a polygon just outside it, at most 45° apart,
 * so every leg keeps the clearance from the corner. Where two circles leave no tangent (a gap
 * between the mover's diameter and its clearance), their radii shrink to fit.
 */
final class Rounding {

    /** A corner to go round on {@code side} (+1: on the left; -1: on the right) at {@code radius}; radius 0 and side 0 for a point. */
    record Circle(Position centre, int side, double radius) {

        static Circle point(Position p) {
            return new Circle(p, 0, 0.0);
        }

        static Circle of(Funnel.Bend b) {
            return b.corner() != null && b.clearance() > 0.0
                    ? new Circle(b.corner(), b.side(), b.clearance()) : point(b.at());
        }

        Circle withRadius(double newRadius) {
            return new Circle(centre, side, newRadius);
        }
    }

    /**
     * The waypoints after the start, each with the index of the circle it goes round (or is), and
     * the circles whose arc turned back on itself: the path doesn't need them.
     */
    record Rounded(List<Position> points, List<Integer> owners, List<Integer> backward) {
    }

    /** What fits is left this much room, so a tangent exists. */
    private static final double FIT = 0.999;
    private static final double MOST = Math.PI / 4;
    private static final double TINY = 1e-9;

    private Rounding() {
    }

    /** The legs round {@code given}, the first the start and the last the goal; the radii as fitted. */
    static Rounded round(List<Circle> given) {
        int n = given.size();
        Circle[] c = given.toArray(Circle[]::new);
        fit(c);
        fit(c);
        Position[] leave = new Position[n];
        Position[] arrive = new Position[n];
        for (int i = 0; i + 1 < n; i++) {
            Position[] t = tangent(c[i], c[i + 1]);
            leave[i] = t[0];
            arrive[i + 1] = t[1];
        }
        List<Position> points = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        List<Integer> backward = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            int before = points.size();
            if (c[i].radius() == 0.0) {
                points.add(c[i].centre());
            } else if (!arc(c[i], arrive[i], leave[i], points)) {
                backward.add(i);
            }
            for (int k = before; k < points.size(); k++) {
                owners.add(i);
            }
        }
        return new Rounded(points, owners, backward);
    }

    /** Shrinks the radii of neighbouring circles that leave no tangent between them. */
    private static void fit(Circle[] c) {
        for (int i = 0; i + 1 < c.length; i++) {
            Circle a = c[i];
            Circle b = c[i + 1];
            double d = a.centre().distanceTo(b.centre()) * FIT;
            if (Math.abs(b.side() * b.radius() - a.side() * a.radius()) <= d) {
                continue;
            }
            if (a.side() != b.side() && a.side() != 0 && b.side() != 0) {
                c[i] = a.withRadius(Math.min(a.radius(), d / 2));
                c[i + 1] = b.withRadius(Math.min(b.radius(), d / 2));
            } else if (a.radius() > b.radius()) {
                c[i] = a.withRadius(b.radius() + d);
            } else {
                c[i + 1] = b.withRadius(a.radius() + d);
            }
        }
    }

    /** The tangent from {@code a} to {@code b} that has each centre on its side: its two touching points. */
    static Position[] tangent(Circle a, Circle b) {
        Position c1 = a.centre();
        Position c2 = b.centre();
        double dx = c2.x() - c1.x();
        double dy = c2.y() - c1.y();
        double d = Math.hypot(dx, dy);
        if (d == 0.0) {
            return new Position[] {c1, c2};
        }
        double k = b.side() * b.radius() - a.side() * a.radius();
        double lambda = Math.sqrt(Math.max(0.0, d * d - k * k));
        double ux = (lambda * dx + k * dy) / (d * d);
        double uy = (lambda * dy - k * dx) / (d * d);
        double norm = Math.hypot(ux, uy);
        ux /= norm;
        uy /= norm;
        // Left of u is (-uy, ux); a touching point is the centre less side * radius of it.
        double s1 = a.side() * a.radius();
        double s2 = b.side() * b.radius();
        return new Position[] {new Position(c1.x() + s1 * uy, c1.y() - s1 * ux),
            new Position(c2.x() + s2 * uy, c2.y() - s2 * ux)};
    }

    /**
     * The points round {@code circle}, outside it, from {@code from} to {@code to} on its side;
     * false (and no points) when that is more than half a turn: the path turns the other way there.
     */
    private static boolean arc(Circle circle, Position from, Position to, List<Position> points) {
        Position centre = circle.centre();
        double a = Math.atan2(from.y() - centre.y(), from.x() - centre.x());
        double b = Math.atan2(to.y() - centre.y(), to.x() - centre.x());
        double sweep = normal(circle.side() > 0 ? b - a : a - b);
        if (sweep < TINY || sweep > 2 * Math.PI - TINY) {
            points.add(from);
            return true;
        }
        if (sweep > Math.PI) {
            return false;
        }
        int m = (int) Math.ceil(sweep / MOST);
        double half = sweep / (2 * m);
        double out = circle.radius() / Math.cos(half);
        for (int j = 1; j <= m; j++) {
            double angle = a + circle.side() * (2 * j - 1) * half;
            points.add(new Position(centre.x() + out * Math.cos(angle), centre.y() + out * Math.sin(angle)));
        }
        return true;
    }

    private static double normal(double angle) {
        double a = angle % (2 * Math.PI);
        return a < 0 ? a + 2 * Math.PI : a;
    }
}
