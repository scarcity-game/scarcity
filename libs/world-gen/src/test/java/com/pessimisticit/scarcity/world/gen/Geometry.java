package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;

/** Point and polygon geometry for the tests. */
final class Geometry {

    private Geometry() {
    }

    /** From {@code p} to the convex, counter-clockwise polygon {@code v}: 0 inside it. */
    static double distance(Position p, List<Position> v) {
        if (WorldGenTest.inside(p, v)) {
            return 0.0;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < v.size(); i++) {
            Position a = v.get(i);
            Position b = v.get((i + 1) % v.size());
            double ex = b.x() - a.x();
            double ey = b.y() - a.y();
            double s = Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / (ex * ex + ey * ey), 0.0, 1.0);
            nearest = Math.min(nearest, Math.hypot(p.x() - a.x() - s * ex, p.y() - a.y() - s * ey));
        }
        return nearest;
    }
}
