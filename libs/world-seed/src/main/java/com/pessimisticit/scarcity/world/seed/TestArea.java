package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The test area's fresh states: a 10x8 room at game time 0 inside granite walls, the pillar (the
 * {@code pillar} prototype, granite, its mass derived) and the orbiter ({@code seed/orbiter.json}),
 * which {@code orbital} controls and which is up from time 0. The game is built by hand, so it
 * draws nothing: its material patterns are each material's first ({@link Materials#fallbackPatterns}).
 */
public final class TestArea {

    /** The room's width and height. */
    public static final double WIDTH = 10.0;
    public static final double HEIGHT = 8.0;

    /** Where the pillar stands. */
    public static final Position PILLAR = new Position(5.0, 4.0);

    private TestArea() {
    }

    /** Where the mice start; they may go anywhere and collide with the orbiter (collisions over time are the point). */
    public static final List<Position> MICE = List.of(
            new Position(1.0, 1.0), new Position(9.0, 1.0), new Position(9.0, 7.0));

    public static Game game() {
        return new Game("Test area", Materials.fallbackPatterns());
    }

    public static Area area() {
        return new Area("Test room", 0.0, 0,
                new Turn(WorldIds.ORBITER, orbiter().controller(), 0.0),
                List.of(new Turn(WorldIds.MOUSE_1, Levels.MICROBE, 0.0),
                        new Turn(WorldIds.MOUSE_2, Levels.MICROBE, 0.0),
                        new Turn(WorldIds.MOUSE_3, Levels.MICROBE, 0.0)));
    }

    /** The four granite walls around the room (#296). */
    public static List<Entity> walls() {
        return Rooms.enclose(WIDTH, HEIGHT);
    }

    public static Entity pillar() {
        return Prototypes.get("pillar").withPosition(PILLAR);
    }

    public static Entity orbiter() {
        return load("orbiter");
    }

    /** The {@code index}th mouse (0 to 2), controlled by {@code microbe}. */
    public static Entity mouse(int index) {
        return Levels.mouse(MICE.get(index));
    }

    static Entity load(String name) {
        String path = "/seed/" + name + ".json";
        try (InputStream in = TestArea.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Entity.class);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
