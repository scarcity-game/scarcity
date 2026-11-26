package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** {@link WorldGen#voronoi}: a Voronoi tessellation with caves in it (#312), and what every cave level is. */
class WorldGenVoronoiTest extends CaveLevelContract {

    @Override
    CellGraph graph(Recorder recorder) {
        return new VoronoiGraph(W, H, WorldGen.sites(W, H, DIVER), WorldGen.VoronoiSettings.DEFAULT.relaxations(),
                recorder);
    }

    @Override
    CaveGenerator.Settings caves() {
        return WorldGen.VoronoiSettings.DEFAULT.caves();
    }

    @Override
    Layout layout(long seed) {
        return WorldGen.voronoi(W, H, DIVER, WorldGen.VoronoiSettings.DEFAULT, new SplittableRandom(seed));
    }

    @Override
    String firstDraw() {
        return "voronoi.site[0].x";
    }

    @Test
    void aLevelSixteenTimesTheAreaIsMadeInSeconds() {
        // 96 x 72 m, about 10,800 cells (#371): it once took minutes, and its caves need more than
        // a hundred rounds of joining passages.
        long start = System.nanoTime();
        Layout big = WorldGen.voronoi(96.0, 72.0, DIVER, WorldGen.VoronoiSettings.DEFAULT, new SplittableRandom(11));
        assertThat(big.cells()).hasSizeGreaterThan(10_000);
        assertThat((System.nanoTime() - start) / 1e9).as("seconds").isLessThan(30.0);
    }
}
