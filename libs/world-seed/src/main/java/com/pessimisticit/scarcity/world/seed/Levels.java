package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.CaveGenerator;
import com.pessimisticit.scarcity.world.gen.Generator;
import com.pessimisticit.scarcity.world.gen.Layout;
import com.pessimisticit.scarcity.world.gen.MaterialDraws;
import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.RoomsAndCorridors;
import com.pessimisticit.scarcity.world.gen.Tessellation;
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
 * the diver: squares with rooms and corridors, or a Voronoi tessellation or an
 * aperiodic Penrose rhomb tiling with caves, drawn per level ({@code level.strategy},
 * a uniform index over {@link #STRATEGIES}, so each is possible at every depth; choosing by depth
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

    /**
     * A way a level is made (#386): a tessellation and a generator, named by the tessellation's
     * type. The level's log describes both as {@code {type, params}}, from them.
     */
    private record Strategy(Tessellation tessellation, Generator generator) {

        String name() {
            return tessellation.type();
        }

        /** The tessellation as the level's log gives it. */
        Map<String, Object> described() {
            return typed(tessellation.type(), tessellation.params(WIDTH, HEIGHT, WorldGen.cellSize(PASSER_RADIUS)));
        }

        /** The generator as the level's log gives it. */
        Map<String, Object> generating() {
            return typed(generator.type(), generator.params(PASSER_RADIUS + WorldGen.MARGIN));
        }
    }

    /** The ways a level is made, in the order the {@code level.strategy} draw indexes them. */
    private static final List<Strategy> WAYS = List.of(
            new Strategy(new Tessellation.Squares(), new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT)),
            new Strategy(Tessellation.Voronoi.DEFAULT, new CaveGenerator(CaveGenerator.Settings.DEFAULT)),
            new Strategy(Tessellation.Penrose.DEFAULT, new CaveGenerator(CaveGenerator.Settings.DEFAULT)));

    /** The ways' names, in the order the {@code level.strategy} draw indexes them. */
    public static final List<String> STRATEGIES = WAYS.stream().map(Strategy::name).toList();

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
        Strategy way = WAYS.get(choosing.index("level.strategy", WAYS.size()));
        Material material = MaterialDraws.wall(choosing);
        Layout layout = WorldGen.make(WIDTH, HEIGHT, PASSER_RADIUS, way.tessellation(), way.generator(), rng);
        Recorder placing = new Recorder(rng);
        Position mouseAt = layout.spawns().get(placing.index("mouse.spawn", layout.spawns().size()));
        List<Recorder.Named> draws = new ArrayList<>(choosing.draws());
        draws.addAll(layout.draws());
        draws.addAll(placing.draws());
        Entity mouse = variedMouse(mouseAt, rng, draws);
        Entity up = varied(Stairs.up("stairs up").withPosition(layout.up()), "stairs.up.mass", rng, draws);
        Entity down = varied(Stairs.down("stairs down").withPosition(layout.down()), "stairs.down.mass", rng, draws);
        return new Level(depth, material, layout.walls(material), layout.cells(), layout.up(), up, down, mouse,
                way.described(), way.generating(), draws);
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
