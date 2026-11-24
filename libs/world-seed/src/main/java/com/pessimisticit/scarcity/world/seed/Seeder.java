package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Resource;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the test game and the dive game in one transaction. Idempotent: if a
 * game exists nothing of it is written (no version bumps on restart). Run it
 * after Flyway. There is no upgrade path: the world started clean with this
 * shape (#111).
 */
public final class Seeder {

    /**
     * Told of each generated area once all of it is created, in the seeding transaction, so the
     * caller can begin its log with how it was made (docs/log.md, {@code snapshot}).
     */
    @FunctionalInterface
    public interface Generated {
        void made(String areaId, Levels.Level level);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Seeder.class);

    private final ResourceStore resourceStore;
    private final TransactionTemplate tx;
    private final RandomGenerator rng;
    private final Generated generated;

    /** {@code rng} draws the dive game's depth 0 ({@link Levels#make}). */
    public Seeder(ResourceStore resourceStore, TransactionTemplate tx, RandomGenerator rng, Generated generated) {
        this.resourceStore = resourceStore;
        this.tx = tx;
        this.rng = rng;
        this.generated = generated;
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

    /**
     * The dive game's generated depth 0: game, area, stairs up (where the diver starts) and down,
     * the mouse, the diver and the walls. Stairs up at depth 0 lead nowhere, so {@code traverse}
     * refuses them.
     */
    private boolean freshDive() {
        Levels.Level level = DiveGame.level(rng);
        create(WorldIds.DIVE_GAME, null, DiveGame.game());
        create(WorldIds.DIVE_AREA, WorldIds.DIVE_GAME, DiveGame.area());
        create(WorldIds.DIVE_STAIRS_UP, WorldIds.DIVE_AREA, level.stairsUp(null));
        create(WorldIds.DIVE_STAIRS_DOWN, WorldIds.DIVE_AREA, level.stairsDown());
        create(WorldIds.DIVER, WorldIds.DIVE_AREA, DiveGame.diver(level));
        create(WorldIds.DIVE_MOUSE, WorldIds.DIVE_AREA, level.mouse());
        for (int i = 0; i < level.walls().size(); i++) {
            create(WorldIds.diveWall(i), WorldIds.DIVE_AREA, level.walls().get(i));
        }
        generated.made(WorldIds.DIVE_AREA, level);
        LOG.info("seeded dive game {} with {} walls", WorldIds.DIVE_GAME, level.walls().size());
        return true;
    }

    /** Versions after: game 2, area 10, the rest 1 (creating a child bumps its parent). */
    private boolean fresh() {
        create(WorldIds.GAME, null, TestArea.game());
        create(WorldIds.AREA, WorldIds.GAME, TestArea.area());
        create(WorldIds.PILLAR, WorldIds.AREA, TestArea.pillar());
        create(WorldIds.ORBITER, WorldIds.AREA, TestArea.orbiter());
        create(WorldIds.MOUSE_1, WorldIds.AREA, TestArea.mouse(0));
        create(WorldIds.MOUSE_2, WorldIds.AREA, TestArea.mouse(1));
        create(WorldIds.MOUSE_3, WorldIds.AREA, TestArea.mouse(2));
        List<Entity> walls = TestArea.walls();
        for (int i = 0; i < walls.size(); i++) {
            create(WorldIds.WALLS.get(i), WorldIds.AREA, walls.get(i));
        }
        LOG.info("seeded test game {}", WorldIds.GAME);
        return true;
    }

    private void create(String id, String parent, Resource resource) {
        resourceStore.create(resource.withId(id), parent);
    }
}
