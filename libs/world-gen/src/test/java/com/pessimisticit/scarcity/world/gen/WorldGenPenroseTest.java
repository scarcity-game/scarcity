package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** {@link WorldGen#penrose}: an aperiodic Penrose rhomb tiling with caves in it, held to what every cave level is. */
class WorldGenPenroseTest extends CaveLevelContract {

    @Override
    CellGraph graph(Recorder recorder) {
        return new PenroseGraph(W, H, WorldGen.PenroseSettings.DEFAULT.edge() * WorldGen.cellSize(DIVER), recorder);
    }

    @Override
    CaveGenerator.Settings caves() {
        return WorldGen.PenroseSettings.DEFAULT.caves();
    }

    @Override
    Layout layout(long seed) {
        return WorldGen.penrose(W, H, DIVER, WorldGen.PenroseSettings.DEFAULT, new SplittableRandom(seed));
    }

    @Override
    String firstDraw() {
        return "penrose.offset[0]";
    }

    @Test
    void aWayPinchedByThinRhombsIsFilledInRatherThanWidenedForever() {
        // Seed 185: widening one region's way opened a cell that was a region of its own with no
        // way out; filling that in undid the widening, and the passages went round for 752 rounds.
        assertThat(layout(185).cells()).isNotEmpty();
    }
}
