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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * #438: the live diver of area:5f0e96a2-1a5b-42b5-a36c-345d06735769 (level 1259) that stepped between two
 * points 0.23 m apart for ever: an opening 0.622 m wide, between a wall's corner and the cells'
 * floor-only vertex, passed the search (2r = 0.6) but not {@link Erosion} (no stretch of it 0.33 m
 * from the wall), so its middle, 0.155 m from the corner, became a waypoint, and the {@link Refiner}
 * could not round it: it added and dropped the same corner until it ran out of rounds. The fixture
 * is that area's cells and walls within 1.5 m of the diver's way to the stairs down.
 */
class StuckDiverTest {

    private static final String AREA = "area:5f0e96a2-1a5b-42b5-a36c-345d06735769";
    private static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";
    private static final Position STAIRS = new Position(13.404971445409714, 9.552732036723341);
    private static final Position A = new Position(34.930597244822245, 6.566656324034442);
    private static final Position B = new Position(35.14539696716155, 6.6594280480962045);

    private final Map<String, Entity> entities = new HashMap<>();
    private final Map<String, Cell> cells = new HashMap<>();

    StuckDiverTest() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/stuck-diver-area.json")) {
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
        }
    }

    private AreaView viewWithDiverAt(Position at) {
        Map<String, Entity> moved = new HashMap<>(entities);
        moved.put(DIVER, entities.get(DIVER).withPosition(at));
        return AreaView.of(AREA, new Area("Level 1259", 0.0), moved, cells);
    }

    private List<Position> path(Position from) {
        return Pathfinder.path(viewWithDiverAt(from), DIVER, STAIRS).orElseThrow();
    }

    @Test
    void theTwoPointsTheDiverSteppedBetweenDoNotSendItBackToEachOther() {
        List<Position> fromA = path(A);
        List<Position> fromB = path(B);

        assertThat(fromA.getFirst()).isNotEqualTo(B);
        assertThat(fromB.getFirst()).isNotEqualTo(A);
        assertThat(fromA.getLast()).isEqualTo(STAIRS);
        assertThat(fromB.getLast()).isEqualTo(STAIRS);
    }

    @Test
    void followingTheWayAlwaysTakesTheDiverToTheStairsWithoutRevisitingAPoint() {
        List<Position> starts = new ArrayList<>(List.of(A, B));
        cells.values().stream().filter(c -> c.kind().open()).map(Cell::position)
                .filter(p -> p.distanceTo(STAIRS) > 0.5).forEach(starts::add);
        int walked = 0;
        for (Position start : starts) {
            if (Pathfinder.path(viewWithDiverAt(start), DIVER, STAIRS).isPresent()) {
                assertWalkReachesStairs(start);
                walked++;
            }
        }
        assertThat(walked).as("starts with a way, of %d", starts.size()).isGreaterThan(starts.size() / 2);
    }

    /** Plans afresh at every point, as the diver does each turn, and goes to the first waypoint. */
    private void assertWalkReachesStairs(Position start) {
        List<Position> visited = new ArrayList<>(List.of(start));
        Position at = start;
        for (int turn = 0; turn < 100 && !at.equals(STAIRS); turn++) {
            Position next = Pathfinder.path(viewWithDiverAt(at), DIVER, STAIRS)
                    .orElseThrow(() -> new AssertionError("no way from " + visited.getLast() + ", from " + start))
                    .getFirst();
            for (Position earlier : visited) {
                assertThat(earlier.distanceTo(next)).as("turn %d from %s goes back to %s", turn, start, earlier)
                        .isGreaterThan(1e-6);
            }
            visited.add(next);
            at = next;
        }
        assertThat(at).as("the walk from %s ends at the stairs after %s", start, visited).isEqualTo(STAIRS);
    }
}
