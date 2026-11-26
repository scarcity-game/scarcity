package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.List;

/**
 * A generated area (#234): its {@code walls} (outlines: {@code wall} entities without ids, a
 * material or a mass; {@link #walls(Material)} makes them of one), its
 * {@code cells}, the tessellation's cells with their kinds, neighbours, {@code clearance} and
 * {@code width}s ({@link #cellMesh()}; {@link #cellWalls(Material)} makes the walls that name them),
 * its {@code openCells} (each a convex, counter-clockwise polygon; not stored in the area, for tests
 * and placement), where the stairs {@code up} and {@code down} go, the other room cells'
 * centroids as {@code spawns}, and every {@code draws} that made it, in order.
 */
public record Layout(
        List<Entity> walls,
        List<Cell> cells,
        List<List<Position>> openCells,
        Position up,
        Position down,
        List<Position> spawns,
        List<Recorder.Named> draws) {

    public Layout {
        walls = List.copyOf(walls);
        cells = List.copyOf(cells);
        openCells = openCells.stream().map(List::copyOf).toList();
        spawns = List.copyOf(spawns);
        draws = List.copyOf(draws);
    }

    /** The walls made of {@code material}: its name, and the mass of their area of it, {@link Material#HEIGHT} tall. */
    public List<Entity> walls(Material material) {
        return walls.stream().map(w -> material.wall(w.polygon())).toList();
    }

    /** The cells as a mesh, with the {@code clearance} and {@code width} they were generated with. */
    public CellMesh cellMesh() {
        return CellMesh.of(cells);
    }

    /**
     * One wall of {@code material} per {@code wall} cell, each naming its cell and with no polygon
     * of its own, in cell id order (docs/resources.md, "Walls"). The same rock as {@link #walls(Material)}.
     */
    public List<Entity> cellWalls(Material material) {
        return cells.stream().filter(c -> c.kind() == Cell.Kind.WALL).map(material::wall).toList();
    }
}
