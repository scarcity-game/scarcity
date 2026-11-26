package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.WorldGen;

/**
 * How big a dive level is, drawn per level (#395): the box's width and height and the cell side, each a
 * truncated gaussian centred on a level that played well (area a1d23b9b: 52.3 x 44.4 m, 1.0 m cells, fill
 * 0.53, Voronoi caves), spread over the range from very small to quite large. The
 * distributions are the named constants here; the openness ones sit with their strategy in
 * {@link Levels}. Each bound is where generation still succeeds for every seed of {@code LevelsTest}'s sweep.
 */
public record LevelShape(double width, double height, double cellSide) {

    /** Width's floor, in metres: a few rooms. */
    public static final double MIN_WIDTH = 20.0;

    /** The width's ceiling (exclusive), in metres: three times today's. */
    public static final double MAX_WIDTH = 72.0;

    /** Width's mean, in metres: the liked level's 52.3 m. */
    public static final double WIDTH_MEAN = 52.0;

    /** Width's sd, in metres: most levels 42 to 62 m, the whole range still reachable. */
    public static final double WIDTH_SD = 10.0;

    /** Height's floor, in metres, drawn separately from the width. */
    public static final double MIN_HEIGHT = 15.0;

    /** The height's ceiling (exclusive), in metres: three times today's. */
    public static final double MAX_HEIGHT = 54.0;

    /** Height's mean, in metres: the liked level's 44.4 m. */
    public static final double HEIGHT_MEAN = 44.0;

    /** Height's sd, in metres: most levels 36 to 52 m. */
    public static final double HEIGHT_SD = 8.0;

    /** The cell side's mean, in times the least side {@code cellSize(passer)}: the liked level's 1.0 m of 0.8 m. */
    public static final double SIDE_MEAN = 1.25;

    /** Its sd, in the same unit: moderate, so most levels stay under 1.5 and some reach 2. */
    public static final double SIDE_SD = 0.3;

    /** The floor, in the same unit: the diver must fit a one-cell passage, so never below {@code cellSize(passer)}. */
    public static final double SIDE_MIN = 1.0;

    /** The ceiling (exclusive), in the same unit: three times today's side, 1.4 m cells. */
    public static final double SIDE_MAX = 1.7;

    /** Openness's sd, as a share of the distribution's centre: wide, 20%. */
    public static final double OPENNESS_SD = 0.2;

    /**
     * Caves: the share of cells that start as rock ({@code fill}), centred on the liked level's 0.53, truncated
     * to [{@value #FILL_MIN}, {@value #FILL_MAX}): below that the rock is too sparse to make walls worth the name,
     * above it too few regions stay big enough to join.
     */
    public static final double FILL_MEAN = 0.53;
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

    /**
     * The ranges the size and cell side are drawn from: {@link #FULL} in production, a narrow one where a test
     * wants small quick levels (services/world's {@code world.levels}). The widths and heights are the
     * gaussians of {@link #WIDTH_MEAN} and {@link #HEIGHT_MEAN}, each mean held inside the range, truncated
     * to [min, max); the side is the gaussian of {@link #SIDE_MEAN} and {@link #SIDE_SD} truncated to
     * [{@code sideMin}, {@code sideMax}) times the least side. Every draw is still recorded.
     */
    public record Ranges(double minWidth, double maxWidth, double minHeight, double maxHeight, double sideMin,
            double sideMax) {

        public static final Ranges FULL = new Ranges(MIN_WIDTH, MAX_WIDTH, MIN_HEIGHT, MAX_HEIGHT, SIDE_MIN, SIDE_MAX);

        public Ranges {
            if (!(minWidth > 0.0 && maxWidth > minWidth && minHeight > 0.0 && maxHeight > minHeight)) {
                throw new IllegalArgumentException("level ranges: need 0 < min < max for the width and the height");
            }
            if (!(sideMin >= SIDE_MIN && sideMax > sideMin)) {
                throw new IllegalArgumentException("level ranges: need " + SIDE_MIN + " <= sideMin < sideMax");
            }
        }
    }

    /** The width, height and cell side of a level for a passer of {@code passerRadius}, each drawn and recorded. */
    static LevelShape draw(Recorder recorder, double passerRadius, Ranges ranges) {
        double width = centred(recorder, "level.width", WIDTH_MEAN, WIDTH_SD, ranges.minWidth(), ranges.maxWidth());
        double height = centred(recorder, "level.height", HEIGHT_MEAN, HEIGHT_SD, ranges.minHeight(),
                ranges.maxHeight());
        double least = WorldGen.cellSize(passerRadius);
        double side = recorder.truncatedGaussian("level.cellSide", SIDE_MEAN * least, SIDE_SD * least,
                ranges.sideMin() * least, ranges.sideMax() * least);
        return new LevelShape(width, height, side);
    }

    /** A gaussian of {@code mean}, held inside [min, max] so a narrow range is still quick to draw, truncated to it. */
    private static double centred(Recorder recorder, String name, double mean, double sd, double min, double max) {
        return recorder.truncatedGaussian(name, Math.clamp(mean, min, max), sd, min, max);
    }
}
