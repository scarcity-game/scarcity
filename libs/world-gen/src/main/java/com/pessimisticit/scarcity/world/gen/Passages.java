package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes the open cells one {@link Navigation} region (#312), so the largest passer can get from
 * any wide part of the cave to any other. While there are two regions, the smaller is joined to
 * the nearest other by the shortest way through the cells (never a border cell), and that way is
 * <em>widened</em>: its cells and every cell touching them are opened, so the passage is wider
 * than the passer needs wherever it was one cell wide. A region that can't be joined, because
 * rock on the border is in the way, is filled in; so is one that comes back as it was after its
 * way was widened, as where thin cells (Penrose rhombs) pinch the way below the passer's room:
 * widening it again would open nothing new, and filling in what it opened would undo it.
 */
final class Passages {

    /** The fewest rounds tried; a level of more cells than that gets as many rounds as cells (#371). */
    private static final int ROUNDS = 100;

    private final CellGraph graph;
    private final BitSet open;
    private final Map<Position, List<Integer>> cellsAt;
    private final Set<BitSet> widened;

    private Passages(CellGraph graph, BitSet open) {
        this.graph = graph;
        this.open = open;
        this.cellsAt = new HashMap<>();
        this.widened = new HashSet<>();
        for (int c = 0; c < graph.size(); c++) {
            for (Position v : graph.polygon(c)) {
                cellsAt.computeIfAbsent(v, k -> new ArrayList<>()).add(c);
            }
        }
    }

    /**
     * Joins the regions of the open cells that are at least {@code need} from every wall.
     *
     * @return the biggest region
     * @throws IllegalStateException if no cell has room
     */
    static BitSet widen(CellGraph graph, BitSet open, double need) {
        Passages passages = new Passages(graph, open);
        // Each round joins one region to another or fills it in, so there are never more rounds than cells.
        int rounds = Math.max(ROUNDS, graph.size());
        for (int round = 0; round < rounds; round++) {
            List<BitSet> regions = new Navigation(graph, open, need).regions();
            if (regions.isEmpty()) {
                throw new IllegalStateException("passages: no cell is " + need + " m from every wall");
            }
            if (regions.size() == 1) {
                return regions.getFirst();
            }
            passages.join(regions.getLast(), regions);
        }
        throw new IllegalStateException("passages: still apart after " + rounds + " rounds");
    }

    /**
     * Opens a wide way from {@code region} to the nearest cell of any other region. If that opens
     * nothing (rock on the border is in the way, which stays), or this region's way was widened
     * before and it is still apart, the region is filled in instead, with whatever open cells
     * that cuts off from the biggest region.
     */
    private void join(BitSet region, List<BitSet> regions) {
        int before = open.cardinality();
        if (widened.add((BitSet) region.clone())) {
            openWayFrom(region, regions);
        }
        if (open.cardinality() == before) {
            open.andNot(region);
            open.and(Connectivity.reachable(graph, open, regions.getFirst().nextSetBit(0)));
        }
    }

    private void openWayFrom(BitSet region, List<BitSet> regions) {
        BitSet others = new BitSet(graph.size());
        regions.stream().filter(r -> r != region).forEach(others::or);
        int[] parent = new int[graph.size()];
        Arrays.fill(parent, -2);
        Deque<Integer> queue = new ArrayDeque<>();
        region.stream().forEach(c -> {
            parent[c] = -1;
            queue.add(c);
        });
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int n : graph.neighbours(cell)) {
                if (parent[n] == -2 && !graph.onBorder(n)) {
                    parent[n] = cell;
                    if (others.get(n)) {
                        widenAlong(parent, n);
                        return;
                    }
                    queue.add(n);
                }
            }
        }
    }

    /** Opens the cells from {@code end} back to the start of the search, and every cell that touches one. */
    private void widenAlong(int[] parent, int end) {
        for (int c = end; c != -1; c = parent[c]) {
            for (Position v : graph.polygon(c)) {
                cellsAt.get(v).stream().filter(n -> !graph.onBorder(n)).forEach(open::set);
            }
        }
    }
}
