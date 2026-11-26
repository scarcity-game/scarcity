package com.pessimisticit.scarcity.world.model.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@code fill} and {@code dig} on a strip of six 1 m squares: floor, wall, then four rocks. */
class CellEffectsTest {

    private final Random rng = new Random(7);
    private final Supplier<String> cellIds = () -> Ids.newId(ResourceKind.CELL, rng);
    private final List<Cell> strip = strip();
    private final FakeContext ctx = context();

    private List<Cell> strip() {
        List<CellMesh.Piece> pieces = new ArrayList<>();
        for (int col = 0; col < 6; col++) {
            pieces.add(new CellMesh.Piece(List.of(new Position(col, 0), new Position(col + 1.0, 0),
                    new Position(col + 1.0, 1), new Position(col, 1)), col == 0));
        }
        return CellMesh.generate(pieces, cellIds).cells().stream().map(c -> c.<Cell>withVersion(1)).toList();
    }

    private FakeContext context() {
        FakeContext fake = new FakeContext(TestArea.ROOM, Map.of(), new ScriptedRandom());
        strip.forEach(c -> fake.cells.put(c.id(), c));
        Map<String, Entity> walls = new HashMap<>();
        for (Cell c : strip) {
            if (c.kind() == Cell.Kind.WALL) {
                walls.put(Ids.newId(ResourceKind.ENTITY, rng), Materials.get("basalt").wall(c));
            }
        }
        walls.forEach((id, wall) -> fake.entities.put(id, wall.withId(id)));
        return fake;
    }

    private String at(int col) {
        return strip.stream().filter(c -> c.position().x() == col + 0.5).findFirst().orElseThrow().id();
    }

    @Test
    void digTurnsAWallIntoFloorRemovesItsEntityAndWallsTheRockBeyond() {
        Dig.INSTANCE.run(new Dig.Params(at(1)), ctx);
        assertThat(ctx.cells.get(at(1)).kind()).isEqualTo(Cell.Kind.FLOOR);
        assertThat(ctx.cells.get(at(2)).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(ctx.cells.get(at(3)).kind()).isEqualTo(Cell.Kind.ROCK);
        assertThat(ctx.destroyed).hasSize(1);
        assertThat(ctx.created).hasSize(1);
        Entity made = ctx.entities.get(ctx.created.getFirst());
        assertThat(made.cell()).isEqualTo(at(2));
        assertThat(made.material()).isEqualTo("basalt");
        assertThat(ctx.result).isInstanceOf(CellEdit.Result.class);
    }

    @Test
    void fillIsTheReverseOfDig() {
        Dig.INSTANCE.run(new Dig.Params(at(1)), ctx);
        Fill.INSTANCE.run(new Fill.Params(at(1)), ctx);
        assertThat(ctx.cells.get(at(1)).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(ctx.cells.get(at(2)).kind()).isEqualTo(Cell.Kind.ROCK);
        assertThat(ctx.created).hasSize(2);
        assertThat(ctx.destroyed).hasSize(2);
        assertThat(ctx.entities.values().stream().filter(e -> e.cell() != null)).hasSize(1);
    }

    @Test
    void fillingTheOnlyFloorCellMakesAWallOfItAndTheNeighbourStaysAWall() {
        Fill.INSTANCE.run(new Fill.Params(at(0)), ctx);
        assertThat(ctx.cells.get(at(0)).kind()).isEqualTo(Cell.Kind.ROCK);
        assertThat(ctx.cells.get(at(1)).kind()).isEqualTo(Cell.Kind.ROCK);
        assertThat(ctx.destroyed).hasSize(1);
    }

    @Test
    void aCellOfTheWrongKindOrAMissingOneIsSkipped() {
        Dig.INSTANCE.run(new Dig.Params(at(0)), ctx);
        assertThat(ctx.result).isEqualTo(new CellEdit.Skipped("cell is floor, not wall"));
        Fill.INSTANCE.run(new Fill.Params(at(1)), ctx);
        assertThat(ctx.result).isEqualTo(new CellEdit.Skipped("cell is wall, not floor"));
        Dig.INSTANCE.run(new Dig.Params(Ids.newId(ResourceKind.CELL, rng)), ctx);
        assertThat(ctx.result).isEqualTo(new CellEdit.Skipped("no such cell"));
        assertThat(ctx.created).isEmpty();
    }

    @Test
    void theActionsValidateTheCellAndTriggerTheEffectAtTheirEnd() {
        Actor actor = new Actor("entity:x", ctx.entities.values().iterator().next(), ctx);
        DigAction.INSTANCE.validate(new CellAction.Params(at(1)), actor);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> DigAction.INSTANCE.validate(new CellAction.Params(at(0)), actor))
                .hasMessageContaining("needs a wall");
        assertThat(FillAction.INSTANCE.onEnd(new CellAction.Params(at(0)), actor))
                .containsExactly(Fill.INSTANCE.call(new Fill.Params(at(0))));
    }
}
