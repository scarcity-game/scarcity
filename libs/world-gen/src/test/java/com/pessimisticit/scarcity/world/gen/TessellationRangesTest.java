package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every tessellation tiles the box with no T-junction or gap (#427) for boxes and cell sides like those
 * {@code libs/world-seed}'s {@code LevelShape} draws (widths 20 to 72, heights 15 to 54, sides 1 to 1.7),
 * over many seeds; {@link PolygonGraph}'s constructor does the check.
 */
class TessellationRangesTest {

    private static final int LEVELS = 1500;

    static Stream<Tessellation> tessellations() {
        return Stream.of(new Tessellation.Squares(), new Tessellation.Voronoi(3), Tessellation.Penrose.DEFAULT);
    }

    @ParameterizedTest
    @MethodSource("tessellations")
    void everyBoxTilesWithoutAJunctionOrGap(Tessellation tessellation) {
        SplittableRandom sizes = new SplittableRandom(427);
        for (int i = 0; i < LEVELS; i++) {
            double width = 20.0 + sizes.nextDouble() * 52.0;
            double height = 15.0 + sizes.nextDouble() * 39.0;
            double side = 1.0 + sizes.nextDouble() * 0.7;
            long seed = sizes.nextLong();
            assertThatCode(() -> tessellation.make(width, height, side, new Recorder(new SplittableRandom(seed))))
                    .as("%s, %s x %s, side %s, seed %d", tessellation.type(), width, height, side, seed)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * A level found failing in the random search: a rhomb's clipped sliver, about a micrometre wide, was dropped
     * for having an area under 1e-12, leaving its neighbours an edge with no cell across it.
     */
    @Test
    void aSliverAtTheBottomSideIsKept() {
        assertThatCode(() -> Tessellation.Penrose.DEFAULT.make(66.83593155157503, 34.199269907994065,
                1.3766945212110522, new Recorder(new SplittableRandom(6091129598413085158L))))
                .doesNotThrowAnyException();
    }
}
