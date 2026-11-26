package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of an area inside its {@code width} by {@code height} box from (0, 0) (#387): a
 * simple polygon, counter-clockwise, convex or not. {@link WorldGen#make} keeps the cells whose
 * centroid is inside it; the solid cells along its edge are the area's outer walls
 * ({@link Layout#outer()}), which nothing can dig through.
 */
public interface Outline {

    /** Its name in a level's log ({@code outline.type}). */
    String type();

    /** Its settings as a level's log gives them ({@code outline.params}). */
    Map<String, Object> params();

    /**
     * The polygon in the box, any choice drawn from {@code recorder}; null for the whole box,
     * which keeps every cell and so leaves a tessellation as it is.
     */
    List<Position> draw(double width, double height, Recorder recorder);

    /** The least share of the box it may cover, for sizing what a generator asks of the cells left: 1 for the box. */
    default double leastShare() {
        return 1.0;
    }

    /** The whole box: what every area was before outlines. */
    record Box() implements Outline {

        @Override
        public String type() {
            return "box";
        }

        @Override
        public Map<String, Object> params() {
            return Map.of();
        }

        @Override
        public List<Position> draw(double width, double height, Recorder recorder) {
            return null;
        }
    }

    /**
     * A regular polygon of {@code [minSides, maxSides]} sides ({@code outline.sides}, a uniform index), standing
     * on a flat side and stretched to fill the box: its leftmost, rightmost, lowest and highest points touch the
     * box's edges, so it is regular up to the box's aspect. Four sides is the whole box (null, as {@link Box}).
     */
    record Polygon(int minSides, int maxSides) implements Outline {

        /** Triangles to octagons; one in six is the box. */
        public static final Polygon DEFAULT = new Polygon(3, 8);

        public Polygon {
            if (minSides < 3 || maxSides < minSides) {
                throw new IllegalArgumentException("polygon: need 3 <= minSides <= maxSides");
            }
        }

        @Override
        public String type() {
            return "polygon";
        }

        @Override
        public Map<String, Object> params() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("minSides", minSides);
            params.put("maxSides", maxSides);
            return params;
        }

        /** The least share of its box any of its polygons covers: a half, for the triangle. */
        @Override
        public double leastShare() {
            double least = 1.0;
            for (int n = minSides; n <= maxSides; n++) {
                least = Math.min(least, share(n));
            }
            return least;
        }

        @Override
        public List<Position> draw(double width, double height, Recorder recorder) {
            int n = minSides + recorder.index("outline.sides", maxSides - minSides + 1);
            return n == 4 ? null : stretched(n, width, height);
        }

        /** The upright {@code n}-gon stretched to the box, counter-clockwise from the right end of its base. */
        static List<Position> stretched(int n, double width, double height) {
            List<Position> unit = upright(n);
            double minX = unit.stream().mapToDouble(Position::x).min().orElseThrow();
            double maxX = unit.stream().mapToDouble(Position::x).max().orElseThrow();
            double minY = unit.stream().mapToDouble(Position::y).min().orElseThrow();
            double maxY = unit.stream().mapToDouble(Position::y).max().orElseThrow();
            List<Position> polygon = new ArrayList<>(n);
            for (Position p : unit) {
                polygon.add(new Position(width * (p.x() - minX) / (maxX - minX),
                        height * (p.y() - minY) / (maxY - minY)));
            }
            return polygon;
        }

        /** A regular {@code n}-gon on the unit circle with a flat side at the bottom, counter-clockwise. */
        private static List<Position> upright(int n) {
            List<Position> unit = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double angle = -Math.PI / 2 + Math.PI / n + 2 * Math.PI * i / n;
                unit.add(new Position(Math.cos(angle), Math.sin(angle)));
            }
            return unit;
        }

        /** The share of its box the stretched {@code n}-gon covers (stretching keeps it). */
        private static double share(int n) {
            List<Position> unit = upright(n);
            double w = unit.stream().mapToDouble(Position::x).max().orElseThrow()
                    - unit.stream().mapToDouble(Position::x).min().orElseThrow();
            double h = unit.stream().mapToDouble(Position::y).max().orElseThrow()
                    - unit.stream().mapToDouble(Position::y).min().orElseThrow();
            return 0.5 * n * Math.sin(2 * Math.PI / n) / (w * h);
        }
    }

    /**
     * A star-shaped polygon round the box's middle: {@code [minVertices, maxVertices]} vertices
     * ({@code outline.vertices}), the {@code i}-th at the angle {@code 2π (i + jitter) / n}, its
     * jitter uniform in {@code [-JITTER, JITTER]} ({@code outline.vertex[i].angle}), and at
     * {@code reach} times the distance to the box's edge that way, {@code reach} uniform in
     * {@code [minReach, 1]} ({@code outline.vertex[i].reach}). The angles rise, so the polygon is
     * simple; its vertices reach out unevenly, so it is mostly not convex.
     */
    record Star(int minVertices, int maxVertices, double minReach) implements Outline {

        /** How far a vertex's angle may move from even spacing, in steps: under half, so the angles rise. */
        public static final double JITTER = 0.3;

        public static final Star DEFAULT = new Star(5, 12, 0.6);

        public Star {
            if (minVertices < 3 || maxVertices < minVertices) {
                throw new IllegalArgumentException("star: need 3 <= minVertices <= maxVertices");
            }
            if (!(minReach > 0.0) || minReach > 1.0) {
                throw new IllegalArgumentException("star: need 0 < minReach <= 1, got " + minReach);
            }
        }

        @Override
        public String type() {
            return "star";
        }

        /**
         * {@code minReach²} of the share of the box a polygon with its vertices on the box's edge
         * covers, which is at least a half: every vertex as near the middle as it may be.
         */
        @Override
        public double leastShare() {
            return 0.5 * minReach * minReach;
        }

        @Override
        public Map<String, Object> params() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("minVertices", minVertices);
            params.put("maxVertices", maxVertices);
            params.put("minReach", minReach);
            params.put("jitter", JITTER);
            return params;
        }

        @Override
        public List<Position> draw(double width, double height, Recorder recorder) {
            int n = minVertices + recorder.index("outline.vertices", maxVertices - minVertices + 1);
            List<Position> polygon = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double jitter = recorder.uniform("outline.vertex[" + i + "].angle", -JITTER, JITTER);
                double reach = recorder.uniform("outline.vertex[" + i + "].reach", minReach, 1.0);
                double angle = 2 * Math.PI * (i + jitter) / n;
                double toEdge = toEdge(width / 2, height / 2, angle);
                polygon.add(new Position(width / 2 + reach * toEdge * Math.cos(angle),
                        height / 2 + reach * toEdge * Math.sin(angle)));
            }
            return polygon;
        }

        /** How far from the middle of a box of half-sides {@code hx}, {@code hy} its edge is at {@code angle}. */
        private static double toEdge(double hx, double hy, double angle) {
            double cos = Math.abs(Math.cos(angle));
            double sin = Math.abs(Math.sin(angle));
            return Math.min(cos == 0.0 ? Double.POSITIVE_INFINITY : hx / cos,
                    sin == 0.0 ? Double.POSITIVE_INFINITY : hy / sin);
        }
    }

    /** Whether {@code p} is inside {@code polygon} (even-odd rule; a simple polygon, either way round). */
    static boolean contains(List<Position> polygon, Position p) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Position a = polygon.get(i);
            Position b = polygon.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }
}
