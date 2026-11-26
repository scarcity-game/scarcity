package com.pessimisticit.scarcity.world.gen;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caves on any {@link CellGraph} (#234, #312), by cellular automaton: every cell that isn't on
 * the border is drawn open with probability {@code fill} (a {@code bernoulli}, {@code cave[t].cell[c].open}),
 * then {@code smoothing} rounds make a cell open when more than half of itself and its
 * neighbours are. Border cells stay solid. Open regions of fewer than {@code minRegion} cells
 * are filled in, the others are joined to the biggest by {@link Connectivity}, and every passage
 * narrower than the passer needs is widened ({@link Passages}).
 *
 * <p>The stairs go on cells whose centroid is more than {@link #STAIRS_CLEAR} from any wall: the
 * up stairs on one drawn ({@code cave.stairsUp}) from those, the down stairs on the one farthest
 * from it through open cells. Spawns are the other cells with room for a mouse.
 */
public final class CaveGenerator implements Generator {

    /** How far a stairs' centre is from every wall at least, in metres (the contract asks for more than 0.5). */
    public static final double STAIRS_CLEAR = 0.6;

    /** How far a spawn is from every wall at least, and from either stairs, in metres. */
    public static final double SPAWN_CLEAR = 0.4;
    public static final double SPAWN_APART = 1.0;

    /**
     * {@code fill}: the chance a cell starts open, in (0, 1). {@code smoothing}: the rounds of the
     * automaton. {@code minRegion}: the fewest cells a region keeps. {@code minRoomy}: the fewest
     * cells a disc of the passer's size can reach in the cave, or it is drawn again, up to
     * {@code attempts} times.
     */
    public record Settings(double fill, int smoothing, int minRegion, int minRoomy, int attempts) {
        public static final Settings DEFAULT = new Settings(0.5, 4, 12, 40, 10);

        public Settings {
            if (!(fill > 0.0 && fill < 1.0) || smoothing < 0) {
                throw new IllegalArgumentException("caves: need 0 < fill < 1 and smoothing >= 0");
            }
            if (Math.min(Math.min(minRegion, minRoomy), attempts) < 1) {
                throw new IllegalArgumentException("caves: need minRegion, minRoomy and attempts >= 1");
            }
        }
    }

    private final Settings settings;

    public CaveGenerator(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return "cave";
    }

    /** The settings, then {@code clearance}: the room a disc needs between the stairs. */
    @Override
    public Map<String, Object> params(double need) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fill", settings.fill());
        params.put("smoothing", settings.smoothing());
        params.put("minRegion", settings.minRegion());
        params.put("minRoomy", settings.minRoomy());
        params.put("attempts", settings.attempts());
        params.put("clearance", need);
        return params;
    }

    /**
     * A cave in {@code graph} that a disc of radius {@code need} can cross: the stairs are on cells
     * it can reach from each other ({@link Navigation}), and a passage too narrow for it is widened.
     * A cave with too little room for it is drawn again ({@code cave[t].cell[c].open}).
     */
    @Override
    public Plan generate(CellGraph graph, Recorder recorder, double need) {
        for (int attempt = 0; attempt < settings.attempts(); attempt++) {
            BitSet open = attempt(graph, recorder, attempt);
            BitSet region = open.isEmpty() ? open : roomy(graph, open, need);
            if (region.cardinality() >= settings.minRoomy()) {
                double[] clearance = Clearance.of(graph, open);
                List<Integer> clear = region.stream().filter(c -> clearance[c] > STAIRS_CLEAR).boxed().toList();
                // A small cave (as in a small outline) may have no room for two stairs: draw again.
                if (clear.size() >= 2) {
                    return place(graph, open, clearance, clear, recorder);
                }
            }
        }
        throw new IllegalStateException("caves: no cave with room for " + settings.minRoomy() + " cells in "
                + settings.attempts() + " attempts");
    }

    /** The cells a disc can reach, once the passages are widened; none if there is no room for it anywhere. */
    private static BitSet roomy(CellGraph graph, BitSet open, double need) {
        try {
            return Passages.widen(graph, open, need);
        } catch (IllegalStateException noRoom) {
            return new BitSet();
        }
    }

    private BitSet attempt(CellGraph graph, Recorder recorder, int attempt) {
        BitSet open = fill(graph, recorder, attempt);
        for (int round = 0; round < settings.smoothing(); round++) {
            open = smooth(graph, open);
        }
        if (open.isEmpty()) {
            return open;
        }
        Connectivity.connect(graph, open, keepRegions(graph, open));
        return open;
    }

    private BitSet fill(CellGraph graph, Recorder recorder, int attempt) {
        BitSet open = new BitSet(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            if (!graph.onBorder(c) && recorder.bernoulli("cave[" + attempt + "].cell[" + c + "].open", settings.fill())) {
                open.set(c);
            }
        }
        return open;
    }

    /** A cell is open when more than half of itself and its neighbours were; border cells never are. */
    private static BitSet smooth(CellGraph graph, BitSet open) {
        BitSet next = new BitSet(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            if (graph.onBorder(c)) {
                continue;
            }
            int total = graph.neighbours(c).size() + 1;
            long count = graph.neighbours(c).stream().filter(open::get).count() + (open.get(c) ? 1 : 0);
            if (2 * count > total) {
                next.set(c);
            }
        }
        return next;
    }

    /** Fills every region of fewer than {@code minRegion} cells, except the biggest; returns a cell of the biggest. */
    private int keepRegions(CellGraph graph, BitSet open) {
        List<BitSet> regions = new ArrayList<>();
        BitSet seen = new BitSet(graph.size());
        for (int c = open.nextSetBit(0); c >= 0; c = open.nextSetBit(c + 1)) {
            if (!seen.get(c)) {
                BitSet region = Connectivity.reachable(graph, open, c);
                seen.or(region);
                regions.add(region);
            }
        }
        BitSet biggest = regions.stream().max(Comparator.comparingInt(BitSet::cardinality)).orElseThrow();
        for (BitSet region : regions) {
            if (region != biggest && region.cardinality() < settings.minRegion()) {
                open.andNot(region);
            }
        }
        return biggest.nextSetBit(0);
    }

    /** The stairs on two of the {@code clear} cells (more than {@link #STAIRS_CLEAR} from every wall), and the spawns. */
    private static Plan place(CellGraph graph, BitSet open, double[] clearance, List<Integer> clear,
            Recorder recorder) {
        int up = clear.get(recorder.index("cave.stairsUp", clear.size()));
        int[] steps = WorldGen.steps(graph, open, up);
        int down = clear.stream().reduce(up, (best, c) -> steps[c] > steps[best] ? c : best);
        // The stairs' own cells are 0 from themselves, so not far from them.
        List<Integer> spawns = open.stream().filter(c -> clearance[c] > SPAWN_CLEAR
                && far(graph, c, up) && far(graph, c, down)).boxed().toList();
        return new Plan(open, up, down, spawns);
    }

    private static boolean far(CellGraph graph, int cell, int stairs) {
        return graph.centroid(cell).distanceTo(graph.centroid(stairs)) >= SPAWN_APART;
    }
}
