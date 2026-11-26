package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.gen.WorldGen;
import com.pessimisticit.scarcity.world.model.Draw;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** The size, openness and cell side of each dive level (#395): drawn in order, over their ranges, and every corner makes a level. */
class LevelShapeTest {

    private static final double LEAST = WorldGen.cellSize(Levels.PASSER_RADIUS);
    private static final int SWEEP = 300;
    private static final double JUST_UNDER = 1.0 - 1e-9;

    @Test
    void theShapeIsDrawnAfterTheStrategyAndMaterialInOrderWithItsDistributions() {
        for (int seed = 0; seed < 30; seed++) {
            Levels.Level level = Levels.make(0, new SplittableRandom(seed));
            List<Recorder.Named> draws = level.draws();
            assertThat(draws).extracting(Recorder.Named::name).startsWith("level.strategy", "level.material",
                    "level.width", "level.height", "level.cellSide");
            Draw.Gaussian width = (Draw.Gaussian) draws.get(2).draw();
            Draw.Gaussian height = (Draw.Gaussian) draws.get(3).draw();
            Draw.Gaussian side = (Draw.Gaussian) draws.get(4).draw();
            assertThat(width).extracting(Draw.Gaussian::mean, Draw.Gaussian::sd, Draw.Gaussian::truncate)
                    .containsExactly(LevelShape.WIDTH_MEAN, LevelShape.WIDTH_SD,
                            new Draw.Truncate(LevelShape.MIN_WIDTH, LevelShape.MAX_WIDTH));
            assertThat(height).extracting(Draw.Gaussian::mean, Draw.Gaussian::sd, Draw.Gaussian::truncate)
                    .containsExactly(LevelShape.HEIGHT_MEAN, LevelShape.HEIGHT_SD,
                            new Draw.Truncate(LevelShape.MIN_HEIGHT, LevelShape.MAX_HEIGHT));
            assertThat(side.mean()).isEqualTo(LevelShape.SIDE_MEAN * LEAST);
            assertThat(side.sd()).isEqualTo(LevelShape.SIDE_SD * LEAST);
            assertThat(side.truncate()).isEqualTo(new Draw.Truncate(LevelShape.SIDE_MIN * LEAST, LevelShape.SIDE_MAX * LEAST));
            assertThat(level.width()).isEqualTo(width.value());
            assertThat(level.height()).isEqualTo(height.value());
            assertThat(level.cellSide()).isEqualTo(side.value()).isGreaterThanOrEqualTo(LEAST);
            assertOpenness(level, draws.stream().filter(d -> d.name().startsWith("level.openness.")).findFirst()
                    .orElseThrow());
        }
    }

    private static void assertOpenness(Levels.Level level, Recorder.Named named) {
        Draw.Gaussian openness = (Draw.Gaussian) named.draw();
        String type = (String) level.generator().get("type");
        Map<?, ?> params = (Map<?, ?>) level.generator().get("params");
        if (type.equals("cave")) {
            assertThat(named.name()).isEqualTo("level.openness.fill");
            assertThat(openness.mean()).isEqualTo(LevelShape.FILL_MEAN);
            assertThat(openness.sd()).isEqualTo(LevelShape.OPENNESS_SD * LevelShape.FILL_MEAN);
            assertThat(openness.truncate()).isEqualTo(new Draw.Truncate(LevelShape.FILL_MIN, LevelShape.FILL_MAX));
            assertThat(params.get("fill")).isEqualTo(openness.value());
        } else {
            assertThat(named.name()).isEqualTo("level.openness.roomSides");
            assertThat(openness.mean()).isEqualTo(LevelShape.ROOMS_MEAN);
            assertThat(openness.sd()).isEqualTo(LevelShape.OPENNESS_SD * LevelShape.ROOMS_MEAN);
            assertThat(openness.truncate()).isEqualTo(new Draw.Truncate(LevelShape.ROOMS_MIN, LevelShape.ROOMS_MAX));
            double min = Math.max(2.4 * openness.value(), 2.0 * level.cellSide());
            assertThat((double) params.get("minSide")).isEqualTo(min);
            assertThat((double) params.get("maxSide")).isEqualTo(Math.max(5.6 * openness.value(), 1.5 * min));
        }
        Map<?, ?> tessellation = (Map<?, ?>) level.tessellation().get("params");
        if (tessellation.containsKey("size")) {
            assertThat(tessellation.get("size")).isEqualTo(level.cellSide());
        }
    }

    @Test
    void everySeedOfASweepMakesALevelAndTheSizesAndSidesCoverTheirRanges() {
        DoubleSummaryStatistics widths = new DoubleSummaryStatistics();
        DoubleSummaryStatistics heights = new DoubleSummaryStatistics();
        DoubleSummaryStatistics sides = new DoubleSummaryStatistics();
        for (int seed = 0; seed < SWEEP; seed++) {
            Levels.Level level = Levels.make(seed % 4, new SplittableRandom(seed));
            widths.accept(level.width());
            heights.accept(level.height());
            sides.accept(level.cellSide());
            assertThat(level.up().x()).as("seed " + seed).isBetween(0.0, level.width());
            assertThat(level.up().y()).as("seed " + seed).isBetween(0.0, level.height());
            assertThat(level.stairsDown().position().x()).isBetween(0.0, level.width());
            assertThat(level.stairsDown().position().y()).isBetween(0.0, level.height());
            assertThat(level.walls()).as("seed " + seed).isNotEmpty();
            assertThat(level.cells()).as("seed " + seed).isNotEmpty();
        }
        assertThat(widths.getAverage()).isCloseTo(LevelShape.WIDTH_MEAN, offset(3.0));
        assertThat(widths.getMin()).isLessThan(LevelShape.WIDTH_MEAN - 2 * LevelShape.WIDTH_SD);
        assertThat(widths.getMax()).isGreaterThan(LevelShape.WIDTH_MEAN + LevelShape.WIDTH_SD);
        assertThat(heights.getAverage()).isCloseTo(LevelShape.HEIGHT_MEAN, offset(3.0));
        assertThat(heights.getMin()).isLessThan(LevelShape.HEIGHT_MEAN - 2 * LevelShape.HEIGHT_SD);
        assertThat(heights.getMax()).isGreaterThan(LevelShape.HEIGHT_MEAN + LevelShape.HEIGHT_SD);
        assertThat(sides.getMin()).isGreaterThanOrEqualTo(LEAST).isLessThan(1.05 * LEAST);
        assertThat(sides.getMax()).isGreaterThan(1.3 * LEAST).isLessThan(LevelShape.SIDE_MAX * LEAST);
    }

    @Test
    void narrowRangesGiveSmallLevelsWithTheDrawsStillRecordedAsGaussiansOverThem() {
        LevelShape.Ranges small = new LevelShape.Ranges(20.0, 21.0, 15.0, 16.0, 1.0, 1.1);
        for (int seed = 0; seed < 10; seed++) {
            Levels.Level level = Levels.make(0, new SplittableRandom(seed), small);
            assertThat(level.width()).isBetween(20.0, 21.0);
            assertThat(level.height()).isBetween(15.0, 16.0);
            assertThat(level.cellSide()).isBetween(LEAST, 1.1 * LEAST);
            Draw.Gaussian width = (Draw.Gaussian) level.draws().get(2).draw();
            assertThat(width.mean()).isEqualTo(21.0);
            assertThat(width.truncate()).isEqualTo(new Draw.Truncate(20.0, 21.0));
        }
        assertThat(LevelShape.Ranges.FULL.maxWidth()).isEqualTo(LevelShape.MAX_WIDTH);
        assertThatThrownBy(() -> new LevelShape.Ranges(20.0, 20.0, 15.0, 16.0, 1.0, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LevelShape.Ranges(20.0, 21.0, 15.0, 16.0, 0.9, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameSeedMakesTheSameLevel() {
        Levels.Level a = Levels.make(2, new SplittableRandom(11));
        Levels.Level b = Levels.make(2, new SplittableRandom(11));

        assertThat(a).usingRecursiveComparison().isEqualTo(b);
        assertThat(a.width()).isEqualTo(b.width());
        assertThat(Levels.make(2, new SplittableRandom(12)).width()).isNotEqualTo(a.width());
    }

    @Test
    void everyCornerOfTheRangesMakesALevel() {
        int made = 0;
        for (int way = 0; way < Levels.STRATEGIES.size(); way++) {
            for (double w : new double[] {0.0, JUST_UNDER}) {
                for (double h : new double[] {0.0, JUST_UNDER}) {
                    for (double side : new double[] {LevelShape.SIDE_MIN, LevelShape.SIDE_MAX}) {
                        for (boolean open : new boolean[] {false, true}) {
                            for (double[] outline : OUTLINE_CORNERS) {
                                for (int rounds : roundsOf(way)) {
                                    Levels.Level level = corner(way, w, h, side, open, outline, rounds);
                                    assertThat(level.walls()).isNotEmpty();
                                    assertThat(level.cells()).isNotEmpty();
                                    made++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(made).as("16 corners, each as a triangle, an octagon and a star, Voronoi at the fewest, middle and most rounds")
                .isEqualTo((Levels.STRATEGIES.size() + 2) * 48);
    }

    private static final int VORONOI = Levels.STRATEGIES.indexOf("voronoi");

    /** The relaxation rounds to try for strategy {@code way}: Voronoi's fewest, a middle and most, else none ({@code -1}). */
    private static int[] roundsOf(int way) {
        return way == VORONOI ? new int[] {0, 2, Levels.RELAXATION_WEIGHTS.size() - 1} : new int[] {-1};
    }

    /** The {@code level.outline} and {@code outline.sides} points of a triangle, an octagon and a star. */
    private static final double[][] OUTLINE_CORNERS = {{0.25, 0.0}, {0.25, JUST_UNDER}, {0.75, 0.0}};

    /** The level whose draws are the given points: {@code u} of the way along each uniform, the side and openness values. */
    private static Levels.Level corner(int way, double widthAt, double heightAt, double side, boolean open,
            double[] outlineAt, int rounds) {
        boolean caves = way > 0;
        double target = open == caves ? (caves ? LevelShape.FILL_MIN : LevelShape.ROOMS_MIN)
                : (caves ? LevelShape.FILL_MAX : LevelShape.ROOMS_MAX);
        double sideTimes = side == LevelShape.SIDE_MAX ? side - 1e-9 : side;
        return level(way, widthAt, heightAt, sideTimes, target, outlineAt, rounds, 1);
    }

    /**
     * The level of strategy {@code way} whose width and height are {@code widthAt} and {@code heightAt} of the
     * way across their ranges, with the side in times the least, the openness value, the outline and side
     * count points, a Voronoi level's relaxation {@code rounds} (ignored for the others) and later draws from
     * {@code seed}.
     */
    private static Levels.Level level(int way, double widthAt, double heightAt, double sideTimes, double openness,
            double[] outlineAt, int rounds, long seed) {
        boolean caves = way > 0;
        double mean = caves ? LevelShape.FILL_MEAN : LevelShape.ROOMS_MEAN;
        List<Double> uniforms = way == VORONOI
                ? List.of(strategyPoint(way), 0.0, outlineAt[0], categoricalPoint(Levels.RELAXATION_WEIGHTS, rounds),
                        outlineAt[1])
                : List.of(strategyPoint(way), 0.0, outlineAt[0], outlineAt[1]);
        List<Double> gaussians = List.of(
                z(LevelShape.MIN_WIDTH, LevelShape.MAX_WIDTH, widthAt, LevelShape.WIDTH_MEAN, LevelShape.WIDTH_SD),
                z(LevelShape.MIN_HEIGHT, LevelShape.MAX_HEIGHT, heightAt, LevelShape.HEIGHT_MEAN,
                        LevelShape.HEIGHT_SD),
                (sideTimes - LevelShape.SIDE_MEAN) / LevelShape.SIDE_SD,
                (openness - mean) / (LevelShape.OPENNESS_SD * mean));
        return Levels.make(0, new Scripted(new SplittableRandom(seed), uniforms, gaussians));
    }

    /** A source that gives the first draws it is told to, then continues from {@code rest}. */
    /** The middle of strategy {@code way}'s share of the categorical's unit interval. */
    private static double strategyPoint(int way) {
        return categoricalPoint(Levels.STRATEGY_WEIGHTS, way);
    }

    /** The middle of option {@code i}'s share of a categorical's unit interval, by {@code weights}. */
    private static double categoricalPoint(List<Double> weights, int way) {
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double before = weights.subList(0, way).stream().mapToDouble(Double::doubleValue).sum();
        return (before + weights.get(way) / 2) / total;
    }

    /** The standard normal point giving the value {@code at} of the way across [min, max), kept just inside it. */
    private static double z(double min, double max, double at, double mean, double sd) {
        double value = Math.clamp(min + at * (max - min), min + 1e-6, max - 1e-6);
        return (value - mean) / sd;
    }

    private static final class Scripted implements RandomGenerator {
        private final RandomGenerator rest;
        private final List<Double> uniforms;
        private final List<Double> gaussians;
        private int u;
        private int g;

        Scripted(RandomGenerator rest, List<Double> uniforms, List<Double> gaussians) {
            this.rest = rest;
            this.uniforms = new ArrayList<>(uniforms);
            this.gaussians = new ArrayList<>(gaussians);
        }

        @Override
        public long nextLong() {
            return rest.nextLong();
        }

        @Override
        public double nextDouble() {
            return u < uniforms.size() ? uniforms.get(u++) : rest.nextDouble();
        }

        @Override
        public double nextGaussian() {
            return g < gaussians.size() ? gaussians.get(g++) : rest.nextGaussian();
        }
    }
}
