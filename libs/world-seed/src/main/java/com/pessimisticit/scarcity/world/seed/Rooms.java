package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.List;

/**
 * Walls around a room (#296): four {@code stone-wall} rectangles just
 * outside the open room [0, W) x [0, H), so its open space is unchanged.
 */
public final class Rooms {

    /** Wall thickness in metres. */
    public static final double THICKNESS = 0.5;

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
        return List.of(
                wall(-t, -t, width + t, 0.0),
                wall(-t, height, width + t, height + t),
                wall(-t, -t, 0.0, height + t),
                wall(width, -t, width + t, height + t));
    }

    private static Entity wall(double x0, double y0, double x1, double y1) {
        return Prototypes.get("stone-wall").withPolygon(List.of(
                new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1)));
    }
}
