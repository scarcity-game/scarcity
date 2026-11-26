package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.GameSeed;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The dive game's fresh states (#172, #311): a generated depth 0
 * {@link Levels level} with stairs up and down, a mouse and the diver
 * ({@code seed/diver.json}) on the stairs up, which the {@code diver} service
 * controls and which is up from time 0. Levels below are made the same way.
 */
public final class DiveGame {

    private DiveGame() {
    }

    /** The game made from {@code seed}: its material patterns are drawn from it. */
    public static Game game(GameSeed seed) {
        return new Game("Dive game", seed);
    }

    /** Depth 0, drawn from {@code rng}. */
    public static Levels.Level level(RandomGenerator rng) {
        return level(rng, LevelShape.Ranges.FULL);
    }

    /** Depth 0, drawn from {@code rng}, its size and cell side from {@code ranges}. */
    public static Levels.Level level(RandomGenerator rng, LevelShape.Ranges ranges) {
        return Levels.make(0, rng, ranges);
    }

    /** Depth 0's area, with the diver up and the mouse pending. */
    public static Area area() {
        Area level = Levels.area(0);
        return new Area(level.name(), level.time(), level.depth(),
                new Turn(WorldIds.DIVER, diver().controller(), 0.0),
                List.of(new Turn(WorldIds.DIVE_MOUSE, Levels.MICROBE, 0.0)));
    }

    /** The diver as {@code seed/diver.json} has it, with no position. */
    public static Entity diver() {
        String path = "/seed/diver.json";
        try (InputStream in = DiveGame.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Entity.class);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }

    /** The diver on {@code level}'s stairs up, where a traveller would arrive. */
    public static Entity diver(Levels.Level level) {
        return diver().withPosition(level.up());
    }
}
