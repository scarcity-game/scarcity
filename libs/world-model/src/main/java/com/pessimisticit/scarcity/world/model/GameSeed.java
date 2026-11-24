package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.random.RandomGenerator;

/**
 * What a game was made from (docs/resources.md, "Games"): the {@code value} its own draws are made
 * from, and the {@code build} (the git sha of the server) that made it. The same value and build
 * make the same game; a seed from another build may make a different one (the catalogue or the
 * draws may have changed), which {@link #sameBuild} tells. {@code value} is below 2^53, so a
 * JavaScript client reads it exactly.
 */
public record GameSeed(
        @JsonProperty(required = true) long value,
        @JsonProperty(required = true) String build) {

    /** Every value is below this: 2^53. */
    public static final long LIMIT = 1L << 53;

    /** {@code build} when the server doesn't know its git sha (a local build). */
    public static final String UNKNOWN_BUILD = "unknown";

    public GameSeed {
        if (value < 0 || value >= LIMIT) {
            throw new IllegalArgumentException("value: must be in [0, 2^53), got " + value);
        }
        if (!Check.text(build, "build").matches("[0-9A-Za-z._-]{1,64}")) {
            throw new IllegalArgumentException("build: not a build id: " + build);
        }
    }

    /** A new seed from {@code rng}, made by {@code build}. */
    public static GameSeed draw(RandomGenerator rng, String build) {
        return new GameSeed(rng.nextLong(LIMIT), build);
    }

    /** Whether {@code build} made this seed, so remaking the game from it gives the same game. */
    public boolean sameBuild(String otherBuild) {
        return !UNKNOWN_BUILD.equals(build) && build.equals(otherBuild);
    }
}
