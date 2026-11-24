package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.Layout;
import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.RoomsAndCorridors;
import com.pessimisticit.scarcity.world.gen.WorldGen;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.Stairs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Makes a dive level (#172, #311): squares with rooms and corridors from
 * {@code libs/world-gen}, sized for the diver, with the stairs back up at the
 * layout's up point (where a traveller arrives), the stairs down at its down
 * point and a mouse at a drawn spawn point. Every level, the dive game's
 * depth 0 included, is made this way; a level is a function of its depth and
 * the draws from the random source it is given.
 */
public final class Levels {

    /** The box a level is generated in, in metres. */
    public static final double WIDTH = 24.0;
    public static final double HEIGHT = 18.0;

    /** The largest entity that must pass every corridor: the diver ({@code seed/diver.json}). */
    public static final double PASSER_RADIUS = 0.3;

    /** The generator's settings. */
    public static final RoomsAndCorridors.Settings SETTINGS = RoomsAndCorridors.Settings.DEFAULT;

    /** The mouse's controller: the microbe service. */
    public static final Controller MICROBE = new Controller.Service("microbe");

    private Levels() {
    }

    /**
     * A generated level: its walls (without ids), where the stairs up and down and the mouse go,
     * and how it was made: the {@code tessellation} and {@code generator}, each {@code {type,
     * params}}, and every draw, in order (docs/log.md, {@code snapshot}).
     */
    public record Level(int depth, List<Entity> walls, Position up, Position down, Position mouseAt,
            double mouseRadius, double mouseMass, double mouseSpeed,
            Map<String, Object> tessellation, Map<String, Object> generator, List<Recorder.Named> draws) {

        public Level {
            walls = List.copyOf(walls);
            draws = List.copyOf(draws);
        }

        /** The area, at time 0, with no one to act. */
        public Area area() {
            return Levels.area(depth);
        }

        /** The stairs back up, leading to {@code upStairs}, an entity id in the level above (null: nowhere yet). */
        public Entity stairsUp(String upStairs) {
            return Stairs.up("stairs up").withTo(upStairs).withPosition(up);
        }

        /** The stairs down, leading nowhere until someone takes them (the level below is made then). */
        public Entity stairsDown() {
            return Stairs.down("stairs down").withPosition(down);
        }

        /** The level's mouse, controlled by {@code microbe}. */
        public Entity mouse() {
            return Levels.mouse(mouseAt, mouseRadius, mouseMass, mouseSpeed);
        }
    }

    /** The level at {@code depth}, every choice drawn from {@code rng} and recorded. */
    public static Level make(int depth, RandomGenerator rng) {
        Layout layout = WorldGen.squares(WIDTH, HEIGHT, PASSER_RADIUS, SETTINGS, rng);
        Recorder placing = new Recorder(rng);
        Position mouse = layout.spawns().get(placing.index("mouse.spawn", layout.spawns().size()));
        List<Recorder.Named> draws = new ArrayList<>(layout.draws());
        draws.addAll(placing.draws());
        Entity base = Prototypes.get("mouse");
        Draw.Gaussian radius = Draws.vary(rng, base.radius(), Draws.VARIATION);
        Draw.Gaussian mass = Draws.vary(rng, base.mass(), Draws.VARIATION);
        draws.add(new Recorder.Named("mouse.radius", radius));
        draws.add(new Recorder.Named("mouse.mass", mass));
        Draw.Gaussian speed = Draws.vary(rng, base.speed(), Draws.VARIATION);
        draws.add(new Recorder.Named("mouse.speed", speed));
        return new Level(depth, layout.walls(), layout.up(), layout.down(), mouse, radius.value(), mass.value(),
                speed.value(),
                tessellation(), generator(), draws);
    }

    private static Map<String, Object> tessellation() {
        double size = WorldGen.cellSize(PASSER_RADIUS);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cols", (int) Math.floor(WIDTH / size));
        params.put("rows", (int) Math.floor(HEIGHT / size));
        params.put("size", size);
        return typed("squares", params);
    }

    private static Map<String, Object> generator() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("minRooms", SETTINGS.minRooms());
        params.put("maxRooms", SETTINGS.maxRooms());
        params.put("minSide", SETTINGS.minSide());
        params.put("maxSide", SETTINGS.maxSide());
        params.put("attempts", SETTINGS.attempts());
        return typed("rooms-and-corridors", params);
    }

    /** {@code {type, params}}, in that order. */
    private static Map<String, Object> typed(String type, Map<String, Object> params) {
        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("type", type);
        typed.put("params", Collections.unmodifiableMap(params));
        return Collections.unmodifiableMap(typed);
    }

    /** A level's area at {@code depth}, at area time 0, with no one to act. */
    public static Area area(int depth) {
        return new Area("Level " + (depth + 1), 0.0, depth, null, List.of());
    }

    /** A mouse at {@code position}, controlled by {@code microbe}, with the prototype's radius, mass and speed. */
    public static Entity mouse(Position position) {
        Entity base = Prototypes.get("mouse");
        return mouse(position, base.radius(), base.mass(), base.speed());
    }

    /** A mouse at {@code position}, controlled by {@code microbe}, of the given radius, mass and speed. */
    public static Entity mouse(Position position, double radius, double mass, double speed) {
        return Prototypes.get("mouse").withPosition(position).withRadius(radius).withMass(mass).withSpeed(speed)
                .withActions(List.of("step", "wait")).withController(MICROBE);
    }
}
