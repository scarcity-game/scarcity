package com.pessimisticit.scarcity.world.gen;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;

/**
 * Every open cell reachable from one start (#234, "Connectivity"), on any {@link CellGraph}:
 * a breadth-first search over the open cells, and for each part it can't reach, the shortest
 * passage through rock (never through a border cell) to the part it can, carved open.
 */
public final class Connectivity {

    private Connectivity() {
    }

    /** The open cells reachable from {@code start} through open neighbours. */
    public static BitSet reachable(CellGraph graph, BitSet open, int start) {
        BitSet seen = new BitSet(graph.size());
        Deque<Integer> queue = new ArrayDeque<>();
        seen.set(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (int n : graph.neighbours(queue.poll())) {
                if (open.get(n) && !seen.get(n)) {
                    seen.set(n);
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** Carves open the cells that join every open cell to {@code start}; returns how many were carved. */
    public static int connect(CellGraph graph, BitSet open, int start) {
        int carved = 0;
        BitSet reached = reachable(graph, open, start);
        while (!contains(reached, open)) {
            carved += carveToNearest(graph, open, reached);
            reached = reachable(graph, open, start);
        }
        return carved;
    }

    private static boolean contains(BitSet reached, BitSet open) {
        BitSet rest = (BitSet) open.clone();
        rest.andNot(reached);
        return rest.isEmpty();
    }

    /** From every reached cell at once, the shortest way to an unreached open cell; the rock on it is opened. */
    private static int carveToNearest(CellGraph graph, BitSet open, BitSet reached) {
        int[] parent = new int[graph.size()];
        Arrays.fill(parent, -2);
        Deque<Integer> queue = new ArrayDeque<>();
        reached.stream().forEach(c -> {
            parent[c] = -1;
            queue.add(c);
        });
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int n : graph.neighbours(cell)) {
                if (parent[n] != -2 || graph.onBorder(n)) {
                    continue;
                }
                parent[n] = cell;
                if (open.get(n)) {
                    return open(open, parent, cell);
                }
                queue.add(n);
            }
        }
        throw new IllegalStateException("an open cell can't be joined: it is walled in by border cells");
    }

    private static int open(BitSet open, int[] parent, int from) {
        int carved = 0;
        for (int c = from; parent[c] != -1; c = parent[c]) {
            open.set(c);
            carved++;
        }
        return carved;
    }
}
