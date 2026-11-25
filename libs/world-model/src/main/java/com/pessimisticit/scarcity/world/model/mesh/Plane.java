package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;

/** Distances between points, segments and convex counter-clockwise polygons, in metres. */
final class Plane {

    private Plane() {
    }

    /** Twice the signed area of a, b, c: positive when c is left of a to b. */
    static double cross(Position a, Position b, Position c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    /** The centre of mass of a convex polygon's area. */
    static Position centroid(List<Position> polygon) {
        Position origin = polygon.get(0);
        double twiceArea = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int i = 1; i < polygon.size() - 1; i++) {
            Position a = polygon.get(i);
            Position b = polygon.get(i + 1);
            double c = cross(origin, a, b);
            twiceArea += c;
            cx += c * (origin.x() + a.x() + b.x());
            cy += c * (origin.y() + a.y() + b.y());
        }
        return new Position(cx / (3.0 * twiceArea), cy / (3.0 * twiceArea));
    }

    /** The point a fraction {@code t} of the way from a to b. */
    static Position along(Position a, Position b, double t) {
        return new Position(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    static double toSegment(Position p, Position a, Position b) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double len2 = ex * ex + ey * ey;
        double t = len2 == 0.0 ? 0.0 : Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / len2, 0.0, 1.0);
        return Math.hypot(p.x() - (a.x() + t * ex), p.y() - (a.y() + t * ey));
    }

    /** Whether {@code p} is inside the polygon or on its boundary. */
    static boolean inside(Position p, List<Position> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            if (cross(polygon.get(i), polygon.get((i + 1) % n), p) < 0.0) {
                return false;
            }
        }
        return true;
    }

    /** From {@code p} to the polygon: 0 inside it. */
    static double toPolygon(Position p, List<Position> polygon) {
        if (inside(p, polygon)) {
            return 0.0;
        }
        double best = Double.POSITIVE_INFINITY;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            best = Math.min(best, toSegment(p, polygon.get(i), polygon.get((i + 1) % n)));
        }
        return best;
    }

    /** Whether the segments ab and cd meet, touching included. */
    static boolean meet(Position a, Position b, Position c, Position d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        if (straddles(d1, d2) && straddles(d3, d4)) {
            return true;
        }
        return touches(d1, a, c, d) || touches(d2, b, c, d) || touches(d3, c, a, b) || touches(d4, d, a, b);
    }

    private static boolean straddles(double u, double v) {
        return u > 0.0 && v < 0.0 || u < 0.0 && v > 0.0;
    }

    /** Whether {@code p}, {@code side} from the line ab, lies on the segment ab. */
    private static boolean touches(double side, Position p, Position a, Position b) {
        return side == 0.0 && toSegment(p, a, b) == 0.0;
    }

    /** Between the segments ab and cd: 0 when they meet. */
    static double segmentToSegment(Position a, Position b, Position c, Position d) {
        if (meet(a, b, c, d)) {
            return 0.0;
        }
        return Math.min(Math.min(toSegment(a, c, d), toSegment(b, c, d)),
                Math.min(toSegment(c, a, b), toSegment(d, a, b)));
    }

    /** From the segment ab to the polygon: 0 when it touches or enters it. */
    static double segmentToPolygon(Position a, Position b, List<Position> polygon) {
        if (inside(a, polygon) || inside(b, polygon)) {
            return 0.0;
        }
        double best = Double.POSITIVE_INFINITY;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            best = Math.min(best, segmentToSegment(a, b, polygon.get(i), polygon.get((i + 1) % n)));
        }
        return best;
    }
}
