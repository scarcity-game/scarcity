package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoxClipTest {

    private static List<Position> rect(double x0, double y0, double x1, double y1) {
        return List.of(new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1));
    }

    @Test
    void aPolygonInsideTheBoxIsKeptAsItIs() {
        List<Position> inside = rect(1, 1, 2, 2);
        assertThat(BoxClip.toBox(inside, 4, 3)).isEqualTo(inside);
    }

    @Test
    void aPolygonOutsideTheBoxIsNull() {
        assertThat(BoxClip.toBox(rect(5, 5, 6, 6), 4, 3)).isNull();
    }

    @Test
    void aPolygonOverASideIsCutAtTheSideExactly() {
        assertThat(BoxClip.toBox(rect(-1, 1, 1, 2), 4, 3)).containsExactly(new Position(0.0, 1.0),
                new Position(1.0, 1.0), new Position(1.0, 2.0), new Position(0.0, 2.0));
    }

    @Test
    void aPolygonOverACornerIsCutOnBothSides() {
        assertThat(BoxClip.toBox(rect(3, 2, 5, 4), 4, 3)).containsExactly(new Position(3.0, 2.0),
                new Position(4.0, 2.0), new Position(4.0, 3.0), new Position(3.0, 3.0));
    }

    @Test
    void aPolygonTouchingTheBoxAtAnEdgeOrPointHasNoAreaAndIsDropped() {
        assertThat(BoxClip.toBox(rect(4, 0, 5, 1), 4, 3)).isNull();
        assertThat(BoxClip.toBox(rect(4, 3, 5, 4), 4, 3)).isNull();
    }

    @Test
    void twoCellsSharingAnEdgeThatCrossesASideGetTheIdenticalPoint() {
        // The shared edge runs from p (outside) to q (inside); each cell lists it in the opposite direction.
        Position p = new Position(-0.3, 0.1);
        Position q = new Position(0.7, 0.9);
        List<Position> left = List.of(p, q, new Position(-1.0, 1.0));
        List<Position> right = List.of(q, p, new Position(2.0, -5.0));
        Position fromLeft = onSideBetween(BoxClip.toBox(left, 4, 3), 0.1, 0.9);
        Position fromRight = onSideBetween(BoxClip.toBox(right, 4, 3), 0.1, 0.9);
        assertThat(fromLeft).isEqualTo(fromRight);
    }

    /** The one vertex on the side x = 0 with y between the two. */
    private static Position onSideBetween(List<Position> polygon, double yLow, double yHigh) {
        List<Position> found = polygon.stream().filter(v -> v.x() == 0.0 && v.y() > yLow && v.y() < yHigh).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
