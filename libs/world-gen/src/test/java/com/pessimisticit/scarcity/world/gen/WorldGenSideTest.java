package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Draw;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A drawn cell side (#395) and the recorded truncated gaussian. */
class WorldGenSideTest {

    private static final double PASSER = 0.3;

    private static Layout make(double side) {
        return WorldGen.make(24.0, 18.0, PASSER, side, new Tessellation.Squares(),
                new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT), new SplittableRandom(3));
    }

    @Test
    void aSideAtOrAboveTheLeastIsUsedAndOneBelowIsRefused() {
        double least = WorldGen.cellSize(PASSER);
        Layout plain = WorldGen.make(24.0, 18.0, PASSER, new Tessellation.Squares(),
                new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT), new SplittableRandom(3));

        assertThat(make(1.5 * least).cells().size()).isLessThan(plain.cells().size());
        assertThat(make(least).cells()).isEqualTo(plain.cells());
        assertThatThrownBy(() -> make(least - 0.01)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell side");
    }

    @Test
    void aTruncatedGaussianIsRecordedWithItsTruncation() {
        Recorder recorder = new Recorder(new SplittableRandom(1));
        double value = recorder.truncatedGaussian("x", 1.0, 0.5, 0.5, 2.0);

        Draw.Gaussian draw = (Draw.Gaussian) recorder.draws().getFirst().draw();
        assertThat(recorder.draws().getFirst().name()).isEqualTo("x");
        assertThat(draw.value()).isEqualTo(value).isBetween(0.5, 2.0);
        assertThat(draw).extracting(Draw.Gaussian::mean, Draw.Gaussian::sd, Draw.Gaussian::truncate)
                .containsExactly(1.0, 0.5, new Draw.Truncate(0.5, 2.0));
    }
}
