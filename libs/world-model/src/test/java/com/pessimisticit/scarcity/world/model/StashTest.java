package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** A stash's radius comes from the mass of its items (docs/resources.md, "Items and stashes"). */
class StashTest {

    @Test
    void radiusIsThatOfAHemisphericalPileClamped() {
        assertThat(Stash.radiusFor(10_000.0)).isCloseTo(0.1440, within(1e-4));
        assertThat(Stash.radiusFor(100_000.0)).isCloseTo(0.3101, within(1e-4));
        assertThat(Stash.radiusFor(1_000_000.0)).isCloseTo(0.6683, within(1e-4));
        assertThat(Stash.radiusFor(1_000.0)).isEqualTo(0.1);
        assertThat(Stash.radiusFor(0.0)).isEqualTo(0.1);
        assertThat(Stash.radiusFor(1e7)).isEqualTo(1.0);
    }

    @Test
    void aPileOfTheSmallestOrLargestRadiusWeighsItsHemisphere() {
        double smallest = 2.0 / 3.0 * Math.PI * Math.pow(Stash.MIN_RADIUS, 3) * Stash.PILE_DENSITY;
        double largest = 2.0 / 3.0 * Math.PI * Math.pow(Stash.MAX_RADIUS, 3) * Stash.PILE_DENSITY;
        assertThat(Stash.radiusFor(smallest)).isCloseTo(Stash.MIN_RADIUS, within(1e-12));
        assertThat(Stash.radiusFor(largest)).isCloseTo(Stash.MAX_RADIUS, within(1e-12));
        assertThat(Stash.radiusFor(8 * smallest)).as("8 times the mass, twice the radius")
                .isCloseTo(2 * Stash.MIN_RADIUS, within(1e-12));
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
