package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.db.AreaHead;
import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.rules.Arrive;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
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
    private final Catalogue catalogue;
    private final RandomGenerator rng;
    private final Clock clock;

    Crossing(ResourceStore resourceStore, AreaLock areaLock, AreaRepository areaRepository, Catalogue catalogue,
            RandomGenerator rng, Clock clock) {
        this.resourceStore = resourceStore;
        this.areaLock = areaLock;
        this.areaRepository = areaRepository;
        this.catalogue = catalogue;
        this.rng = rng;
        this.clock = clock;
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
        String toArea = departure.made() ? make(fromArea, result, departure) : areaOf(departure.toStairs());
        AreaHead head = areaLock.begin(toArea, clock.instant());
        AreaSnapshot snapshot = areaRepository.load(toArea);
        Entity stairs = snapshot.entities().stream().filter(e -> e.id().equals(departure.toStairs())).findFirst()
                .orElseThrow(() -> new IllegalStateException(toArea + " has no stairs " + departure.toStairs()));
        double duration = Arrive.warmUp(snapshot.area().time(), gameTime);
        Entity placeholder = Arrive.placeholder(departure.entity(), stairs.position(), duration);
        resourceStore.create(placeholder.withId(Ids.newId(ResourceKind.ENTITY, rng)), toArea);
        long logSeq = warmUp(toArea, head, gameTime);
        areaRepository.publishReload(toArea, logSeq, null);
        return toArea;
    }

    /** Makes the level below {@code fromArea} with its stairs up, stairs down and mouse (#172, {@link Levels}). */
    private String make(String fromArea, AdvanceResult result, Departure departure) {
        String game = resourceStore.parentOf(fromArea)
                .orElseThrow(() -> new IllegalStateException(fromArea + " is not in a game"));
        resourceStore.create(Levels.area(result.area().depth() + 1).withId(departure.toArea()), game);
        resourceStore.create(Levels.stairsUp(departure.fromStairs()).withId(departure.toStairs()), departure.toArea());
        resourceStore.create(Levels.stairsDown().withId(Ids.newId(ResourceKind.ENTITY, rng)), departure.toArea());
        resourceStore.create(Levels.mouse().withId(Ids.newId(ResourceKind.ENTITY, rng)), departure.toArea());
        return departure.toArea();
    }

    private String areaOf(String entity) {
        return resourceStore.areaOf(entity)
                .orElseThrow(() -> new IllegalStateException("no area holds the stairs " + entity));
    }

    /**
     * Runs the area from its placeholder's start to game time; the placeholder's arrival is its end.
     * Its batch isn't published: the crossing publishes a reload.
     *
     * @return the area's log head afterwards
     */
    private long warmUp(String areaId, AreaHead head, double gameTime) {
        AreaSnapshot snapshot = areaRepository.load(areaId);
        Optional<AdvanceResult> result = Engine.warmUp(snapshot, gameTime, rng, head.nextSeq(), catalogue);
        result.ifPresent(r -> areaRepository.commit(head, snapshot, r, null, false));
        return result.map(AdvanceResult::lastSeq).orElse(head.lastSeq());
    }
}
