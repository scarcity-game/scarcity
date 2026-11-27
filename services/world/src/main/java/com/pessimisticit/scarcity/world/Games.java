package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.seed.ArenaGame;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes new games from a profile (#455, docs/resources.md, "New games"), in one transaction: every
 * resource, the area's log begun with how it was made, and its queue offered, as at a seed.
 */
@Component
class Games {

    /** A made game: its id, its one area's, and the caller's entity in it. */
    record Made(String game, String area, String entity) {
    }

    private final ResourceStore resourceStore;
    private final LevelLog levelLog;
    private final AreaAdvance areaAdvance;
    private final RandomGenerator rng;
    private final WorldBuildProperties worldBuildProperties;
    private final TransactionTemplate tx;

    Games(ResourceStore resourceStore, LevelLog levelLog, AreaAdvance areaAdvance, RandomGenerator rng,
            WorldBuildProperties worldBuildProperties, TransactionTemplate tx) {
        this.resourceStore = resourceStore;
        this.levelLog = levelLog;
        this.areaAdvance = areaAdvance;
        this.rng = rng;
        this.worldBuildProperties = worldBuildProperties;
        this.tx = tx;
    }

    /** An arena watched by the user {@code sub}. */
    Made arena(ArenaGame.Options options, String sub) {
        ArenaGame.Arena arena = ArenaGame.make(options, sub, rng, worldBuildProperties.sha());
        String gameId = arena.game().id();
        String areaId = arena.area().id();
        tx.executeWithoutResult(s -> {
            resourceStore.create(arena.game(), null);
            resourceStore.create(arena.area(), gameId);
            for (Entity entity : arena.walls()) {
                resourceStore.create(entity, areaId);
            }
            for (Entity entity : arena.dens()) {
                resourceStore.create(entity, areaId);
            }
            resourceStore.create(arena.spectator(), areaId);
            levelLog.start(areaId, arena.generation(), arena.draws());
            areaAdvance.refreshQueue(areaId);
        });
        return new Made(gameId, areaId, arena.spectator().id());
    }
}
