package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A way of cutting a {@code width} by {@code height} metre box from (0, 0) into cells (#386),
 * sized by {@code side}: the cells' mean area is about {@code side²}, so levels of every
 * tessellation have about as many cells. {@link WorldGen#make} gives it
 * {@link WorldGen#cellSize}.
 */
public interface Tessellation {

    /** Its name in a level's log ({@code tessellation.type}). */
    String type();

    /** Its parameters as a level's log gives them ({@code tessellation.params}). */
    Map<String, Object> params(double width, double height, double side);

    /** The cells, any choice drawn from {@code recorder}. */
    CellGraph make(double width, double height, double side, Recorder recorder);

    /** Squares of {@code side}, as many as fit (#234). */
    record Squares() implements Tessellation {

        @Override
        public String type() {
            return "squares";
        }

        @Override
        public Map<String, Object> params(double width, double height, double side) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("cols", (int) Math.floor(width / side));
            params.put("rows", (int) Math.floor(height / side));
            params.put("size", side);
            return params;
        }

        @Override
        public CellGraph make(double width, double height, double side, Recorder recorder) {
            return new SquareGrid((int) Math.floor(width / side), (int) Math.floor(height / side), side);
        }
    }

    /** A Voronoi tessellation ({@link VoronoiGraph}) of {@link #sites} sites and {@code relaxations} rounds of Lloyd relaxation (#312). */
    record Voronoi(int relaxations) implements Tessellation {

        public static final Voronoi DEFAULT = new Voronoi(3);

        /** The sites for cells of mean area {@code side²}, at least 3. */
        public static int sites(double width, double height, double side) {
            return Math.max(3, (int) Math.round(width * height / (side * side)));
        }

        @Override
        public String type() {
            return "voronoi";
        }

        @Override
        public Map<String, Object> params(double width, double height, double side) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("width", width);
            params.put("height", height);
            params.put("sites", sites(width, height, side));
            params.put("relaxations", relaxations);
            return params;
        }

        @Override
        public CellGraph make(double width, double height, double side, Recorder recorder) {
            return new VoronoiGraph(width, height, sites(width, height, side), relaxations, recorder);
        }
    }

    /**
     * An aperiodic Penrose rhomb tiling ({@link PenroseGraph}), rhombs of side {@code edge} times
     * {@code side}. By default ({@link #MEAN_AREA_EDGE}) the mean rhomb area is {@code side²}:
     * thick and thin rhombs come in the golden ratio, so the mean area is
     * {@code (φ sin 72° + sin 36°) / (φ + 1)} of a rhomb's side squared.
     */
    record Penrose(double edge) implements Tessellation {

        private static final double PHI = (1 + Math.sqrt(5)) / 2;

        /** The rhombs' side, in units of {@code side}, for a mean area of {@code side²}: about 1.11. */
        public static final double MEAN_AREA_EDGE = 1 / Math.sqrt(
                (PHI * Math.sin(Math.toRadians(72)) + Math.sin(Math.toRadians(36))) / (PHI + 1));

        public static final Penrose DEFAULT = new Penrose(MEAN_AREA_EDGE);

        @Override
        public String type() {
            return "penrose";
        }

        @Override
        public Map<String, Object> params(double width, double height, double side) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("width", width);
            params.put("height", height);
            params.put("edge", edge * side);
            return params;
        }

        @Override
        public CellGraph make(double width, double height, double side, Recorder recorder) {
            return new PenroseGraph(width, height, edge * side, recorder);
        }
    }

    /**
     * Regular hexagons ({@link HexLattice}) of mean area {@code side²}, so a hexagon's side is
     * {@code side √(2 / 3√3)}, clipped to the box (#419). Every interior cell has six neighbours.
     */
    record Hexagons() implements Tessellation {

        /** A hexagon's side, in units of {@code side}, for an area of {@code side²}: about 0.62. */
        public static final double EDGE = Math.sqrt(2 / (3 * Math.sqrt(3)));

        @Override
        public String type() {
            return "hexagons";
        }

        @Override
        public Map<String, Object> params(double width, double height, double side) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("width", width);
            params.put("height", height);
            params.put("edge", EDGE * side);
            return params;
        }

        @Override
        public CellGraph make(double width, double height, double side, Recorder recorder) {
            return new PolygonGraph(new HexLattice(width, height, EDGE * side, "hexagons", recorder).hexagons(),
                    new Position(0.0, 0.0), new Position(width, height));
        }
    }

    /**
     * The rhombille tiling (#420): each hexagon of {@link HexLattice} cut into three rhombs through
     * its centre, 60 and 120 degrees, of mean area {@code side²}, so a rhomb's side is
     * {@code side √(2 / √3)}. Cells meet three or six at a vertex.
     */
    record Rhombille() implements Tessellation {

        /** A rhomb's side, in units of {@code side}, for an area of {@code side²}: about 1.07. */
        public static final double EDGE = Math.sqrt(2 / Math.sqrt(3));

        @Override
        public String type() {
            return "rhombille";
        }

        @Override
        public Map<String, Object> params(double width, double height, double side) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("width", width);
            params.put("height", height);
            params.put("edge", EDGE * side);
            return params;
        }

        @Override
        public CellGraph make(double width, double height, double side, Recorder recorder) {
            return new PolygonGraph(new HexLattice(width, height, EDGE * side, "rhombille", recorder).rhombs(),
                    new Position(0.0, 0.0), new Position(width, height));
        }
    }
}
