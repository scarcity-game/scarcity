package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;

/**
 * A generated area (#234): its {@code walls} (outlines: {@code wall} entities without ids, a
 * material or a mass; {@link #walls(Material)} makes them of one), its
 * {@code openCells} (each a convex, counter-clockwise polygon; not stored in the area, for tests
 * and placement), where the stairs {@code up} and {@code down} go, the other room cells'
 * centroids as {@code spawns}, and every {@code draws} that made it, in order.
 */
public record Layout(
        List<Entity> walls,
        List<List<Position>> openCells,
        Position up,
        Position down,
        List<Position> spawns,
        List<Recorder.Named> draws) {

    public Layout {
        walls = List.copyOf(walls);
        openCells = openCells.stream().map(List::copyOf).toList();
        spawns = List.copyOf(spawns);
        draws = List.copyOf(draws);
    }

    /** The walls made of {@code material}: its name, and the mass of their area of it, {@link Material#HEIGHT} tall. */
    public List<Entity> walls(Material material) {
        return walls.stream().map(w -> material.wall(w.polygon())).toList();
    }
}
