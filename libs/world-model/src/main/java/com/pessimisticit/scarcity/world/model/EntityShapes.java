package com.pessimisticit.scarcity.world.model;

import java.util.List;

/** The geometry keys of an {@link Entity}: which of radius, polygon and cell it has, and the {@link Shape} they make. */
final class EntityShapes {

    private EntityShapes() {
    }

    /**
     * Checks that at most one of {@code cell}, {@code radius} and {@code polygon} is set, and returns the
     * polygon checked by {@link ConvexPolygon} (null when there is none).
     */
    static List<Position> checkKeys(String cell, Double radius, List<Position> polygon) {
        if (cell != null) {
            Ids.require(ResourceKind.CELL.prefix(), cell);
            if (radius != null || polygon != null) {
                throw new IllegalArgumentException("cell: an entity has a radius, a polygon or a cell, only one");
            }
        }
        if (polygon == null) {
            return null;
        }
        if (radius != null) {
            throw new IllegalArgumentException("polygon: an entity has a radius or a polygon, not both");
        }
        return ConvexPolygon.check(polygon);
    }

    /** See {@link Entity#shape()}. {@code id} names the entity in the error for a wall not resolved. */
    static Shape of(String id, Position position, Double radius, List<Position> polygon, String cell,
            List<Position> cellPolygon) {
        if (polygon != null) {
            return new Shape.Polygon(polygon);
        }
        if (cell != null) {
            if (cellPolygon == null) {
                throw new IllegalStateException("cell: " + id + " isn't resolved against " + cell);
            }
            return new Shape.Polygon(cellPolygon);
        }
        if (radius == null) {
            return null;
        }
        if (position == null) {
            throw new IllegalStateException("position: a circle needs a position");
        }
        return new Shape.Circle(position, radius);
    }
}
