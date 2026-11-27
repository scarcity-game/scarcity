package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.EntityKind;
import com.pessimisticit.scarcity.world.model.Stairs;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DiveGameTest extends DbTest {

    @Test
    void depthZeroHasStairsAMouseAndTheDiver() {
        assertThat(seeder().seed()).isTrue();

        assertThat(get(WorldIds.DIVE_GAME).children()).containsExactly(WorldIds.DIVE_AREA);
        com.pessimisticit.scarcity.world.model.Game game =
                (com.pessimisticit.scarcity.world.model.Game) get(WorldIds.DIVE_GAME);
        assertThat(game.seed()).as("made from a seed, by this build").isNotNull();
        assertThat(game.seed().build()).isEqualTo("unknown");
        assertThat(game.materialPatterns()).as("a pattern per material, from the seed")
                .isEqualTo(com.pessimisticit.scarcity.world.model.MaterialPatterns.draw(game.seed().value()).patterns());
        String material = ((Entity) get(WorldIds.diveWall(0))).material();
        assertThat(Materials.get(material).name()).as("the walls' drawn material").isEqualTo(material);
        List<String> children = get(WorldIds.DIVE_AREA).children();
        assertThat(children).startsWith(WorldIds.DIVE_STAIRS_UP, WorldIds.DIVE_STAIRS_DOWN, WorldIds.DIVER,
                WorldIds.DIVE_MOUSE);
        List<String> walls = children.subList(4, children.size()).stream().filter(id -> id.startsWith("entity:")).toList();
        assertThat(walls).as("generated walls, a few dozen").hasSizeGreaterThan(4)
                .isEqualTo(IntStream.range(0, walls.size()).mapToObj(WorldIds::diveWall).toList());
        assertThat(walls).allSatisfy(id -> assertThat(((Entity) get(id)).prototype()).isEqualTo("wall"));
        List<String> cells = children.stream().filter(id -> id.startsWith("cell:")).toList();
        assertThat(cells).as("the layout's cells, floor and wall (#353)").hasSizeGreaterThan(walls.size())
                .allSatisfy(id -> assertThat(get(id)).isInstanceOf(Cell.class));
        assertThat(children).as("entities and cells, nothing else").hasSize(4 + walls.size() + cells.size());
        assertThat(generated).as("its log begins with how it was made").containsExactly(WorldIds.DIVE_AREA);
        assertThat(((Entity) get(WorldIds.DIVER)).position()).as("on the stairs up")
                .isEqualTo(((Entity) get(WorldIds.DIVE_STAIRS_UP)).position());
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
    void aStoredMapThatNoLongerFitsTheCatalogueIsRemadeFromTheSeedOnRead() {
        seeder().seed();
        JDBC.update("UPDATE resources SET state = jsonb_set(state, '{materialPatterns}',"
                + " '{\"adamant\": \"ashlar\", \"oak\": \"ashlar\"}') WHERE id = ?", WorldIds.DIVE_GAME);

        com.pessimisticit.scarcity.world.model.Game game =
                (com.pessimisticit.scarcity.world.model.Game) get(WorldIds.DIVE_GAME);

        assertThat(game.remade()).isTrue();
        assertThat(game.materialPatterns())
                .isEqualTo(com.pessimisticit.scarcity.world.model.MaterialPatterns.draw(game.seed().value()).patterns());
        assertThat(seeder().seed()).as("seeding again reads it and writes nothing").isFalse();
    }

    @Test
    void theTestGameIsSeededWithItsMice() {
        seeder().seed();

        assertThat(get(WorldIds.AREA).children()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER,
                WorldIds.MOUSE_1, WorldIds.MOUSE_2, WorldIds.MOUSE_3, WorldIds.WALLS.get(0), WorldIds.WALLS.get(1),
                WorldIds.WALLS.get(2), WorldIds.WALLS.get(3));
        assertThat(version(WorldIds.AREA)).isEqualTo(10);
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
            Levels.Level level = Levels.make(depth, new SplittableRandom(depth));
            assertThat(level.area().depth()).isEqualTo(depth);
            Stairs down = (Stairs) level.stairsDown();
            assertThat(down.to()).isNull();
            assertThat(down.leadsDown()).isTrue();
            assertThat(((Stairs) level.stairsUp(null, null)).leadsDown()).isFalse();
            assertThat(down.position()).isNotEqualTo(level.stairsUp(null, null).position());
            assertThat(level.mouse().entityKind()).isEqualTo(EntityKind.MOB);
            assertThat(level.mouse().controller()).isEqualTo(new Controller.Service("microbe"));
        }
    }
}
