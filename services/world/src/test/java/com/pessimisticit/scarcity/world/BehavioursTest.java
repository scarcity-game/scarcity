package com.pessimisticit.scarcity.world;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class BehavioursTest {

    @Test
    void pickTwoEqualWeights() {
        assertThat(Behaviours.pick(List.of(1.0, 1.0), 0.0)).isEqualTo(0);
        assertThat(Behaviours.pick(List.of(1.0, 1.0), 0.4999999)).isEqualTo(0);
        assertThat(Behaviours.pick(List.of(1.0, 1.0), 0.5)).isEqualTo(1);
        assertThat(Behaviours.pick(List.of(1.0, 1.0), 0.9999999)).isEqualTo(1);
    }

    @Test
    void pickUnequalWeights() {
        assertThat(Behaviours.pick(List.of(1.0, 3.0), 0.25)).isEqualTo(1);
        assertThat(Behaviours.pick(List.of(1.0, 3.0), 0.2499)).isEqualTo(0);
    }

    @Test
    void pickSingleWeight() {
        assertThat(Behaviours.pick(List.of(5.0), 0.7)).isEqualTo(0);
    }

    @Test
    void pickThreeWeights() {
        assertThat(Behaviours.pick(List.of(1.0, 2.0, 3.0), 0.34)).isEqualTo(1);
    }
}
