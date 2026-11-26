package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.CaveGenerator;
import com.pessimisticit.scarcity.world.gen.Generator;
import com.pessimisticit.scarcity.world.gen.Layout;
import com.pessimisticit.scarcity.world.gen.MaterialDraws;
import com.pessimisticit.scarcity.world.gen.Outline;
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
import com.pessimisticit.scarcity.world.model.Materials;
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

    /** The largest entity that must pass every corridor: the diver ({@code seed/diver.json}). */
    public static final double PASSER_RADIUS = 0.3;

    /**
     * How open a level is, drawn per level (#395) as a truncated gaussian centred on the old fixed value
     * ({@code level.openness.<what>}; the constants are {@link LevelShape}'s), and what it makes of the generator.
     */
    private record Openness(String what, double mean, double min, double max, GeneratorOf generator) {

        double draw(Recorder recorder) {
            return recorder.truncatedGaussian("level.openness." + what, mean, LevelShape.OPENNESS_SD * mean, min, max);
        }
    }

    /**
     * Rooms and corridors: the room sides are today's times the drawn {@code factor}, but never under
     * {@link #ROOM_CELLS} cells across (a room a cell or two across cannot be made), the largest at least half as
     * big again as the smallest.
     */
    /** The generator for a drawn openness value, a level's shape and the least share of its box the outline covers. */
    @FunctionalInterface
    private interface GeneratorOf {
        Generator of(double openness, LevelShape shape, double share);
    }

    private static RoomsAndCorridors rooms(double factor, LevelShape shape, double share) {
        RoomsAndCorridors.Settings base = RoomsAndCorridors.Settings.DEFAULT;
        double min = Math.max(base.minSide() * factor, ROOM_CELLS * shape.cellSide());
        double max = Math.max(base.maxSide() * factor, 1.5 * min);
        return new RoomsAndCorridors(new RoomsAndCorridors.Settings(base.minRooms(), base.maxRooms(), min, max,
                ROOM_ATTEMPTS));
    }

    /** The fewest cells across a room (today's smallest, 2.4 m of 0.8 m cells, is three). */
    private static final double ROOM_CELLS = 2.0;

    /**
     * Caves: the drawn {@code fill}, and {@code minRoomy}, the cells a disc must reach, kept to
     * {@link #ROOMY_SHARE} of the level's cells (at most today's 40), since a small level has few cells to ask for:
     * the cells of the least {@code share} of its box the outline may cover ({@link Outline#leastShare}).
     */
    private static CaveGenerator caves(double fill, LevelShape shape, double share) {
        CaveGenerator.Settings base = CaveGenerator.Settings.DEFAULT;
        double cells = share * shape.width() * shape.height() / (shape.cellSide() * shape.cellSide());
        int roomy = (int) Math.max(1, Math.min(base.minRoomy(), Math.floor(ROOMY_SHARE * cells)));
        return new CaveGenerator(new CaveGenerator.Settings(fill, base.smoothing(), base.minRegion(), roomy,
                CAVE_ATTEMPTS));
    }

    /** How often a room that touches another is drawn again: today's 20 times five, as rooms are big for a small level. */
    private static final int ROOM_ATTEMPTS = 100;

    /** How often a cave is drawn again before giving up: today's 10 times four, as a level with few cells fails a draw more often. */
    private static final int CAVE_ATTEMPTS = 40;

    /** The share of a level's cells a cave must have a disc-sized way through; today's 40 of 675 is 6%. */
    private static final double ROOMY_SHARE = 0.06;

    private static final Openness ROOMS_OPENNESS = new Openness("roomSides", LevelShape.ROOMS_MEAN, LevelShape.ROOMS_MIN, LevelShape.ROOMS_MAX,
            Levels::rooms);
    private static final Openness CAVES_OPENNESS = new Openness("fill", LevelShape.FILL_MEAN,
            LevelShape.FILL_MIN, LevelShape.FILL_MAX, Levels::caves);

    /**
     * A way a level is made (#386): a tessellation and, from its openness draw, a generator; named by the
     * tessellation's type. The level's log describes both as {@code {type, params}}, from them.
     */
    private record Strategy(Tessellation tessellation, Openness openness) {

        String name() {
            return tessellation.type();
        }
    }

    /** The ways a level is made, in the order the {@code level.strategy} draw indexes them. */
    private static final List<Strategy> WAYS = List.of(
            new Strategy(new Tessellation.Squares(), ROOMS_OPENNESS),
            new Strategy(Tessellation.Voronoi.DEFAULT, CAVES_OPENNESS),
            new Strategy(Tessellation.Penrose.DEFAULT, CAVES_OPENNESS));

    /** The ways' names, in the order the {@code level.strategy} draw indexes them. */
    public static final List<String> STRATEGIES = WAYS.stream().map(Strategy::name).toList();

    /**
     * The shapes a level may have (#387), in the order the {@code level.outline} draw indexes them:
     * its whole box, or a star-shaped polygon in it, mostly not convex. The walls on its edge are
     * bedrock ({@link Materials#BEDROCK}).
     */
    private static final List<Outline> OUTLINES = List.of(new Outline.Box(), Outline.Star.DEFAULT);

    /** The mouse's controller: the microbe service. */
    public static final Controller MICROBE = new Controller.Service("microbe");

    private Levels() {
    }

    /**
     * A generated level: its {@code width} and {@code height} (the box [0, width) x [0, height), in metres) and
     * {@code cellSide} (#395), the {@code material} its walls are made of, its walls (without ids), its
     * {@code cells} (with their ids; the walls keep their own polygons until the client draws cells, #354), the
     * stairs up (at {@code up}, where a traveller arrives, leading nowhere yet) and down, its mouse,
     * and how it was made: the {@code tessellation} and {@code generator}, each {@code {type,
     * params}}, and every draw, in order (docs/log.md, {@code snapshot}).
     */
    public record Level(int depth, double width, double height, double cellSide, Material material, List<Entity> walls, List<Cell> cells, Position up, Entity upStairs,
            Entity downStairs, Entity mouse,
            Map<String, Object> tessellation, Map<String, Object> generator, Map<String, Object> outline,
            List<Recorder.Named> draws) {

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
     * {@link MaterialDraws#wall}), its size ({@link LevelShape}: {@code level.width}, {@code level.height},
     * {@code level.cellSide}) and openness ({@code level.openness.*}), then the layout, the mouse's spawn, the mouse's radius, mass and
     * speed and the stairs' masses, each varied around its prototype's ({@link Draws#vary}).
     */
    public static Level make(int depth, RandomGenerator rng) {
        return make(depth, rng, LevelShape.Ranges.FULL);
    }

    /** {@link #make(int, RandomGenerator)} with the size and cell side drawn from {@code ranges}. */
    public static Level make(int depth, RandomGenerator rng, LevelShape.Ranges ranges) {
        Recorder choosing = new Recorder(rng);
        Strategy way = WAYS.get(choosing.index("level.strategy", WAYS.size()));
        Material material = MaterialDraws.wall(choosing);
        LevelShape shape = LevelShape.draw(choosing, PASSER_RADIUS, ranges);
        Outline outline = OUTLINES.get(choosing.index("level.outline", OUTLINES.size()));
        Generator generator = way.openness().generator().of(way.openness().draw(choosing), shape,
                outline.leastShare());
        Layout layout = WorldGen.make(new WorldGen.Frame(shape.width(), shape.height(), shape.cellSide(), outline),
                PASSER_RADIUS, way.tessellation(), generator, rng);
        Recorder placing = new Recorder(rng);
        Position mouseAt = layout.spawns().get(placing.index("mouse.spawn", layout.spawns().size()));
        List<Recorder.Named> draws = new ArrayList<>(choosing.draws());
        draws.addAll(layout.draws());
        draws.addAll(placing.draws());
        Entity mouse = variedMouse(mouseAt, rng, draws);
        Entity up = varied(Stairs.up("stairs up").withPosition(layout.up()), "stairs.up.mass", rng, draws);
        Entity down = varied(Stairs.down("stairs down").withPosition(layout.down()), "stairs.down.mass", rng, draws);
        return new Level(depth, shape.width(), shape.height(), shape.cellSide(), material,
                layout.walls(material, Materials.get(Materials.BEDROCK)),
                layout.cells(), layout.up(), up, down, mouse,
                typed(way.tessellation().type(),
                        way.tessellation().params(shape.width(), shape.height(), shape.cellSide())),
                typed(generator.type(), generator.params(PASSER_RADIUS + WorldGen.MARGIN)),
                typed(outline.type(), outline.params()), draws);
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
