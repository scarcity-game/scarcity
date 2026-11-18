package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The dive game's fresh states (#172): a depth 0 {@link Levels level} with
 * stairs up and down, a mouse and the diver ({@code seed/diver.json}), which
 * the {@code diver} service controls and which is up from time 0. Levels
 * below are made from the same {@link Levels} definition.
 */
public final class DiveGame {

    /** Where the diver starts, next to the stairs up. */
    public static final Position DIVER_START = new Position(4.0, 4.0);

    private DiveGame() {
    }

    public static Game game() {
        return new Game("Dive game");
    }

    public static Area area() {
        Area level = Levels.area(0);
        return new Area(level.name(), level.width(), level.height(), level.time(), level.depth(),
                new Turn(WorldIds.DIVER, diver().controller(), 0.0),
                List.of(new Turn(WorldIds.DIVE_MOUSE, Levels.MICROBE, 0.0)));
    }

    /** Stairs up at depth 0 lead nowhere: there is no level above, so {@code traverse} refuses them. */
    public static Entity stairsUp() {
        return Levels.stairsUp(null);
    }

    public static Entity stairsDown() {
        return Levels.stairsDown();
    }

    public static Entity mouse() {
        return Levels.mouse();
    }

    public static Entity diver() {
        String path = "/seed/diver.json";
        try (InputStream in = DiveGame.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Entity.class)
                    .withPosition(DIVER_START);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
