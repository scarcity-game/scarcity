package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.db.AreaHead;
import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.engine.IntentRejected;
import com.pessimisticit.scarcity.world.engine.LogEntry;
import com.pessimisticit.scarcity.world.engine.Write;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One intent's advance, inside the caller's transaction: lock the area, load
 * it, check that the caller controls the entity (403), advance, commit. The
 * engine checks {@code ifVersion} (409) and the start rules (409, 422).
 */
@Component
class AreaAdvance {

    private static final Logger log = LoggerFactory.getLogger(AreaAdvance.class);

    private final ResourceStore store;
    private final AreaLock lock;
    private final AreaRepository areas;
    private final Catalogue catalogue;
    private final RandomGenerator rng;
    private final Clock clock;
    private final DistributionSummary events;

    AreaAdvance(ResourceStore store, AreaLock lock, AreaRepository areas, Catalogue catalogue, RandomGenerator rng,
            Clock clock, MeterRegistry meters) {
        this.store = store;
        this.lock = lock;
        this.areas = areas;
        this.catalogue = catalogue;
        this.rng = rng;
        this.clock = clock;
        this.events = DistributionSummary.builder("world.advance.events")
                .description("action starts and ends processed by one advance")
                .register(meters);
    }

    /**
     * @throws IntentRejected if the intent can't start; the caller rolls back
     */
    Intents.Advanced run(Controller caller, IntentRequest request) {
        String areaId = store.areaOf(request.entity()).orElseThrow(() -> unknown(request.entity()));
        AreaHead head = lock.begin(areaId, clock.instant());
        AreaSnapshot snapshot = areas.load(areaId);
        Entity entity = snapshot.entities().stream().filter(e -> e.id().equals(request.entity())).findFirst()
                .orElseThrow(() -> unknown(request.entity()));
        if (!caller.equals(entity.state().controller())) {
            throw new IntentRejected(IntentRejected.Reason.NOT_CONTROLLED,
                    "the caller doesn't control " + entity.id());
        }
        AdvanceResult result = Engine.advance(snapshot, request.by(caller), rng, head.nextSeq(), catalogue);
        // null: the publisher takes the current span's traceparent, world.advance's.
        areas.commit(head, snapshot, result, null);
        events.record(result.log().stream().filter(AreaAdvance::isEvent).count());
        result.blocked().forEach((id, why) -> log.warn("{}: its behaviour can't start: {}", id, why));
        return new Intents.Advanced(areaId, result.area().time(), result.lastSeq(), versionAfter(result, entity));
    }

    private static IntentRejected unknown(String entity) {
        return new IntentRejected(IntentRejected.Reason.UNKNOWN_ENTITY, "unknown entity " + entity);
    }

    private static boolean isEvent(LogEntry e) {
        return e.kind() == LogEntry.Kind.ACTION_START || e.kind() == LogEntry.Kind.ACTION_END;
    }

    /** Null when the advance destroyed it. */
    private static Long versionAfter(AdvanceResult result, Entity entity) {
        Optional<Write> write = result.write(entity.id());
        return write.isPresent() ? write.get().version() : Long.valueOf(entity.version());
    }
}
