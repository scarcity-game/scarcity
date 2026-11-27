package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * Walls around a room (#296): four {@link #MATERIAL granite} {@code wall} rectangles just
 * outside the open room [0, W) x [0, H), so its open space is unchanged, or a ring of trapezoids
 * just outside a regular polygon ({@link #regular}, the arena). The room is built by hand, so its
 * material is fixed, not drawn; each wall weighs its area of granite, 3 m tall.
 */
public final class Rooms {

    /** Wall thickness in metres. */
    public static final double THICKNESS = 0.5;

    /** What the room's walls are made of. */
    public static final String MATERIAL = "granite";

    private Rooms() {
    }

    /** The four walls of a room with the default thickness. */
    public static List<Entity> enclose(double width, double height) {
        return enclose(width, height, THICKNESS);
    }

    /**
     * The four walls of a {@code width} by {@code height} room, in the order south,
     * north, west, east. South and north span the full width plus both corners and
     * west and east the full height plus both corners, so they overlap at the corners.
     * Together they cover [-t, W+t] x [-t, H+t] minus the room.
     */
    public static List<Entity> enclose(double width, double height, double thickness) {
        double t = thickness;
        Material granite = Materials.get(MATERIAL);
        return List.of(
                wall(granite, -t, -t, width + t, 0.0),
                wall(granite, -t, height, width + t, height + t),
                wall(granite, -t, -t, 0.0, height + t),
                wall(granite, width, -t, width + t, height + t));
    }

    /**
     * The walls of a regular polygon of {@code sides} with circumradius {@code radius} round
     * {@code centre}, standing on a side: one trapezoid per side, from the side out to the same
     * polygon {@code thickness} further out, so neighbours meet along the corners' bisectors and
     * cover the ring with no gap or overlap. In order counter-clockwise from the bottom side.
     */
    public static List<Entity> regular(int sides, double radius, Position centre, double thickness) {
        if (sides < 3) {
            throw new IllegalArgumentException("sides: needs 3 or more, got " + sides);
        }
        Material granite = Materials.get(MATERIAL);
        double outer = radius + thickness / Math.cos(Math.PI / sides);
        List<Entity> walls = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            double a0 = corner(sides, i);
            double a1 = corner(sides, i + 1);
            walls.add(granite.wall(List.of(at(centre, radius, a0), at(centre, outer, a0), at(centre, outer, a1),
                    at(centre, radius, a1))));
        }
        return List.copyOf(walls);
    }

    /** The angle of corner {@code i}: the bottom side lies flat, between corners 0 and 1. */
    private static double corner(int sides, int i) {
        return -Math.PI / 2 - Math.PI / sides + 2 * Math.PI * i / sides;
    }

    private static Position at(Position centre, double r, double angle) {
        return new Position(centre.x() + r * Math.cos(angle), centre.y() + r * Math.sin(angle));
    }

    private static Entity wall(Material material, double x0, double y0, double x1, double y1) {
        return material.wall(List.of(
                new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1)));
    }
}
