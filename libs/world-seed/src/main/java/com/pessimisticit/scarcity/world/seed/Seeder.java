package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
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

    private final ResourceStore store;
    private final TransactionTemplate tx;

    public Seeder(ResourceStore store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    /** @return true if it seeded, false if the game already existed. */
    public boolean seed() {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> !store.exists(WorldIds.GAME) && fresh()));
        } catch (DuplicateKeyException e) {
            // Another instance seeded between our exists() and insert; its transaction won.
            LOG.info("test game was seeded concurrently; nothing to do");
            return false;
        }
    }

    /** Versions after: game 2, area 3, pillar 1, orbiter 1 (creating a child bumps its parent). */
    private boolean fresh() {
        create(WorldIds.GAME, ResourceType.GAME, null, TestArea.game());
        create(WorldIds.AREA, ResourceType.AREA, WorldIds.GAME, TestArea.area());
        create(WorldIds.PILLAR, ResourceType.ENTITY, WorldIds.AREA, TestArea.pillar());
        create(WorldIds.ORBITER, ResourceType.ENTITY, WorldIds.AREA, TestArea.orbiter());
        LOG.info("seeded test game {}", WorldIds.GAME);
        return true;
    }

    private void create(String id, ResourceType type, String parent, Record state) {
        store.create(new Resource(id, type, 1, List.of(), WorldJson.toObject(state)), parent);
    }
}
