package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.seed.LevelShape;
import org.junit.jupiter.api.Test;

/** {@code world.levels} (#395): unset is the full range, set bounds are used. */
class LevelPropertiesTest {

    @Test
    void unsetBoundsAreTheFullRangesAndSetOnesAreUsed() {
        assertThat(new LevelProperties(null, null, null, null, null, null).ranges()).isEqualTo(LevelShape.Ranges.FULL);
        assertThat(new LevelProperties(20.0, 21.0, 15.0, 16.0, 1.0, 1.1).ranges())
                .isEqualTo(new LevelShape.Ranges(20.0, 21.0, 15.0, 16.0, 1.0, 1.1));
    }
}
