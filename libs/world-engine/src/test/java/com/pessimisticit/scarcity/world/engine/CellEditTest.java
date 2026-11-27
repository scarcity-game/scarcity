package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@code dig} and {@code fill} through the engine: cell writes, wall entities, the log. */
class CellEditTest {

    private final Random rng = new Random(11);
    private final List<Cell> strip = strip();
    private final Store store = store();

    private List<Cell> strip() {
        List<CellMesh.Piece> pieces = new ArrayList<>();
        for (int col = 0; col < 4; col++) {
            pieces.add(new CellMesh.Piece(List.of(new Position(col, 0), new Position(col + 1.0, 0),
                    new Position(col + 1.0, 1), new Position(col, 1)), col == 0));
        }
        return CellMesh.generate(pieces, () -> Ids.newId(ResourceKind.CELL, rng)).cells();
    }

    private Store store() {
        Entity digger = Room.orbiter().withActions(List.of("step", "wait", "dig", "fill"));
        Store s = new Store(Room.area(10.0)).putCells(strip).put(ORBITER, digger);
        strip.stream().filter(c -> c.kind() == Cell.Kind.WALL).forEach(c ->
                s.put(Ids.newId(ResourceKind.ENTITY, rng), Materials.get("granite").wall(c)));
        return s;
    }

    private String at(int col) {
        return strip.stream().filter(c -> c.position().x() == col + 0.5).findFirst().orElseThrow().id();
    }

    private AdvanceResult run(String type, String cell) {
        return store.advance(Room.intent(new ActionSpec(type, Room.object("{\"cell\":\"" + cell + "\"}"))),
                new ScriptedRandom());
    }

    @Test
    void digWritesTheChangedCellsAtTheNextVersionAndLogsThemWithTheirStates() {
        AdvanceResult r = run("dig", at(1));
        assertThat(store.cell(at(1)).kind()).isEqualTo(Cell.Kind.FLOOR);
        assertThat(store.cell(at(2)).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(r.writes().stream().filter(Write::cell).map(Write::id)).contains(at(1), at(2));
        assertThat(r.writes().stream().filter(Write::cell).map(w -> w.version())).containsOnly(2L);
        assertThat(r.created()).hasSize(1);
        assertThat(r.destroyed()).hasSize(1);
        List<LogChange> cellChanges = r.log().stream().flatMap(e -> e.changes().stream())
                .filter(c -> c.type().equals("cell")).toList();
        assertThat(cellChanges).extracting(LogChange::id).contains(at(1), at(2));
        assertThat(cellChanges).allSatisfy(c -> {
            assertThat(c.op()).isEqualTo(LogChange.Op.CHANGED);
            assertThat(c.version()).isEqualTo(2L);
        });
    }

    @Test
    void fillAfterDigRestoresTheKindsAndCountsVersionsUp() {
        run("dig", at(1));
        AdvanceResult r = run("fill", at(1));
        assertThat(store.cell(at(1)).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(store.cell(at(2)).kind()).isEqualTo(Cell.Kind.ROCK);
        assertThat(r.writes().stream().filter(Write::cell).map(w -> w.version())).containsOnly(3L);
    }
}
