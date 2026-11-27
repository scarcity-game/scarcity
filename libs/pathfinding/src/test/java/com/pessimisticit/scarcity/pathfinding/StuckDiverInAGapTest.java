package com.pessimisticit.scarcity.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The live diver of area:a974e9ea-65e6-4c67-aade-86363f6eaf91 (level 1669) that walked a loop of
 * four points for ever. A floor cell between two walls had its centroid 0.311 m from them, so
 * {@link CellSearch} let the diver (r = 0.3) through it, but its diagonal, corner to corner, is
 * 0.654 m: less than the 0.65 m plus rounding a leg needs ({@link Probe}, r + MARGIN / 2 each
 * side). The legs through it weren't clear, and the {@link Mender} went round from inside the gap,
 * the long way, back out past the start; one step later the plan led into the gap again. The
 * fixture is that area's cells and walls between the diver and the stairs down.
 */
class StuckDiverInAGapTest {

    private static final List<Position> LOOP = List.of(
            new Position(24.440274242818724, 25.055155499334024),
            new Position(24.258027327121074, 24.982822327723685),
            new Position(24.78510097706692, 24.940891441761202),
            new Position(25.471382283073844, 26.529785105503027));

    private final AreaFixture area = new AreaFixture("/stuck-diver-gap-area.json",
            "area:a974e9ea-65e6-4c67-aade-86363f6eaf91", new Position(2.6628199679651114, 7.21763368745247));

    @Test
    void noPointOfTheLoopPlansAWayBackThroughItself() {
        for (Position from : LOOP) {
            List<Position> way = area.path(from).orElseThrow();
            assertThat(way).as("the way from %s", from).doesNotContain(from);
            assertThat(way.getLast()).isEqualTo(area.stairs());
        }
    }

    @Test
    void followingTheWayFromRoundTheGapTakesTheDiverToTheStairsWithoutRevisitingAPoint() {
        LOOP.forEach(area::assertWalkReachesStairs);
        area.openCellsNear(LOOP.getFirst(), 3.0).forEach(start -> {
            if (area.path(start).isPresent()) {
                area.assertWalkReachesStairs(start);
            }
        });
    }
}
