package com.pessimisticit.scarcity.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A live area a diver got stuck in, as a test resource: its cells and walls (rows of
 * {@code world.resources}, {@code {id, type, version, state}}), the diver and the stairs down it
 * was heading for.
 */
final class AreaFixture {

    static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";

    private final String areaId;
    private final Position stairs;
    private final Map<String, Entity> entities = new HashMap<>();
    private final Map<String, Cell> cells = new HashMap<>();

    AreaFixture(String resource, String areaId, Position stairs) {
        this.areaId = areaId;
        this.stairs = stairs;
        try (InputStream in = AreaFixture.class.getResourceAsStream(resource)) {
            JsonNode root = WorldJson.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (JsonNode row : root.get("resources")) {
                String kind = row.get("type").asText();
                ObjectNode state = (ObjectNode) row.get("state");
                state.put("resourceKind", kind);
                state.put("id", row.get("id").asText());
                state.put("version", row.get("version").asInt());
                if (kind.equals("cell")) {
                    cells.put(row.get("id").asText(), WorldJson.fromJson(state, Cell.class));
                } else {
                    entities.put(row.get("id").asText(), WorldJson.fromJson(state, Entity.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Position stairs() {
        return stairs;
    }

    Map<String, Cell> cells() {
        return cells;
    }

    AreaView viewWithDiverAt(Position at) {
        Map<String, Entity> moved = new HashMap<>(entities);
        moved.put(DIVER, entities.get(DIVER).withPosition(at));
        return AreaView.of(areaId, new Area("fixture", 0.0), moved, cells);
    }

    Optional<List<Position>> path(Position from) {
        return Pathfinder.path(viewWithDiverAt(from), DIVER, stairs);
    }

    /** The open cells' centres more than half a metre from the stairs and within {@code reach} of {@code near}. */
    List<Position> openCellsNear(Position near, double reach) {
        return cells.values().stream().filter(c -> c.kind().open()).map(Cell::position)
                .filter(p -> p.distanceTo(stairs) > 0.5 && p.distanceTo(near) <= reach).toList();
    }

    /** Plans afresh at every point, as the diver does each turn, and goes to the first waypoint. */
    void assertWalkReachesStairs(Position start) {
        List<Position> visited = new ArrayList<>(List.of(start));
        Position at = start;
        for (int turn = 0; turn < 100 && !at.equals(stairs); turn++) {
            Position next = path(at)
                    .orElseThrow(() -> new AssertionError("no way from " + visited.getLast() + ", from " + start))
                    .getFirst();
            for (Position earlier : visited) {
                assertThat(earlier.distanceTo(next)).as("turn %d from %s goes back to %s", turn, start, earlier)
                        .isGreaterThan(1e-6);
            }
            visited.add(next);
            at = next;
        }
        assertThat(at).as("the walk from %s ends at the stairs after %s", start, visited).isEqualTo(stairs);
    }
}
