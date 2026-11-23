package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SquareGridTest {

    private final SquareGrid grid = new SquareGrid(3, 3, 1.0);

    @Test
    void cellsAreNumberedRowByRowWithNeighboursEastNorthWestSouth() {
        int centre = grid.cell(1, 1);
        assertThat(centre).isEqualTo(4);
        assertThat(grid.neighbours(centre)).containsExactly(5, 7, 3, 1);
        assertThat(grid.centroid(centre)).isEqualTo(new Position(1.5, 1.5));
        assertThat(grid.polygon(centre)).containsExactly(new Position(1.0, 1.0), new Position(2.0, 1.0),
                new Position(2.0, 2.0), new Position(1.0, 2.0));
        assertThat(grid.onBorder(centre)).isFalse();
        assertThat(grid.onBorder(0)).isTrue();
    }

    @Test
    void solidCellsMergeIntoMaximalRectangles() {
        BitSet open = new BitSet();
        open.set(grid.cell(1, 1));
        List<Entity> walls = grid.walls(open);
        // The bottom row, the west and east columns above it, then the top middle.
        assertThat(walls).extracting(Entity::polygon).containsExactly(
                rect(0, 0, 3, 1), rect(0, 1, 1, 3), rect(2, 1, 3, 3), rect(1, 2, 2, 3));
    }

    @Test
    void anAllSolidGridIsOneWall() {
        assertThat(grid.walls(new BitSet())).singleElement()
                .satisfies(w -> assertThat(w.polygon()).isEqualTo(rect(0, 0, 3, 3)));
    }

    @Test
    void twoOpenCellsAreJoinedThroughTheRock() {
        SquareGrid wide = new SquareGrid(7, 3, 1.0);
        BitSet open = new BitSet();
        open.set(wide.cell(1, 1));
        open.set(wide.cell(5, 1));
        assertThat(Connectivity.connect(wide, open, wide.cell(1, 1))).isEqualTo(3);
        assertThat(open.cardinality()).isEqualTo(5);
        assertThat(Connectivity.reachable(wide, open, wide.cell(1, 1))).isEqualTo(open);
    }

    private static List<Position> rect(double x0, double y0, double x1, double y1) {
        return List.of(new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1));
    }
}
