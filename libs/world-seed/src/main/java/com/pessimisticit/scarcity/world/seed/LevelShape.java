package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.WorldGen;

/**
 * How big a dive level is, drawn per level (#395): the box's width and height, uniform from very
 * small to quite large, and the cell side, a truncated gaussian from today's value to much larger. The
 * distributions are the named constants here; the openness ones sit with their strategy in
 * {@link Levels}. Each bound is where generation still succeeds for every seed of {@code LevelsTest}'s sweep.
 */
public record LevelShape(double width, double height, double cellSide) {

    /** Width, uniform in [{@value}, {@link #MAX_WIDTH}) m: today's 24 m is inside, 10 m is a few rooms. */
    public static final double MIN_WIDTH = 20.0;

    /** The width's ceiling (exclusive), in metres: three times today's. */
    public static final double MAX_WIDTH = 72.0;

    /** Height, uniform in [{@value}, {@link #MAX_HEIGHT}) m, drawn separately from the width: today's 18 m is inside. */
    public static final double MIN_HEIGHT = 15.0;

    /** The height's ceiling (exclusive), in metres: three times today's. */
    public static final double MAX_HEIGHT = 54.0;

    /** The cell side's mean, in times the least side {@code cellSize(passer)}: just above today's 1, which is also the floor. */
    public static final double SIDE_MEAN = 1.1;

    /** Its sd, in the same unit: moderate, so most levels stay under 1.5 and some reach 2. */
    public static final double SIDE_SD = 0.3;

    /** The floor, in the same unit: the diver must fit a one-cell passage, so never below {@code cellSize(passer)}. */
    public static final double SIDE_MIN = 1.0;

    /** The ceiling (exclusive), in the same unit: three times today's side, 1.4 m cells. */
    public static final double SIDE_MAX = 1.7;

    /** Openness's sd, as a share of the distribution's centre: wide, 20%. */
    public static final double OPENNESS_SD = 0.2;

    /**
     * Caves: the share of cells that start as rock ({@code fill}), centred on today's 0.5 (sd 0.1), truncated
     * to [{@value #FILL_MIN}, {@value #FILL_MAX}): below that the rock is too sparse to make walls worth the name,
     * above it too few regions stay big enough to join.
     */
    public static final double FILL_MEAN = 0.5;
    public static final double FILL_MIN = 0.4;
    public static final double FILL_MAX = 0.6;

    /**
     * Rooms and corridors: a factor on the room sides (today's 2.4 to 5.6 m), centred on 1 (sd 0.2),
     * truncated to [{@value #ROOMS_MIN}, {@value #ROOMS_MAX}): 0.6 makes rooms of 1.4 to 3.4 m, hardly wider
     * than corridors; 1.3 makes 3.1 to 7.3 m.
     */
    public static final double ROOMS_MEAN = 1.0;
    public static final double ROOMS_MIN = 0.6;
    public static final double ROOMS_MAX = 1.3;

    /** The width, height and cell side of a level for a passer of {@code passerRadius}, each drawn and recorded. */
    static LevelShape draw(Recorder recorder, double passerRadius) {
        double width = recorder.uniform("level.width", MIN_WIDTH, MAX_WIDTH);
        double height = recorder.uniform("level.height", MIN_HEIGHT, MAX_HEIGHT);
        double least = WorldGen.cellSize(passerRadius);
        double side = recorder.truncatedGaussian("level.cellSide", SIDE_MEAN * least, SIDE_SD * least,
                SIDE_MIN * least, SIDE_MAX * least);
        return new LevelShape(width, height, side);
    }
}
