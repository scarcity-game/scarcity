package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the test game in one transaction. Idempotent: if the game exists
 * nothing is written (no version bumps on restart). Run it after Flyway.
 * There is no upgrade path: the world started clean with this shape (#111).
 */
public final class Seeder {

    private static final Logger LOG = LoggerFactory.getLogger(Seeder.class);

    private final ResourceStore resourceStore;
    private final TransactionTemplate tx;

    public Seeder(ResourceStore resourceStore, TransactionTemplate tx) {
        this.resourceStore = resourceStore;
        this.tx = tx;
    }

    /** @return true if it seeded, false if the game already existed. */
    public boolean seed() {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> !resourceStore.exists(WorldIds.GAME) && fresh()));
        } catch (DuplicateKeyException e) {
            // Another instance seeded between our exists() and insert; its transaction won.
            LOG.info("test game was seeded concurrently; nothing to do");
            return false;
        }
    }

    /** Versions after: game 2, area 3, pillar 1, orbiter 1 (creating a child bumps its parent). */
    private boolean fresh() {
        create(WorldIds.GAME, null, TestArea.game());
        create(WorldIds.AREA, WorldIds.GAME, TestArea.area());
        create(WorldIds.PILLAR, WorldIds.AREA, TestArea.pillar());
        create(WorldIds.ORBITER, WorldIds.AREA, TestArea.orbiter());
        LOG.info("seeded test game {}", WorldIds.GAME);
        return true;
    }

    private void create(String id, String parent, Resource resource) {
        resourceStore.create(resource.withId(id), parent);
    }
}
