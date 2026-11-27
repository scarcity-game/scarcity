package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.db.AreaHead;
import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import com.pessimisticit.scarcity.world.model.rules.Departure;
import com.pessimisticit.scarcity.world.seed.Levels;
import java.time.Clock;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Carries an entity that left an area by stairs to the area it arrives in
 * (docs/actions.md, "Crossing areas"), in the advance's transaction: makes
 * the level on a first crossing, puts the entity's placeholder there, and
 * runs the new area up to game time. The arrival area is then published as
 * a reload, not as deltas (docs/resources.md, "Reload"): its resources are
 * new, or written outside an advance (the placeholder).
 */
@Component
class Crossing {

    private final ResourceStore resourceStore;
    private final AreaLock areaLock;
    private final AreaRepository areaRepository;
    private final Engines engines;
    private final RandomGenerator rng;
    private final Clock clock;
    private final LevelMaker levelMaker;

    Crossing(ResourceStore resourceStore, AreaLock areaLock, AreaRepository areaRepository, Engines engines,
            RandomGenerator rng, Clock clock, LevelMaker levelMaker) {
        this.resourceStore = resourceStore;
        this.areaLock = areaLock;
        this.areaRepository = areaRepository;
        this.engines = engines;
        this.rng = rng;
        this.clock = clock;
        this.levelMaker = levelMaker;
    }

    /**
     * Carries every departure of {@code result}, an advance of {@code fromArea}.
     *
     * @return the area of the last departure, null if there were none
     */
    String carry(String fromArea, AdvanceResult result) {
        String arrival = null;
        for (Departure departure : result.departures()) {
            arrival = carry(fromArea, result, departure);
        }
        return arrival;
    }

    private String carry(String fromArea, AdvanceResult result, Departure departure) {
        double gameTime = result.area().time();
        Levels.Level level = departure.made() ? make(fromArea, result, departure) : null;
        String toArea = departure.toArea();
        areaLock.begin(toArea, clock.instant());
        AreaSnapshot snapshot = areaRepository.load(toArea);
        Entity stairs = snapshot.entities().stream().filter(e -> e.id().equals(departure.toStairs())).findFirst()
                .orElseThrow(() -> new IllegalStateException(toArea + " has no stairs " + departure.toStairs()));
        double duration = Arrive.warmUp(snapshot.area().time(), gameTime);
        Entity placeholder = Arrive.placeholder(departure.entity(), stairs.position(), duration);
        resourceStore.create(placeholder.withId(Ids.newId(ResourceKind.ENTITY, rng)), toArea);
        if (level != null) {
            // The made level's log begins with how it was made, the placeholder already in it.
            levelMaker.begin(toArea, level);
        }
        long logSeq = warmUp(toArea, areaLock.begin(toArea, clock.instant()), gameTime);
        areaRepository.publishReload(toArea, logSeq, null);
        return toArea;
    }

    /**
     * Makes the level below {@code fromArea}, generated (#172, #311, {@link Levels}): its stairs up,
     * stairs down, mouse and walls.
     */
    private Levels.Level make(String fromArea, AdvanceResult result, Departure departure) {
        String game = resourceStore.parentOf(fromArea)
                .orElseThrow(() -> new IllegalStateException(fromArea + " is not in a game"));
        Levels.Level level = levelMaker.make(result.area().depth() + 1);
        String toArea = departure.toArea();
        resourceStore.create(level.area().withId(toArea), game);
        resourceStore.create(level.stairsUp(departure.fromStairs(), fromArea).withId(departure.toStairs()), toArea);
        resourceStore.create(level.stairsDown().withId(Ids.newId(ResourceKind.ENTITY, rng)), toArea);
        resourceStore.create(level.mouse().withId(Ids.newId(ResourceKind.ENTITY, rng)), toArea);
        for (Entity wall : level.walls()) {
            resourceStore.create(wall.withId(Ids.newId(ResourceKind.ENTITY, rng)), toArea);
        }
        level.cells().forEach(cell -> resourceStore.create(cell, toArea));
        return level;
    }

    /**
     * Runs the area from its placeholder's start to game time; the placeholder's arrival is its end.
     * Its batch isn't published: the crossing publishes a reload.
     *
     * @return the area's log head afterwards
     */
    private long warmUp(String areaId, AreaHead head, double gameTime) {
        AreaSnapshot snapshot = areaRepository.load(areaId);
        Optional<AdvanceResult> result = engines.warmUp(snapshot, gameTime, head.nextSeq());
        result.ifPresent(r -> areaRepository.commit(head, snapshot, r, null, false));
        return result.map(AdvanceResult::lastSeq).orElse(head.lastSeq());
    }
}
