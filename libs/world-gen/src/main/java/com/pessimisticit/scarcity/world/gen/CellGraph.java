package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.BitSet;
import java.util.List;

/**
 * A tessellation as a graph of cells (#234): what every generator works on, so any generator
 * can run on any tessellation. Cells are numbered {@code 0} to {@code size() - 1}; each is a
 * convex, counter-clockwise polygon in area coordinates. Neighbours share an edge. A cell on
 * the border has an edge it shares with no cell; generators keep border cells solid, so the
 * walls always enclose the open ones.
 */
public interface CellGraph {

    int size();

    /** The cell's polygon: convex, counter-clockwise, in metres. */
    List<Position> polygon(int cell);

    Position centroid(int cell);

    /** The cells that share an edge with {@code cell}, in a fixed order. */
    List<Integer> neighbours(int cell);

    /** True if the cell has an edge it shares with no other cell. */
    boolean onBorder(int cell);

    /** The shortest edge any cell has, in metres: the narrowest gap a one-cell passage leaves. */
    double minEdge();

    /** The lower-left and upper-right corners of the box around every cell. */
    List<Position> bounds();

    /**
     * Walls covering every cell not in {@code open}: {@code stone-wall} entities with convex
     * polygons (docs/resources.md, "Walls"), as few as the tessellation can make them. They
     * leave no gap between the open cells and the outside.
     */
    List<Entity> walls(BitSet open);
}
