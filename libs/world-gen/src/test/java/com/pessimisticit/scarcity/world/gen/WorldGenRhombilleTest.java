package com.pessimisticit.scarcity.world.gen;

import java.util.SplittableRandom;

/** {@link Tessellation.Rhombille} with caves, held to what every cave level is (#420). */
class WorldGenRhombilleTest extends CaveLevelContract {

    private final Tessellation tessellation = new Tessellation.Rhombille();

    @Override
    CellGraph graph(Recorder recorder) {
        return tessellation.make(W, H, WorldGen.cellSize(DIVER), recorder);
    }

    @Override
    CaveGenerator.Settings caves() {
        return CaveGenerator.Settings.DEFAULT;
    }

    @Override
    Layout layout(long seed) {
        return WorldGen.make(W, H, DIVER, tessellation, new CaveGenerator(CaveGenerator.Settings.DEFAULT),
                new SplittableRandom(seed));
    }

    @Override
    int maxWalls() {
        return 320;
    }

    @Override
    String firstDraw() {
        return "rhombille.origin.x";
    }
}
