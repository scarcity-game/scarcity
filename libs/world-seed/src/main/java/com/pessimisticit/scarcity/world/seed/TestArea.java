package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The test area's fresh states: a 10x8 room at game time 0, the pillar and
 * the orbiter ({@code seed/*.json}), which {@code orbital} controls and
 * which is up from time 0.
 */
public final class TestArea {

    private TestArea() {
    }

    public static Game game() {
        return new Game("Test area");
    }

    public static Area area() {
        return new Area("Test room", 10.0, 8.0, 0.0, 0,
                new Turn(WorldIds.ORBITER, orbiter().controller(), 0.0), List.of());
    }

    public static Entity pillar() {
        return load("pillar");
    }

    public static Entity orbiter() {
        return load("orbiter");
    }

    private static Entity load(String name) {
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
