package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Fixed;
import com.pessimisticit.scarcity.world.model.Npc;
import com.pessimisticit.scarcity.world.model.Turn;
import org.junit.jupiter.api.Test;

class FreshSeedTest extends DbTest {

    @Test
    void seedsTheControlledOrbiterAtTimeZero() {
        assertThat(seeder().seed()).isTrue();

        assertThat(version(WorldIds.GAME)).isEqualTo(2);
        assertThat(version(WorldIds.AREA)).isEqualTo(10);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(1);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
        assertThat(get(WorldIds.GAME).children()).containsExactly(WorldIds.AREA);
        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER, WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3, WorldIds.WALLS.get(0), WorldIds.WALLS.get(1),
                WorldIds.WALLS.get(2), WorldIds.WALLS.get(3));

        Area area = (Area) get(WorldIds.AREA);
        assertThat(area.time()).isZero();
        // The orbiter is up from time 0 (#148).
        assertThat(area.activeEntity().entity()).isEqualTo(WorldIds.ORBITER);
        assertThat(area.activeEntity().controller()).isEqualTo(new Controller.Service("orbital"));
        assertThat(area.activeEntity().since()).isZero();
        assertThat(area.pending()).extracting(Turn::entity)
                .containsExactly(WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3);
        for (String id : List.of(WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3)) {
            Entity mouse = (Entity) get(id);
            assertThat(mouse.prototype()).isEqualTo("mouse");
            assertThat(mouse.controller()).isEqualTo(new Controller.Service("microbe"));
        }
        Entity orbiter = (Entity) get(WorldIds.ORBITER);
        assertThat(orbiter).isInstanceOf(Npc.class);
        assertThat(orbiter.prototype()).isEqualTo("orbiter");
        assertThat(orbiter.behaviour()).isNull();
        assertThat(orbiter.controller()).isEqualTo(new Controller.Service("orbital"));
        assertThat(orbiter.actions()).containsExactly("step", "wait", "throw");
        assertThat(orbiter.position().x()).isEqualTo(8.0);
        assertThat(orbiter.speed()).isEqualTo(1.0);
        assertThat(get(WorldIds.PILLAR)).isInstanceOf(Fixed.class);
    }

    @Test
    void seedingAgainWritesNothing() {
        seeder().seed();

        assertThat(seeder().seed()).isFalse();

        assertThat(version(WorldIds.AREA)).isEqualTo(10);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
        assertThat(JDBC.queryForObject("SELECT count(*) FROM area_log", Long.class)).isZero();
    }

    @Test
    void twoInstancesSeedingAtOnceSeedOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> runs = List.of(1, 2).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(go);
                    return seeder().seed();
                }))
                .toList();
        go.countDown();

        assertThat(runs.stream().map(CompletableFuture::join))
                .containsExactlyInAnyOrder(true, false);
        assertThat(version(WorldIds.AREA)).isEqualTo(10);
        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER, WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3, WorldIds.WALLS.get(0), WorldIds.WALLS.get(1),
                WorldIds.WALLS.get(2), WorldIds.WALLS.get(3));
        // Exactly one game of each, one dive area, and the loser left nothing behind.
        assertThat(JDBC.queryForObject("SELECT count(*) FROM resources WHERE id = ?", Long.class, WorldIds.GAME))
                .isEqualTo(1);
        assertThat(JDBC.queryForObject("SELECT count(*) FROM resources WHERE id = ?", Long.class,
                WorldIds.DIVE_GAME)).isEqualTo(1);
        assertThat(get(WorldIds.DIVE_GAME).children()).containsExactly(WorldIds.DIVE_AREA);
        assertThat(JDBC.queryForObject("SELECT count(*) FROM resources WHERE type = 'game'",
                Long.class)).isEqualTo(2);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
