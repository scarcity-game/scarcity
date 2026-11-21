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
            return Boolean.TRUE.equals(tx.execute(s -> seedMissing()));
        } catch (DuplicateKeyException e) {
            // Another instance seeded between our exists() and insert; its transaction won.
            LOG.info("test game was seeded concurrently; nothing to do");
            return false;
        }
    }

    /** Each game on its own, so a deployed world gets the dive game without its test game being touched. */
    private boolean seedMissing() {
        boolean test = !resourceStore.exists(WorldIds.GAME) && fresh();
        boolean dive = !resourceStore.exists(WorldIds.DIVE_GAME) && freshDive();
        return test || dive;
    }

    /** The dive game's depth 0: game, area, stairs up, stairs down, mouse and diver. */
    private boolean freshDive() {
        create(WorldIds.DIVE_GAME, null, DiveGame.game());
        create(WorldIds.DIVE_AREA, WorldIds.DIVE_GAME, DiveGame.area());
        create(WorldIds.DIVE_STAIRS_UP, WorldIds.DIVE_AREA, DiveGame.stairsUp());
        create(WorldIds.DIVE_STAIRS_DOWN, WorldIds.DIVE_AREA, DiveGame.stairsDown());
        create(WorldIds.DIVER, WorldIds.DIVE_AREA, DiveGame.diver());
        create(WorldIds.DIVE_MOUSE, WorldIds.DIVE_AREA, DiveGame.mouse());
        LOG.info("seeded dive game {}", WorldIds.DIVE_GAME);
        return true;
    }

    /** Versions after: game 2, area 6, the rest 1 (creating a child bumps its parent). */
    private boolean fresh() {
        create(WorldIds.GAME, null, TestArea.game());
        create(WorldIds.AREA, WorldIds.GAME, TestArea.area());
        create(WorldIds.PILLAR, WorldIds.AREA, TestArea.pillar());
        create(WorldIds.ORBITER, WorldIds.AREA, TestArea.orbiter());
        create(WorldIds.MOUSE_1, WorldIds.AREA, TestArea.mouse(0));
        create(WorldIds.MOUSE_2, WorldIds.AREA, TestArea.mouse(1));
        create(WorldIds.MOUSE_3, WorldIds.AREA, TestArea.mouse(2));
        LOG.info("seeded test game {}", WorldIds.GAME);
        return true;
    }

    private void create(String id, String parent, Resource resource) {
        resourceStore.create(resource.withId(id), parent);
    }
}
