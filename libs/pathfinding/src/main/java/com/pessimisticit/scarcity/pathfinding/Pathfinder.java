package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The path of straight legs for a circle from where it is to a point, round every obstacle of the
 * area: an entity with a shape that doesn't {@code overlap}, walls among them (docs/resources.md,
 * "Shapes", "Cells").
 *
 * <ol>
 * <li>If the straight leg is clear, it is the path.</li>
 * <li>Else A* over the floor cells ({@link CellSearch}), within a budget of cells expanded.</li>
 * <li>String-pulling through the corridor's openings ({@link Funnel}), each corner rounded at the
 * mover's radius plus {@link #MARGIN} ({@link Rounding}).</li>
 * <li>Every leg is checked with {@code Contacts.first} ({@link Probe}); one that isn't clear (a
 * corner the funnel didn't turn round, another mover) is mended by a visibility graph over the
 * obstacles near it, and left as it is if that finds nothing: the {@code step} is cut short and
 * the caller plans again.</li>
 * </ol>
 *
 * <p>An area with no cells (made before #353) is searched with a visibility graph over all its
 * obstacles, as before. Other movers count where they are now; the caller plans again each turn.
 */
public final class Pathfinder {

    /** How much further than its radius a path keeps the mover from obstacles' corners, in m. */
    public static final double MARGIN = 0.05;

    /**
     * How a search is bounded: at most {@code budget} cells expanded (then there is no path);
     * other movers' cells are not entered within {@code near} cells of the start, and cost
     * {@code crowding} metres more each beyond.
     */
    public record Settings(int budget, int near, double crowding) {

        public static final Settings DEFAULT = new Settings(5000, 3, 2.0);

        public Settings {
            if (budget < 1 || near < 0 || !(crowding >= 0.0)) {
                throw new IllegalArgumentException("budget >= 1, near >= 0 and crowding >= 0, got " + budget
                        + ", " + near + ", " + crowding);
            }
        }
    }

    private final AreaView view;
    private final Probe probe;
    private final Settings settings;

    private Pathfinder(AreaView view, String moverId, Settings settings) {
        this.view = view;
        this.probe = Probe.of(view, moverId);
        this.settings = settings;
    }

    /**
     * The waypoints from the entity {@code moverId}'s position to {@code to}: each one the end of
     * a leg, the last one {@code to}, the mover's position not among them. Empty when the mover
     * can't reach {@code to} (or the budget ran out); an empty list when it is there.
     */
    public static Optional<List<Position>> path(AreaView view, String moverId, Position to) {
        return path(view, moverId, to, Settings.DEFAULT);
    }

    /** {@link #path(AreaView, String, Position)} with these bounds. */
    public static Optional<List<Position>> path(AreaView view, String moverId, Position to, Settings settings) {
        return plan(view, moverId, to, settings).path();
    }

    /** How a path was found: by which {@code way}, the cells expanded, and the legs mended. */
    record Plan(Optional<List<Position>> path, Way way, int expanded, int mended) {
    }

    /** How a plan was made. */
    enum Way { STRAIGHT, CELLS, NO_CELLS }

    /** The plan behind {@link #path(AreaView, String, Position, Settings)}. */
    static Plan plan(AreaView view, String moverId, Position to, Settings settings) {
        return new Pathfinder(view, moverId, settings).search(to);
    }

    /** Every entity of {@code entities} that stops a mover, but the mover: a shape that doesn't overlap. */
    static Stream<Entity> obstacles(Map<String, Entity> entities, String moverId) {
        return entities.entrySet().stream()
                .filter(e -> e.getValue().shape() != null && !e.getValue().overlap() && !e.getKey().equals(moverId))
                .map(Map.Entry::getValue);
    }

    private Plan search(Position to) {
        Position from = probe.mover().position();
        if (from.equals(to)) {
            return new Plan(Optional.of(List.of()), Way.STRAIGHT, 0, 0);
        }
        if (probe.clear(from, to, true)) {
            return new Plan(Optional.of(List.of(to)), Way.STRAIGHT, 0, 0);
        }
        if (view.cells().isEmpty()) {
            return new Plan(new VisibilityGraph(probe).search(from, to, true), Way.NO_CELLS, 0, 0);
        }
        FloorGraph graph = new FloorGraph(view.cells());
        int start = graph.locate(from);
        int goal = graph.locate(to);
        if (start < 0 || goal < 0) {
            return new Plan(Optional.empty(), Way.CELLS, 0, 0);
        }
        double clearance = probe.radius() + MARGIN;
        CellSearch cells = new CellSearch(graph, probe.radius(), settings, occupants(graph, clearance));
        Optional<CellSearch.Corridor> corridor = cells.search(start, goal, from, to);
        if (corridor.isEmpty()) {
            return new Plan(Optional.empty(), Way.CELLS, cells.expanded(), 0);
        }
        Erosion erosion = new Erosion(graph, clearance, probe.radius() + MARGIN * 0.6, cells.refused().stream().distinct()
                .map(graph.polygons::get).toList());
        List<Funnel.Bend> bends = straightened(Funnel.pull(corridor.get(), erosion, from, to));
        List<Position> rounded = new Refiner(probe).refine(from, bends.stream().map(Rounding.Circle::of).toList());
        Mender mender = new Mender(probe);
        List<Position> path = mender.mend(from, rounded);
        return new Plan(Optional.of(path), Way.CELLS, cells.expanded(), mender.mended());
    }

    /**
     * How many other movers each floor cell has in reach: an obstacle whose position is in a floor
     * cell (walls are in their own), counted in every floor cell within its extent plus the
     * clearance of it.
     */
    private int[] occupants(FloorGraph graph, double clearance) {
        int[] counts = new int[graph.size()];
        obstacles(probe.entities(), probe.moverId()).forEach(obstacle -> {
            int home = graph.containing(obstacle.position());
            if (home >= 0) {
                spread(graph, home, obstacle.position(), extent(obstacle.shape()) + clearance, counts);
            }
        });
        return counts;
    }

    private static void spread(FloorGraph graph, int home, Position at, double reach, int[] counts) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(home);
        seen.add(home);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            counts[c]++;
            for (int link = graph.first[c]; link < graph.first[c + 1]; link++) {
                int next = graph.to[link];
                if (seen.add(next) && FloorGraph.distance(graph.polygons.get(next), at) < reach) {
                    queue.add(next);
                }
            }
        }
    }

    /** Half the diagonal of the shape's bounding box: as far as it reaches from its centre, or more. */
    static double extent(Shape shape) {
        Shape.BoundingBox box = shape.boundingBox();
        return Math.hypot(box.maxX() - box.minX(), box.maxY() - box.minY()) / 2;
    }

    /**
     * The bends without those at a vertex where only floor cells meet that the mover can cut
     * straight past: the corridor's shape bent the path there, not a wall.
     */
    private List<Funnel.Bend> straightened(List<Funnel.Bend> bends) {
        List<Funnel.Bend> out = new ArrayList<>(bends);
        for (int i = out.size() - 2; i >= 1; i--) {
            Funnel.Bend b = out.get(i);
            if (b.corner() == null && cuttable(out.get(i - 1), out.get(i + 1), i + 1 == out.size() - 1)) {
                out.remove(i);
            }
        }
        return out;
    }

    /** Whether the tangent from round {@code a} to round {@code b} is clear. */
    private boolean cuttable(Funnel.Bend a, Funnel.Bend b, boolean last) {
        Position[] t = Rounding.tangent(Rounding.Circle.of(a), Rounding.Circle.of(b));
        return probe.clear(t[0], t[1], last);
    }

}
