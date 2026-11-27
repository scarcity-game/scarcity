package com.pessimisticit.scarcity.world.gen;

import java.util.SplittableRandom;

/** {@link Tessellation.Hexagons} with caves, held to what every cave level is (#419). */
class WorldGenHexagonsTest extends CaveLevelContract {

    private final Tessellation tessellation = new Tessellation.Hexagons();

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
    String firstDraw() {
        return "hexagons.origin.x";
    }
}
