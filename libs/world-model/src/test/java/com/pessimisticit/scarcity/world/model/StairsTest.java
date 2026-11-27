package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Stairs name the paired stairs and the area that holds them together, or neither (#235). */
class StairsTest {

    private static final String PAIR = "entity:00000000-0000-4000-8000-0000000000a2";
    private static final String BELOW = "area:00000000-0000-4000-8000-0000000000b2";

    @Test
    void linkedStairsNameTheirPairAndItsArea() {
        Stairs stairs = Stairs.down("stairs down").withTo(PAIR, BELOW);

        assertThat(stairs.to()).isEqualTo(PAIR);
        assertThat(stairs.toArea()).isEqualTo(BELOW);
        Stairs read = (Stairs) WorldJson.fromJson(WorldJson.toJson(stairs), Resource.class);
        assertThat(read.toArea()).isEqualTo(BELOW);
    }

    @Test
    void stairsThatLeadNowhereNameNeither() {
        Stairs stairs = Stairs.up("stairs up").withTo(null, null);

        assertThat(stairs.to()).isNull();
        assertThat(stairs.toArea()).isNull();
    }

    @Test
    void oneWithoutTheOtherIsRejected() {
        Stairs stairs = Stairs.down("stairs down");

        assertThatThrownBy(() -> stairs.withTo(PAIR, null)).hasMessageContaining("toArea");
        assertThatThrownBy(() -> stairs.withTo(null, BELOW)).hasMessageContaining("toArea");
        assertThatThrownBy(() -> stairs.withTo(PAIR, PAIR)).hasMessageContaining("area id");
    }
}
