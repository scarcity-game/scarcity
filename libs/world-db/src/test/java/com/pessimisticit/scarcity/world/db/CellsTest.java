package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** An area's cells are stored, loaded with it, and a wall that names one is loaded with its shape (docs/resources.md, "Cells"). */
class CellsTest extends DbTest {

    private final Room room;
    private final Cell wallCell;
    private final String wall;

    CellsTest() {
        this.room = new Room(resourceStore, tx);
        Random rng = new Random(3);
        // a floor square at x 10..11 and a solid one beside it, clear of the room's entities
        CellMesh mesh = CellMesh.generate(List.of(
                new CellMesh.Piece(List.of(new Position(10, 0), new Position(11, 0), new Position(11, 1), new Position(10, 1)), true),
                new CellMesh.Piece(List.of(new Position(11, 0), new Position(12, 0), new Position(12, 1), new Position(11, 1)), false)),
                () -> Ids.newId(ResourceKind.CELL, rng));
        this.wall = Ids.newId(ResourceKind.ENTITY);
        this.wallCell = mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.WALL).findFirst().orElseThrow();
        tx.executeWithoutResult(s -> {
            mesh.cells().forEach(c -> resourceStore.create(c, room.area));
            Entity entity = Materials.get("granite").wall(wallCell).withId(wall);
            resourceStore.create(entity, room.area);
        });
    }

    @Test
    void loadGivesTheCellsAndTheWallItsCellsShape() {
        AreaSnapshot loaded = tx.execute(s -> areaRepository.load(room.area));
        assertThat(loaded.cells()).hasSize(2).containsKey(wallCell.id());
        assertThat(loaded.cells().get(wallCell.id()).kind()).isEqualTo(Cell.Kind.WALL);
        Entity read = loaded.entities().stream().filter(e -> e.id().equals(wall)).findFirst().orElseThrow();
        assertThat(read.cell()).isEqualTo(wallCell.id());
        assertThat(read.shape()).isEqualTo(new Shape.Polygon(wallCell.polygon()));
    }

    @Test
    void theFirstSnapshotHoldsTheCells() {
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        List<String> types = new ArrayList<>();
        JsonNode snapshot = areaLogReader.read(room.area, 0, AreaLogReader.MAX_LIMIT, "snapshot").get("entries").get(0);
        snapshot.get("changes").forEach(c -> types.add(c.get("type").asText()));
        assertThat(types).filteredOn("cell"::equals).hasSize(2);
    }
}
