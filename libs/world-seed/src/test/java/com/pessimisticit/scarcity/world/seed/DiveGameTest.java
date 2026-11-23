package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityKind;
import com.pessimisticit.scarcity.world.model.Stairs;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiveGameTest extends DbTest {

    @Test
    void depthZeroHasStairsAMouseAndTheDiver() {
        assertThat(seeder().seed()).isTrue();

        assertThat(get(WorldIds.DIVE_GAME).children()).containsExactly(WorldIds.DIVE_AREA);
        assertThat(get(WorldIds.DIVE_AREA).children()).containsExactly(WorldIds.DIVE_STAIRS_UP,
                WorldIds.DIVE_STAIRS_DOWN, WorldIds.DIVER, WorldIds.DIVE_MOUSE);
        Area area = (Area) get(WorldIds.DIVE_AREA);
        assertThat(area.depth()).isZero();
        assertThat(area.time()).isZero();
        assertThat(area.activeEntity().entity()).isEqualTo(WorldIds.DIVER);
        assertThat(area.pending()).extracting(t -> t.entity()).containsExactly(WorldIds.DIVE_MOUSE);

        assertThat(get(WorldIds.DIVE_STAIRS_UP)).isInstanceOf(Stairs.class);
        assertThat(((Stairs) get(WorldIds.DIVE_STAIRS_DOWN)).to()).isNull();
        Entity mouse = (Entity) get(WorldIds.DIVE_MOUSE);
        assertThat(mouse.entityKind()).isEqualTo(EntityKind.MOB);
        assertThat(mouse.prototype()).isEqualTo("mouse");
        assertThat(mouse.controller()).isEqualTo(new Controller.Service("microbe"));
        Entity diver = (Entity) get(WorldIds.DIVER);
        assertThat(diver.entityKind()).isEqualTo(EntityKind.NPC);
        assertThat(diver.controller()).isEqualTo(new Controller.Service("diver"));
        assertThat(diver.actions()).contains("step", "traverse");
    }

    @Test
    void theTestGameIsSeededWithItsMice() {
        seeder().seed();

        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER,
                WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3);
        assertThat(version(WorldIds.AREA)).isEqualTo(6);
        assertThat(((Entity) get(WorldIds.ORBITER)).controller()).isEqualTo(new Controller.Service("orbital"));
    }

    @Test
    void aWorldWithOnlyTheTestGameGetsTheDiveGame() {
        resourceStore.create(TestArea.game().withId(WorldIds.GAME), null);
        resourceStore.create(TestArea.area().withId(WorldIds.AREA), WorldIds.GAME);

        assertThat(seeder().seed()).isTrue();

        assertThat(resourceStore.exists(WorldIds.DIVER)).isTrue();
        assertThat(get(WorldIds.AREA).children()).isEmpty();
        assertThat(seeder().seed()).isFalse();
    }

    @Test
    void everyLevelHasStairsUpStairsDownAndAMouse() {
        for (int depth : List.of(1, 2, 7)) {
            assertThat(Levels.area(depth).depth()).isEqualTo(depth);
        }
        Stairs down = (Stairs) Levels.stairsDown();
        assertThat(down.to()).isNull();
        assertThat(down.leadsDown()).isTrue();
        assertThat(((Stairs) Levels.stairsUp(null)).leadsDown()).isFalse();
        assertThat(down.position()).isNotEqualTo(Levels.stairsUp("entity:00000000-0000-4000-8000-0000000000aa").position());
        assertThat(Levels.mouse().entityKind()).isEqualTo(EntityKind.MOB);
        assertThat(Levels.mouse().controller()).isEqualTo(new Controller.Service("microbe"));
        assertThat(Levels.mouse()).as("deterministic").usingRecursiveComparison().isEqualTo(Levels.mouse());
    }
}
