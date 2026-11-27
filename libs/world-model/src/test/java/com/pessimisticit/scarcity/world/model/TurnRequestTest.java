package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TurnRequestTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000004";
    private static final String WALL = "entity:00000000-0000-4000-8000-000000000005";

    @Test
    void aRequestReadsBackWithItsEntitiesAndCellsTyped() {
        Cell cell = wallCell();
        Entity mouse = Prototypes.get("mouse").withActions(List.of("step", "wait"))
                .withPosition(new Position(0.5, 0.5)).withId(MOUSE);
        Entity wall = Materials.get("granite").wall(cell).withId(WALL);
        TurnRequest request = new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, mouse, List.of(wall), List.of(cell), Map.of());

        TurnRequest read = WorldJson.fromJson(WorldJson.toJson(request), TurnRequest.class);

        assertThat(read.self().id()).isEqualTo(MOUSE);
        assertThat(read.self().actions()).containsExactly("step", "wait");
        assertThat(read.entities()).singleElement().satisfies(e -> assertThat(e.cell()).isEqualTo(cell.id()));
        assertThat(read.cells()).singleElement().satisfies(c -> assertThat(c.kind()).isEqualTo(Cell.Kind.WALL));
        assertThat(read.version()).isEqualTo(3);
        assertThat(read.range()).isEqualTo(8.0);
    }

    @Test
    void theGamesFactionsComeWithTheTurnAndAbsentIsNone() {
        Entity mouse = Prototypes.get("mouse").withPosition(new Position(0.5, 0.5)).withId(MOUSE);
        TurnRequest request = new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, mouse, List.of(), List.of(),
                Factions.standard());
        assertThat(WorldJson.fromJson(WorldJson.toJson(request), TurnRequest.class).factions())
                .isEqualTo(Factions.standard());
        String without = WorldJson.toJson(new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, mouse, List.of(), List.of(), null));
        assertThat(without).doesNotContain("factions");
        assertThat(WorldJson.fromJson(without, TurnRequest.class).factions()).isEmpty();
    }

    @Test
    void selfMustBeTheTurnsEntity() {
        Entity other = Prototypes.get("mouse").withId(WALL);
        assertThatThrownBy(() -> new TurnRequest(AREA, MOUSE, 3, 12.0, 8.0, other, List.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("self");
    }

    @Test
    void anAnswerWithoutReportedLeavesTheKeyOut() {
        ActionSpec wait = new ActionSpec("wait", WorldJson.toObject(java.util.Map.of("duration", 1.0)));
        String json = WorldJson.toJson(new TurnAnswer(wait, null));
        assertThat(json).doesNotContain("reported");
        assertThat(WorldJson.fromJson(json, TurnAnswer.class).actionSpec().type()).isEqualTo("wait");
    }

    /** Two 1 m squares side by side: floor at x 0..1, wall at x 1..2. */
    private static Cell wallCell() {
        Random rng = new Random(1);
        CellMesh mesh = CellMesh.generate(List.of(
                new CellMesh.Piece(List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1)), true),
                new CellMesh.Piece(List.of(new Position(1, 0), new Position(2, 0), new Position(2, 1), new Position(1, 1)), false)),
                () -> Ids.newId(ResourceKind.CELL, rng));
        return mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.WALL).findFirst().orElseThrow().withVersion(1);
    }
}
