package com.pessimisticit.scarcity.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #438: the live diver of area:5f0e96a2-1a5b-42b5-a36c-345d06735769 (level 1259) that stepped between two
 * points 0.23 m apart for ever: an opening 0.622 m wide, between a wall's corner and the cells'
 * floor-only vertex, passed the search (2r = 0.6) but not {@link Erosion} (no stretch of it 0.33 m
 * from the wall), so its middle, 0.155 m from the corner, became a waypoint, and the {@link Refiner}
 * could not round it: it added and dropped the same corner until it ran out of rounds. The fixture
 * is that area's cells and walls within 1.5 m of the diver's way to the stairs down.
 */
class StuckDiverTest {

    private static final Position A = new Position(34.930597244822245, 6.566656324034442);
    private static final Position B = new Position(35.14539696716155, 6.6594280480962045);

    private final AreaFixture area = new AreaFixture("/stuck-diver-area.json",
            "area:5f0e96a2-1a5b-42b5-a36c-345d06735769", new Position(13.404971445409714, 9.552732036723341));

    @Test
    void theTwoPointsTheDiverSteppedBetweenDoNotSendItBackToEachOther() {
        List<Position> fromA = area.path(A).orElseThrow();
        List<Position> fromB = area.path(B).orElseThrow();

        assertThat(fromA.getFirst()).isNotEqualTo(B);
        assertThat(fromB.getFirst()).isNotEqualTo(A);
        assertThat(fromA.getLast()).isEqualTo(area.stairs());
        assertThat(fromB.getLast()).isEqualTo(area.stairs());
    }

    @Test
    void followingTheWayAlwaysTakesTheDiverToTheStairsWithoutRevisitingAPoint() {
        List<Position> starts = new ArrayList<>(List.of(A, B));
        area.cells().values().stream().filter(c -> c.kind().open()).map(Cell::position)
                .filter(p -> p.distanceTo(area.stairs()) > 0.5).forEach(starts::add);
        int walked = 0;
        for (Position start : starts) {
            if (area.path(start).isPresent()) {
                area.assertWalkReachesStairs(start);
                walked++;
            }
        }
        assertThat(walked).as("starts with a way, of %d", starts.size()).isGreaterThan(starts.size() / 2);
    }
}
