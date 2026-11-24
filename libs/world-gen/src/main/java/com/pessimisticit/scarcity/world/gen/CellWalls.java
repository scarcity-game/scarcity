package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link CellGraph#walls}: one wall outline (the {@code wall} prototype, no material yet) per solid
 * cell that touches an open cell, in cell order. Two
 * cells touch when they share a vertex, compared exactly: a tessellation computes every shared
 * vertex once, so neighbours hold equal positions and their walls meet with no gap.
 */
final class CellWalls {

    private CellWalls() {
    }

    static List<Entity> around(CellGraph graph, BitSet open) {
        Set<Position> openVertices = new HashSet<>();
        open.stream().forEach(cell -> openVertices.addAll(graph.polygon(cell)));
        List<Entity> walls = new ArrayList<>();
        for (int cell = 0; cell < graph.size(); cell++) {
            List<Position> polygon = graph.polygon(cell);
            if (!open.get(cell) && polygon.stream().anyMatch(openVertices::contains)) {
                walls.add(Prototypes.get("wall").withPolygon(polygon));
            }
        }
        return walls;
    }
}
