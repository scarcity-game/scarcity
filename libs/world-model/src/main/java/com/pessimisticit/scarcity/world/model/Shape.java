package com.pessimisticit.scarcity.world.model;

import java.util.List;

/**
 * An entity's shape (docs/resources.md, "Shapes"): a circle or a convex polygon,
 * derived from the flat keys {@code radius} and {@code polygon} by
 * {@link Entity#shape()}. Rules ask the shape and never test which one it is.
 */
public sealed interface Shape {

    /**
     * The first time a point at {@code p}, moving by ({@code vx}, {@code vy}) per unit of time
     * relative to the shape, comes within {@code reach} of it ({@link Sweep}): 0 if it is there
     * already and closing, infinity if it never does.
     */
    double contact(Position p, double vx, double vy, double reach);

    /**
     * The {@code distance} of a {@code contact} log entry with a mover of {@code moverRadius}: the
     * sum of the radii, or the mover's radius for a polygon.
     */
    double contactDistance(double moverRadius);

    /** The smallest axis-aligned box that holds the shape. */
    BoundingBox boundingBox();

    /** An axis-aligned box, in area coordinates. */
    record BoundingBox(double minX, double minY, double maxX, double maxY) {

        public BoundingBox {
            Check.finite(minX, "minX");
            Check.finite(minY, "minY");
            Check.finite(maxX, "maxX");
            Check.finite(maxY, "maxY");
        }
    }

    /** A disc of {@code radius} metres around {@code position}. */
    record Circle(Position position, double radius) implements Shape {

        public Circle {
            Check.required(position, "position");
            Check.positive(radius, "radius");
        }

        @Override
        public double contact(Position p, double vx, double vy, double reach) {
            return Sweep.circle(p, vx, vy, position, radius + reach);
        }

        @Override
        public double contactDistance(double moverRadius) {
            return radius + moverRadius;
        }

        @Override
        public BoundingBox boundingBox() {
            return new BoundingBox(position.x() - radius, position.y() - radius,
                    position.x() + radius, position.y() + radius);
        }
    }

    /** A convex polygon, counter-clockwise, in area coordinates (checked like {@code Entity.polygon}). */
    record Polygon(List<Position> vertices) implements Shape {

        public Polygon {
            vertices = ConvexPolygon.check(vertices);
        }

        @Override
        public double contact(Position p, double vx, double vy, double reach) {
            return Sweep.polygon(p, vx, vy, vertices, reach);
        }

        @Override
        public double contactDistance(double moverRadius) {
            return moverRadius;
        }

        @Override
        public BoundingBox boundingBox() {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Position v : vertices) {
                minX = Math.min(minX, v.x());
                minY = Math.min(minY, v.y());
                maxX = Math.max(maxX, v.x());
                maxY = Math.max(maxY, v.y());
            }
            return new BoundingBox(minX, minY, maxX, maxY);
        }
    }
}
