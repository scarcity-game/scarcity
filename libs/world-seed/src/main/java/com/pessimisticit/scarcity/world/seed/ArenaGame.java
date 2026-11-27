package com.pessimisticit.scarcity.world.seed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Behaviour;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Faction;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.GameSeed;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.Spawn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The arena (#449, #455): a game made from the {@code arena} profile. One walled room, a den per
 * {@link DenCount} placed apart at drawn spots, each spawning its prototype's mobs for
 * {@code brawler}, and the user who made it as a spectator, a {@code player} who only waits, in the
 * middle. The game's own faction relations make each chosen faction hostile to the others and not
 * to {@code player}, so the mobs fight each other and leave the spectator alone. Every resource
 * has a new id, drawn from the same source.
 */
public final class ArenaGame {

    /** The profile's name. */
    public static final String PROFILE = "arena";

    /** The mob prototypes an arena may use: those that fight ({@code strike}). */
    public static final List<String> MOBS = List.of("goblin", "wild-dog");

    /** The room, in metres. */
    public static final double WIDTH = 24.0;
    public static final double HEIGHT = 18.0;

    /** At most this many dens of one prototype. */
    public static final int MAX_DENS_PER_MOB = 4;

    /** A den's centre keeps this far from the walls, and this far from another den's and the spectator's. */
    public static final double DEN_MARGIN = 1.5;
    public static final double DEN_SPACING = 4.0;

    /** Spots tried per den before the arena is given up (it never is in {@code ArenaGameTest}'s sweep). */
    public static final int DEN_ATTEMPTS = 100;

    /** How many live mobs a den keeps, and its wait between two (seconds). */
    public static final int DEN_CAP = 3;
    public static final Spawn.Interval DEN_INTERVAL = new Spawn.Interval(15.0, 5.0, 5.0, 40.0);

    /** Who plays the mobs. */
    public static final Controller BRAWLER = new Controller.Service("brawler");

    /** The spectator's prototype ({@code seed/spectator.json}). */
    public static final String SPECTATOR = "spectator";

    private ArenaGame() {
    }

    /** {@code count} dens of {@code prototype}, one of {@link #MOBS}. */
    public record DenCount(@JsonProperty(required = true) String prototype, @JsonProperty(required = true) int count) {
        public DenCount {
            Check.text(prototype, "prototype");
            if (!MOBS.contains(prototype)) {
                throw new IllegalArgumentException("dens: " + prototype + " is not an arena mob, one of " + MOBS);
            }
            if (count < 1 || count > MAX_DENS_PER_MOB) {
                throw new IllegalArgumentException(
                        "dens: count of " + prototype + " must be 1 to " + MAX_DENS_PER_MOB + ", got " + count);
            }
        }
    }

    /** The profile's options: the dens, at least one, each prototype once. */
    public record Options(@JsonProperty(required = true) List<DenCount> dens) {
        public Options {
            Check.required(dens, "dens");
            if (dens.isEmpty()) {
                throw new IllegalArgumentException("dens: at least one is needed");
            }
            dens = List.copyOf(dens);
            Set<String> seen = new HashSet<>();
            for (DenCount den : dens) {
                Check.required(den, "dens");
                if (!seen.add(den.prototype())) {
                    throw new IllegalArgumentException("dens: " + den.prototype() + " is listed twice");
                }
            }
        }
    }

    /**
     * A made arena: its resources, each with its id, and how it was made for the area's first log
     * entry: {@code generation} ({@code {profile, options, room}}) and every draw, in order.
     */
    public record Arena(Game game, Area area, Entity spectator, List<Entity> dens, List<Entity> walls,
            Map<String, Object> generation, List<Recorder.Named> draws) {
        public Arena {
            dens = List.copyOf(dens);
            walls = List.copyOf(walls);
            draws = List.copyOf(draws);
        }
    }

    /**
     * An arena with {@code options}, watched by the user {@code sub}. {@code rng} draws the game's seed
     * ({@code build} the server's git sha), the dens' spots ({@code den[i].x}, {@code den[i].y}, every
     * spot tried) and the ids.
     *
     * @throws IllegalStateException if a den finds no spot in {@link #DEN_ATTEMPTS}
     */
    public static Arena make(Options options, String sub, RandomGenerator rng, String build) {
        Controller watcher = new Controller.User(sub);
        Recorder recorder = new Recorder(rng);
        Position middle = new Position(WIDTH / 2, HEIGHT / 2);
        List<Entity> dens = new ArrayList<>();
        List<Position> taken = new ArrayList<>(List.of(middle));
        for (DenCount count : options.dens()) {
            for (int i = 0; i < count.count(); i++) {
                Position spot = spot(recorder, dens.size(), taken);
                taken.add(spot);
                dens.add(den(count.prototype(), spot).withId(Ids.newId(ResourceKind.ENTITY, rng)));
            }
        }
        String spectatorId = Ids.newId(ResourceKind.ENTITY, rng);
        Entity spectator = TestArea.load(SPECTATOR).withPosition(middle).withController(watcher).withId(spectatorId);
        Game game = new Game("Arena", GameSeed.draw(rng, build)).withFactions(factions(options));
        Area area = new Area("Arena", 0.0, 0, new Turn(spectatorId, watcher, 0.0), List.of());
        List<Entity> walls = Rooms.enclose(WIDTH, HEIGHT).stream()
                .map(w -> w.<Entity>withId(Ids.newId(ResourceKind.ENTITY, rng))).toList();
        return new Arena(game.withId(Ids.newId(ResourceKind.GAME, rng)),
                area.withId(Ids.newId(ResourceKind.AREA, rng)), spectator, dens, walls,
                generation(options), recorder.draws());
    }

    /** Each chosen faction hostile to every other chosen one, in the options' order; never to {@code player}. */
    static Map<String, Faction> factions(Options options) {
        Set<String> chosen = new LinkedHashSet<>();
        options.dens().forEach(d -> chosen.add(Prototypes.get(d.prototype()).faction()));
        Map<String, Faction> factions = new LinkedHashMap<>();
        for (String faction : chosen) {
            factions.put(faction, new Faction(chosen.stream().filter(f -> !f.equals(faction)).toList()));
        }
        return factions;
    }

    private static Entity den(String prototype, Position spot) {
        Spawn.Params spawn = new Spawn.Params(List.of(new Spawn.Choice(1.0, prototype)), DEN_CAP, BRAWLER,
                DEN_INTERVAL, null);
        return new EntityTemplate("den").at(spot).withBehaviour(new Behaviour.Repeat(Spawn.specFor(spawn)));
    }

    /** A spot at least {@link #DEN_SPACING} from every taken one, tried uniformly in the room less its margin. */
    private static Position spot(Recorder recorder, int index, List<Position> taken) {
        for (int attempt = 0; attempt < DEN_ATTEMPTS; attempt++) {
            double x = recorder.uniform("den[" + index + "].x", DEN_MARGIN, WIDTH - DEN_MARGIN);
            double y = recorder.uniform("den[" + index + "].y", DEN_MARGIN, HEIGHT - DEN_MARGIN);
            Position spot = new Position(x, y);
            if (taken.stream().allMatch(p -> Math.hypot(p.x() - x, p.y() - y) >= DEN_SPACING)) {
                return spot;
            }
        }
        throw new IllegalStateException("den " + index + ": no spot in " + DEN_ATTEMPTS + " tries");
    }

    private static Map<String, Object> generation(Options options) {
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("width", WIDTH);
        room.put("height", HEIGHT);
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("profile", PROFILE);
        generation.put("options", Map.of("dens", options.dens()));
        generation.put("room", room);
        return generation;
    }
}
