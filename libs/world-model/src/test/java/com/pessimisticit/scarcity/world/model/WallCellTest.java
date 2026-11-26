package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A wall names its cell and takes its shape from it (docs/resources.md, "Walls"). */
class WallCellTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000001";
    private static final String WALL = "entity:00000000-0000-4000-8000-0000000000a1";

    /** Two 1 m squares side by side: floor at x 0..1, wall at x 1..2. */
    private static Cell wallCell() {
        Random rng = new Random(1);
        CellMesh mesh = CellMesh.generate(List.of(
                new CellMesh.Piece(List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1)), true),
                new CellMesh.Piece(List.of(new Position(1, 0), new Position(2, 0), new Position(2, 1), new Position(1, 1)), false)),
                () -> Ids.newId(ResourceKind.CELL, rng));
        return mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.WALL).findFirst().orElseThrow().withVersion(1);
    }

    @Test
    void aWallTakesItsShapeAndMassFromItsCellAndWritesOnlyTheId() {
        Cell cell = wallCell();
        Entity wall = Materials.get("granite").wall(cell).withId(WALL);
        assertThat(wall.cell()).isEqualTo(cell.id());
        assertThat(wall.polygon()).isNull();
        assertThat(wall.shape()).isEqualTo(new Shape.Polygon(cell.polygon()));
        assertThat(wall.position()).isEqualTo(new Position(1.5, 0.5));
        assertThat(wall.mass()).isCloseTo(Materials.get("granite").mass(1.0), within(1e-6));
        String json = WorldJson.toJson(wall);
        assertThat(json).contains("\"cell\":\"" + cell.id() + "\"").doesNotContain("polygon");
    }

    @Test
    void aWallReadBackHasNoShapeUntilResolved() {
        Cell cell = wallCell();
        Entity read = WorldJson.fromJson(WorldJson.toJson(Materials.get("granite").wall(cell).withId(WALL)), Entity.class);
        assertThat(read.resolved()).isFalse();
        assertThatThrownBy(read::shape).isInstanceOf(IllegalStateException.class).hasMessageContaining("isn't resolved");
        AreaView view = AreaView.of(AREA, new Area("a", 0.0), Map.of(WALL, read), Map.of(cell.id(), cell));
        assertThat(view.peek(WALL).shape()).isEqualTo(new Shape.Polygon(cell.polygon()));
        assertThat(view.cells()).containsKey(cell.id());
    }

    @Test
    void aViewWithoutTheWallsCellIsRefused() {
        Entity read = WorldJson.fromJson(WorldJson.toJson(Materials.get("granite").wall(wallCell()).withId(WALL)), Entity.class);
        assertThatThrownBy(() -> AreaView.of(AREA, new Area("a", 0.0), Map.of(WALL, read), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("cell:");
    }

    @Test
    void aCellIsOnlyOneKindOfShape() {
        String json = WorldJson.toJson(Materials.get("granite").wall(wallCell()).withId(WALL))
                .replace("\"cell\"", "\"radius\":0.5,\"cell\"");
        assertThatThrownBy(() -> WorldJson.fromJson(json, Resource.class)).hasMessageContaining("only one");
    }
}
