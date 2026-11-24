package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** A stash's radius comes from the mass of its items (docs/resources.md, "Items and stashes"). */
class StashTest {

    @Test
    void radiusIsATenthOfTheLogOfTheGramsClamped() {
        assertThat(Stash.radiusFor(10.0)).isCloseTo(0.1, within(1e-12));
        assertThat(Stash.radiusFor(100.0)).isCloseTo(0.2, within(1e-12));
        assertThat(Stash.radiusFor(1_000.0)).isCloseTo(0.3, within(1e-12));
        assertThat(Stash.radiusFor(1_000_000.0)).isCloseTo(0.6, within(1e-12));
        assertThat(Stash.radiusFor(5.0)).isEqualTo(0.1);
        assertThat(Stash.radiusFor(0.0)).isEqualTo(0.1);
        assertThat(Stash.radiusFor(1e7)).isCloseTo(0.7, within(1e-12));
        assertThat(Stash.radiusFor(1e10)).isEqualTo(1.0);
        assertThat(Stash.radiusFor(1e12)).isEqualTo(1.0);
    }

    @Test
    void aNewStashIsOfTheSmallestRadiusAndOutOfRangeIsRefused() {
        Stash stash = Stash.at(new Position(1.0, 1.0));
        assertThat(stash.radius()).isEqualTo(Stash.MIN_RADIUS);
        assertThat(stash.joinDistance()).isEqualTo(0.2);
        assertThatThrownBy(() -> stash.withRadius(0.05)).hasMessageContaining("radius");
        assertThatThrownBy(() -> stash.withRadius(1.5)).hasMessageContaining("radius");
    }
}
