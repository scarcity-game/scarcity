package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class SweepTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    /** A 2 x 1 block from (0, 0) to (2, 1), counter-clockwise. */
    private static final List<Position> BLOCK = List.of(
            new Position(0.0, 0.0), new Position(2.0, 0.0), new Position(2.0, 1.0), new Position(0.0, 1.0));

    private static double block(double x, double y, double vx, double vy, double reach) {
        return Sweep.polygon(new Position(x, y), vx, vy, BLOCK, reach);
    }

    @Test
    void headOnAnEdgeStopsReachShortOfIt() {
        // From (1, 3) straight down at 1 m/s, radius 0.3: the edge y = 1 is met at y = 1.3.
        assertThat(block(1.0, 3.0, 0.0, -1.0, 0.3)).isCloseTo(1.7, within(1e-12));
        // From the left at 2 m/s: x = -0.3 is 4.7 m away.
        assertThat(block(-5.0, 0.5, 2.0, 0.0, 0.3)).isCloseTo(2.35, within(1e-12));
    }

    @Test
    void aGlancingPathMeetsTheEdgeWhereItsSpanAllows() {
        // Down and to the right at 45 degrees from (-1, 3): reaches y = 1.3 at x = 0.7, inside the span.
        assertThat(block(-1.0, 3.0, 1.0, -1.0, 0.3)).isCloseTo(1.7, within(1e-12));
    }

    @Test
    void aPathPastTheCornerMeetsTheVertex() {
        // Straight down along x = 2.2: no edge face is in the way, but the corner (2, 1) is 0.2 to the side.
        double t = block(2.2, 3.0, 0.0, -1.0, 0.3);
        double expected = 2.0 - Math.sqrt(0.3 * 0.3 - 0.2 * 0.2);
        assertThat(t).isCloseTo(expected, within(1e-12));
    }

    @Test
    void aPathThatClearsTheCornerNeverMeets() {
        assertThat(block(2.31, 3.0, 0.0, -1.0, 0.3)).isEqualTo(INF);
    }

    @Test
    void parallelToAnEdgeNeverMeetsIt() {
        assertThat(block(-1.0, 1.5, 1.0, 0.0, 0.3)).isEqualTo(INF);
    }

    @Test
    void movingAwayOrStandingStillNeverMeets() {
        assertThat(block(1.0, 3.0, 0.0, 1.0, 0.3)).isEqualTo(INF);
        assertThat(block(1.0, 3.0, 0.0, 0.0, 0.3)).isEqualTo(INF);
    }

    @Test
    void touchingAndClosingIsZeroAndTouchingAndLeavingIsNever() {
        assertThat(block(1.0, 1.3, 0.0, -1.0, 0.3)).isZero();
        assertThat(block(1.0, 1.3, 0.0, 1.0, 0.3)).isEqualTo(INF);
        // Sliding along the edge, touching it, is not closing.
        assertThat(block(1.0, 1.3, 1.0, 0.0, 0.3)).isEqualTo(INF);
    }

    @Test
    void touchingACornerCountsByTheDirectionFromIt() {
        double d = 0.3 / Math.sqrt(2.0);
        assertThat(block(2.0 + d, 1.0 + d, -1.0, -1.0, 0.3)).isZero();
        assertThat(block(2.0 + d, 1.0 + d, 1.0, 0.0, 0.3)).isEqualTo(INF);
    }

    @Test
    void aPointInsideCanMoveOutThroughTheNearestEdgeButNotDeeper() {
        // (1, 0.9) is nearest the top edge.
        assertThat(block(1.0, 0.9, 0.0, 1.0, 0.0)).isEqualTo(INF);
        assertThat(block(1.0, 0.9, 0.0, -1.0, 0.0)).isZero();
    }

    @Test
    void aRayWithNoReachStopsOnTheEdge() {
        assertThat(block(5.0, 0.25, -1.0, 0.0, 0.0)).isCloseTo(3.0, within(1e-12));
        // A ray that passes exactly through a corner meets it there.
        assertThat(block(4.0, 3.0, -1.0, -1.0, 0.0)).isCloseTo(2.0, within(1e-12));
    }

    @Test
    void twoOverlappingWallsLeaveNoGapAtTheirCorner() {
        // An L: the block, and a 1 x 2 block on its left end, overlapping it in (0, 0)-(1, 1).
        List<Position> upright = List.of(
                new Position(0.0, 0.0), new Position(1.0, 0.0), new Position(1.0, 2.0), new Position(0.0, 2.0));
        // Into the inner corner (1, 1) from the upper right, at 45 degrees.
        Position from = new Position(3.0, 3.0);
        double first = Math.min(Sweep.polygon(from, -1.0, -1.0, BLOCK, 0.3),
                Sweep.polygon(from, -1.0, -1.0, upright, 0.3));
        // It stops 0.3 from both faces: at (1.3, 1.3), after 1.7 along each axis.
        assertThat(first).isCloseTo(1.7, within(1e-12));
    }

    @Test
    void aCircleIsAQuadraticInTime() {
        Position centre = new Position(0.0, 0.0);
        assertThat(Sweep.circle(new Position(5.0, 0.0), -1.0, 0.0, centre, 1.0)).isCloseTo(4.0, within(1e-12));
        assertThat(Sweep.circle(new Position(1.0, 0.0), -1.0, 0.0, centre, 1.0)).isZero();
        assertThat(Sweep.circle(new Position(1.0, 0.0), 1.0, 0.0, centre, 1.0)).isEqualTo(INF);
        assertThat(Sweep.circle(new Position(5.0, 2.0), -1.0, 0.0, centre, 1.0)).isEqualTo(INF);
    }
}
