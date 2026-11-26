package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** What an intelligence service is told of a turn (docs/actions.md, "Intelligence services"). */
class TurnContextsTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-0000000000a1";
    private static final String NEAR = "entity:00000000-0000-4000-8000-0000000000a2";
    private static final String FAR = "entity:00000000-0000-4000-8000-0000000000a3";
    private static final String WALL = "entity:00000000-0000-4000-8000-0000000000a4";

    /** Floor at x 0..1 and wall at x 1..2; the mouse at (0.5, 0.5), a mouse beside it, one 10 m off. */
    private final List<Cell> cells = cells();
    private final Cell floor = cells.stream().filter(c -> c.kind() == Cell.Kind.FLOOR).findFirst().orElseThrow();
    private final Cell wallCell = cells.stream().filter(c -> c.kind() == Cell.Kind.WALL).findFirst().orElseThrow();
    private final AreaSnapshot snapshot = snapshot();

    @Test
    void aShortRangeHoldsTheCellItIsInAndWhatIsBesideIt() {
        TurnRequest r = TurnContexts.of(snapshot, MOUSE, 3.0, 0.2).orElseThrow();
        assertThat(r.cells()).extracting(Cell::id).containsExactly(floor.id());
        // The wall cell is 0.5 m off: neither it nor its wall is in range.
        assertThat(r.entities()).extracting(Entity::id).containsExactly(NEAR);
        assertThat(r.self().id()).isEqualTo(MOUSE);
        assertThat(r.since()).isEqualTo(3.0);
        assertThat(r.range()).isEqualTo(0.2);
        assertThat(r.area()).isEqualTo(AREA);
    }

    @Test
    void aWallIsInRangeWithItsCellWhereverItsCentreIs() {
        TurnRequest r = TurnContexts.of(snapshot, MOUSE, 3.0, 1.0).orElseThrow();
        assertThat(r.cells()).extracting(Cell::id).containsExactlyInAnyOrder(floor.id(), wallCell.id());
        assertThat(r.entities()).extracting(Entity::id).containsExactlyInAnyOrder(NEAR, WALL);
    }

    @Test
    void anEntityThatIsGoneHasNoTurn() {
        assertThat(TurnContexts.of(snapshot, "entity:00000000-0000-4000-8000-0000000000ff", 3.0, 1.0)).isEmpty();
    }

    private AreaSnapshot snapshot() {
        List<Entity> entities = List.of(
                stored(mouse(new Position(0.5, 0.5)), MOUSE),
                stored(mouse(new Position(0.6, 0.5)), NEAR),
                stored(mouse(new Position(10.5, 0.5)), FAR),
                stored(Materials.get("granite").wall(wallCell), WALL));
        Map<String, Cell> byId = new TreeMap<>();
        cells.forEach(c -> byId.put(c.id(), c));
        Area area = (Area) new Area("test", 3.0).withId(AREA);
        return new AreaSnapshot(area, entities, new TreeMap<>(), Map.of(), new TreeMap<>(byId));
    }

    private static Entity stored(Entity entity, String id) {
        return (Entity) entity.withId(id).withVersion(1);
    }

    private static Entity mouse(Position at) {
        return Prototypes.get("mouse").withPosition(at);
    }

    private static List<Cell> cells() {
        Random rng = new Random(1);
        CellMesh mesh = CellMesh.generate(List.of(
                new CellMesh.Piece(List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1)), true),
                new CellMesh.Piece(List.of(new Position(1, 0), new Position(2, 0), new Position(2, 1), new Position(1, 1)), false)),
                () -> Ids.newId(ResourceKind.CELL, rng));
        return mesh.cells().stream().map(c -> (Cell) c.withVersion(1)).toList();
    }
}
