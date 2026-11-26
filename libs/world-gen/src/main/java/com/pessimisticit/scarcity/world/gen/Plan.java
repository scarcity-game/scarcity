package com.pessimisticit.scarcity.world.gen;

import java.util.BitSet;
import java.util.List;

/**
 * What a {@link Generator} makes of a {@link CellGraph}: the open cells, the cells the stairs up
 * and down go on, and the cells a mouse may start on.
 */
public record Plan(BitSet open, int up, int down, List<Integer> spawns) {

    public Plan {
        open = (BitSet) open.clone();
        spawns = List.copyOf(spawns);
    }

    @Override
    public BitSet open() {
        return (BitSet) open.clone();
    }
}
