package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.Stairs;
import java.util.List;

/**
 * Makes the level below an area when someone first takes its stairs
 * (docs/actions.md, "Crossing areas"): a room with the stairs back up, stairs
 * down and a mouse (#172). Every level, the dive game's depth 0 included, is
 * the same definition, so the diver always finds stairs down; a level is a
 * pure function of its depth.
 */
public final class Levels {

    /** Width and height of a level. */
    public static final double WIDTH = 10.0;
    public static final double HEIGHT = 8.0;

    /** Where the stairs back up are, and so where the traveller arrives. */
    public static final Position ARRIVAL = new Position(WIDTH / 2, HEIGHT / 2);

    /** Where the stairs down are. */
    public static final Position DOWN = new Position(1.5, 1.5);

    /** Where a level's mouse starts. */
    public static final Position MOUSE = new Position(8.0, 6.0);

    /** The mouse's controller: the microbe service. */
    public static final Controller MICROBE = new Controller.Service("microbe");

    private Levels() {
    }

    /** A level at {@code depth}, at area time 0, with no one to act. */
    public static Area area(int depth) {
        return new Area("Level " + (depth + 1), WIDTH, HEIGHT, 0.0, depth, null, List.of());
    }

    /** The stairs back up, leading to {@code upStairs}, an entity id in the level above (null: nowhere yet). */
    public static Entity stairsUp(String upStairs) {
        return Stairs.up("stairs up").withTo(upStairs).withPosition(ARRIVAL);
    }

    /** The stairs down, leading nowhere until someone takes them (the level below is made then). */
    public static Entity stairsDown() {
        return Stairs.down("stairs down").withPosition(DOWN);
    }

    /** The level's mouse, controlled by {@code microbe}. */
    public static Entity mouse() {
        return mouse(MOUSE);
    }

    /** A mouse at {@code position}, controlled by {@code microbe}. */
    public static Entity mouse(Position position) {
        return Prototypes.get("mouse").withPosition(position).withActions(List.of("step", "wait"))
                .withController(MICROBE);
    }
}
