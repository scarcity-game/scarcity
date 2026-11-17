package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;

/**
 * Makes the level below an area when someone first takes its stairs
 * (docs/actions.md, "Crossing areas"): an empty room with the stairs back up.
 * What else a level holds is #172's.
 */
public final class Levels {

    /** Width and height of a level. */
    public static final double WIDTH = 10.0;
    public static final double HEIGHT = 8.0;

    /** Where the stairs back up are, and so where the traveller arrives. */
    public static final Position ARRIVAL = new Position(WIDTH / 2, HEIGHT / 2);

    private Levels() {
    }

    /** A level at {@code depth}, at area time 0, with no one to act. */
    public static Area area(int depth) {
        return new Area("Level " + (depth + 1), WIDTH, HEIGHT, 0.0, depth, null, java.util.List.of());
    }

    /** The stairs back up, leading to {@code upStairs}, an entity id in the level above. */
    public static Entity stairsUp(String upStairs) {
        return new Stairs("stairs up").withTo(upStairs).withPosition(ARRIVAL);
    }
}
