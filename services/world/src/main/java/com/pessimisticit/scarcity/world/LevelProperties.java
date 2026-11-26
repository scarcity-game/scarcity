package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.seed.LevelShape;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code world.levels}: the ranges a dive level's width, height and cell side are drawn from (#395,
 * {@link LevelShape.Ranges}). Absent, each is the full range; a test narrows them for small, quick levels
 * (a level of 20 x 15 m with cells of today's size). The side bounds are in times the least side.
 */
@Validated
@ConfigurationProperties("world.levels")
record LevelProperties(@Positive Double minWidth, @Positive Double maxWidth, @Positive Double minHeight,
        @Positive Double maxHeight, @Positive Double sideMin, @Positive Double sideMax) {

    /** The configured ranges, each unset bound the full range's. */
    LevelShape.Ranges ranges() {
        LevelShape.Ranges full = LevelShape.Ranges.FULL;
        return new LevelShape.Ranges(or(minWidth, full.minWidth()), or(maxWidth, full.maxWidth()),
                or(minHeight, full.minHeight()), or(maxHeight, full.maxHeight()), or(sideMin, full.sideMin()),
                or(sideMax, full.sideMax()));
    }

    private static double or(Double configured, double full) {
        return configured == null ? full : configured;
    }
}
