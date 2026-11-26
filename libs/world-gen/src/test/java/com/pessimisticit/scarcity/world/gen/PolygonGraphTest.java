package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tiling check in {@link PolygonGraph}'s constructor, on small hand-made polygon sets. */
class PolygonGraphTest {

    private static final Position ORIGIN = new Position(0.0, 0.0);

    private static List<Position> rect(double x0, double y0, double x1, double y1) {
        return List.of(new Position(x0, y0), new Position(x1, y0), new Position(x1, y1), new Position(x0, y1));
    }

    private static PolygonGraph graph(double width, double height, List<List<Position>> polygons) {
        return new PolygonGraph(polygons, ORIGIN, new Position(width, height));
    }

    @Test
    void twoSquaresSharingAnEdgeAreNeighboursAndBothOnTheBorder() {
        PolygonGraph graph = graph(2, 1, List.of(rect(0, 0, 1, 1), rect(1, 0, 2, 1)));
        assertThat(graph.neighbours(0)).containsExactly(1);
        assertThat(graph.neighbours(1)).containsExactly(0);
        assertThat(graph.onBorder(0)).isTrue();
        assertThat(graph.minEdge()).isEqualTo(1.0);
    }

    @Test
    void aTJunctionIsRejectedNamingTheCellAndTheEdge() {
        // The left square's right edge is (1, 0)-(1, 1); on the right it is split at (1, 0.5).
        var polygons = List.of(rect(0, 0, 1, 1), rect(1, 0, 2, 0.5), rect(1, 0.5, 2, 1));
        assertThatThrownBy(() -> graph(2, 1, polygons))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell 0")
                .hasMessageContaining(new Position(1.0, 0.0).toString())
                .hasMessageContaining(new Position(1.0, 1.0).toString());
    }

    @Test
    void aGapBetweenCellsIsRejected() {
        var polygons = List.of(rect(0, 0, 1, 1), rect(1.5, 0, 2.5, 1));
        assertThatThrownBy(() -> graph(2.5, 1, polygons))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell 0");
    }

    @Test
    void anEdgeHeldByThreeCellsIsRejected() {
        List<Position> third = List.of(new Position(1.0, 0.0), new Position(1.0, 1.0), new Position(0.5, 1.5));
        var polygons = List.of(rect(0, 0, 1, 1), rect(1, 0, 2, 1), third);
        assertThatThrownBy(() -> graph(2, 2, polygons))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[0, 1, 2]");
    }

    @Test
    void anEdgeInsideTheBoxWithNoNeighbourIsRejected() {
        assertThatThrownBy(() -> graph(2, 2, List.of(rect(0, 0, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell 0");
    }

    @Test
    void anEdgeNearlyOnASideIsRejected() {
        assertThatThrownBy(() -> graph(1, 1, List.of(rect(1e-9, 0, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell 0");
    }
}
