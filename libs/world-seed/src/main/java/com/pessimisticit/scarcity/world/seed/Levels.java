package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.Layout;
import com.pessimisticit.scarcity.world.gen.MaterialDraws;
import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.RoomsAndCorridors;
import com.pessimisticit.scarcity.world.gen.WorldGen;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Material;
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
 * Makes a dive level (#172, #311, #312) with {@code libs/world-gen}, sized for
 * the diver: either squares with rooms and corridors or a Voronoi tessellation
 * with caves, drawn per level ({@code level.strategy}, a uniform index over
 * {@link #STRATEGIES}, so both are possible at every depth; choosing by depth
 * is #312's). The stairs back up are at the layout's up point (where a
 * traveller arrives), the stairs down at its down point and a mouse at a drawn
 * spawn point. Every level, the dive game's depth 0 included, is made this
 * way; a level is a function of its depth and the draws from the random source
 * it is given.
 */
public final class Levels {

    /** The box a level is generated in, in metres. */
    public static final double WIDTH = 24.0;
    public static final double HEIGHT = 18.0;

    /** The largest entity that must pass every corridor: the diver ({@code seed/diver.json}). */
    public static final double PASSER_RADIUS = 0.3;

    /** The generator's settings. */
    public static final RoomsAndCorridors.Settings SETTINGS = RoomsAndCorridors.Settings.DEFAULT;

    /** The Voronoi levels' settings. */
    public static final WorldGen.VoronoiSettings VORONOI = WorldGen.VoronoiSettings.DEFAULT;

    /** The ways a level is made, in the order the {@code level.strategy} draw indexes them. */
    public static final List<String> STRATEGIES = List.of("squares", "voronoi");

    /** The mouse's controller: the microbe service. */
    public static final Controller MICROBE = new Controller.Service("microbe");

    private Levels() {
    }

    /**
     * A generated level: the {@code material} its walls are made of, its walls (without ids), its
     * {@code cells} (with their ids; the walls keep their own polygons until the client draws cells, #354), the
     * stairs up (at {@code up}, where a traveller arrives, leading nowhere yet) and down, its mouse,
     * and how it was made: the {@code tessellation} and {@code generator}, each {@code {type,
     * params}}, and every draw, in order (docs/log.md, {@code snapshot}).
     */
    public record Level(int depth, Material material, List<Entity> walls, List<Cell> cells, Position up, Entity upStairs,
            Entity downStairs, Entity mouse,
            Map<String, Object> tessellation, Map<String, Object> generator, List<Recorder.Named> draws) {

        public Level {
            walls = List.copyOf(walls);
            cells = List.copyOf(cells);
            draws = List.copyOf(draws);
        }

        /** The area, at time 0, with no one to act. */
        public Area area() {
            return Levels.area(depth);
        }

        /** The stairs back up, leading to {@code toStairs}, an entity id in the level above (null: nowhere yet). */
        public Entity stairsUp(String toStairs) {
            return ((Stairs) upStairs).withTo(toStairs);
        }

        /** The stairs down, leading nowhere until someone takes them (the level below is made then). */
        public Entity stairsDown() {
            return downStairs;
        }
    }

    /**
     * The level at {@code depth}, every choice drawn from {@code rng} and recorded: the way it is
     * made ({@code level.strategy}) and its wall material ({@code level.material},
     * {@link MaterialDraws#wall}), then the layout, the mouse's spawn, the mouse's radius, mass and
     * speed and the stairs' masses, each varied around its prototype's ({@link Draws#vary}).
     */
    public static Level make(int depth, RandomGenerator rng) {
        Recorder choosing = new Recorder(rng);
        boolean voronoi = STRATEGIES.get(choosing.index("level.strategy", STRATEGIES.size())).equals("voronoi");
        Material material = MaterialDraws.wall(choosing);
        Layout layout = voronoi
                ? WorldGen.voronoi(WIDTH, HEIGHT, PASSER_RADIUS, VORONOI, rng)
                : WorldGen.squares(WIDTH, HEIGHT, PASSER_RADIUS, SETTINGS, rng);
        Recorder placing = new Recorder(rng);
        Position mouseAt = layout.spawns().get(placing.index("mouse.spawn", layout.spawns().size()));
        List<Recorder.Named> draws = new ArrayList<>(choosing.draws());
        draws.addAll(layout.draws());
        draws.addAll(placing.draws());
        Entity mouse = variedMouse(mouseAt, rng, draws);
        Entity up = varied(Stairs.up("stairs up").withPosition(layout.up()), "stairs.up.mass", rng, draws);
        Entity down = varied(Stairs.down("stairs down").withPosition(layout.down()), "stairs.down.mass", rng, draws);
        return new Level(depth, material, layout.walls(material), layout.cells(), layout.up(), up, down, mouse,
                voronoi ? voronoiTessellation() : tessellation(), voronoi ? caves() : generator(), draws);
    }

    /** The mouse at {@code at}, its radius, mass and speed varied around the prototype's ({@code mouse.*}). */
    private static Entity variedMouse(Position at, RandomGenerator rng, List<Recorder.Named> draws) {
        Entity base = Prototypes.get("mouse");
        Draw.Gaussian radius = Draws.vary(rng, base.radius(), Draws.VARIATION);
        Draw.Gaussian mass = Draws.vary(rng, base.mass(), Draws.VARIATION);
        draws.add(new Recorder.Named("mouse.radius", radius));
        draws.add(new Recorder.Named("mouse.mass", mass));
        Draw.Gaussian speed = Draws.vary(rng, base.speed(), Draws.VARIATION);
        draws.add(new Recorder.Named("mouse.speed", speed));
        return mouse(at, radius.value(), mass.value(), speed.value());
    }

    /** {@code entity} with its mass varied around its prototype's, the draw recorded as {@code name}. */
    private static Entity varied(Entity entity, String name, RandomGenerator rng, List<Recorder.Named> draws) {
        Draw.Gaussian mass = Draws.vary(rng, entity.mass(), Draws.VARIATION);
        draws.add(new Recorder.Named(name, mass));
        return entity.withMass(mass.value());
    }

    private static Map<String, Object> tessellation() {
        double size = WorldGen.cellSize(PASSER_RADIUS);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cols", (int) Math.floor(WIDTH / size));
        params.put("rows", (int) Math.floor(HEIGHT / size));
        params.put("size", size);
        return typed("squares", params);
    }

    private static Map<String, Object> voronoiTessellation() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("width", WIDTH);
        params.put("height", HEIGHT);
        params.put("sites", WorldGen.sites(WIDTH, HEIGHT, PASSER_RADIUS));
        params.put("relaxations", VORONOI.relaxations());
        return typed("voronoi", params);
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

    private static Map<String, Object> caves() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fill", VORONOI.caves().fill());
        params.put("smoothing", VORONOI.caves().smoothing());
        params.put("minRegion", VORONOI.caves().minRegion());
        params.put("minRoomy", VORONOI.caves().minRoomy());
        params.put("attempts", VORONOI.caves().attempts());
        params.put("clearance", PASSER_RADIUS + WorldGen.MARGIN);
        return typed("cave", params);
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
