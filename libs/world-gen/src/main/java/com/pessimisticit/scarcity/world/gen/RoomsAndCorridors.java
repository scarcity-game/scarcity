package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Rooms joined by corridors, on any {@link CellGraph} (#234): a drawn number of rooms, each a
 * drawn box in metres whose cells (those with their centroid inside it, never a border cell) are
 * opened; a room that would touch an earlier one is drawn again, up to {@code attempts} times.
 * Each room is joined to the one before by an L of cells one cell wide, the side of its bend
 * drawn. Then {@link Connectivity} makes sure every open cell is reachable from the first room.
 */
public final class RoomsAndCorridors {

    /**
     * How many rooms ({@code [minRooms, maxRooms]}), how big their sides are
     * ({@code [minSide, maxSide)} metres) and how often a room that touches another is drawn again.
     */
    public record Settings(int minRooms, int maxRooms, double minSide, double maxSide, int attempts) {
        public static final Settings DEFAULT = new Settings(4, 8, 2.4, 5.6, 20);

        public Settings {
            if (minRooms < 2 || maxRooms < minRooms) {
                throw new IllegalArgumentException("rooms: need 2 <= minRooms <= maxRooms, got " + minRooms + ", " + maxRooms);
            }
            if (!(minSide > 0.0) || !(maxSide > minSide) || attempts < 1) {
                throw new IllegalArgumentException("sides: need 0 < minSide < maxSide and attempts >= 1");
            }
        }
    }

    /** A room: its cells, and its centre, the cell nearest its box's middle, all of whose neighbours are in it. */
    public record Room(List<Integer> cells, int centre) {
        public Room {
            cells = List.copyOf(cells);
        }
    }

    /** The open cells, and the rooms among them in the order they were drawn. */
    public record Plan(BitSet open, List<Room> rooms) {
        public Plan {
            open = (BitSet) open.clone();
            rooms = List.copyOf(rooms);
        }

        @Override
        public BitSet open() {
            return (BitSet) open.clone();
        }
    }

    private final Settings settings;

    public RoomsAndCorridors(Settings settings) {
        this.settings = settings;
    }

    public Plan generate(CellGraph graph, Recorder recorder) {
        BitSet open = new BitSet(graph.size());
        List<Room> rooms = new ArrayList<>();
        int count = settings.minRooms() + recorder.index("rooms", settings.maxRooms() - settings.minRooms() + 1);
        for (int i = 0; i < count; i++) {
            Room room = place(graph, recorder, open, i);
            if (room != null) {
                room.cells().forEach(open::set);
                rooms.add(room);
            }
        }
        if (rooms.size() < 2) {
            throw new IllegalStateException("only " + rooms.size() + " room(s) fit; the area is too small for "
                    + settings);
        }
        for (int i = 1; i < rooms.size(); i++) {
            corridor(graph, open, rooms.get(i - 1).centre(), rooms.get(i).centre(),
                    recorder.bernoulli("corridor[" + i + "].bendFirstAlongX", 0.5));
        }
        Connectivity.connect(graph, open, rooms.getFirst().centre());
        return new Plan(open, rooms);
    }

    /** Room {@code i}: drawn until it touches no open cell, or null after {@code attempts}. */
    private Room place(CellGraph graph, Recorder recorder, BitSet open, int i) {
        List<Position> bounds = graph.bounds();
        double margin = graph.minEdge();
        for (int t = 0; t < settings.attempts(); t++) {
            String name = "room[" + i + "].try[" + t + "].";
            double w = recorder.uniform(name + "width", settings.minSide(), settings.maxSide());
            double h = recorder.uniform(name + "height", settings.minSide(), settings.maxSide());
            double xMax = bounds.get(1).x() - margin - w;
            double yMax = bounds.get(1).y() - margin - h;
            double x0 = bounds.get(0).x() + margin;
            double y0 = bounds.get(0).y() + margin;
            if (xMax <= x0 || yMax <= y0) {
                continue;
            }
            double x = recorder.uniform(name + "x", x0, xMax);
            double y = recorder.uniform(name + "y", y0, yMax);
            Room room = room(graph, x, y, x + w, y + h);
            if (room != null && !touches(graph, room, open)) {
                return room;
            }
        }
        return null;
    }

    /** The cells of the box [x0, x1] x [y0, y1], or null when it has no cell all of whose neighbours are in it. */
    static Room room(CellGraph graph, double x0, double y0, double x1, double y1) {
        BitSet in = new BitSet(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            if (!graph.onBorder(c) && within(graph.centroid(c), x0, y0, x1, y1)) {
                in.set(c);
            }
        }
        int centre = centre(graph, in, new Position((x0 + x1) / 2, (y0 + y1) / 2));
        return centre < 0 ? null : new Room(in.stream().boxed().toList(), centre);
    }

    private static boolean within(Position p, double x0, double y0, double x1, double y1) {
        return p.x() >= x0 && p.x() <= x1 && p.y() >= y0 && p.y() <= y1;
    }

    /** The cell of {@code in} nearest {@code middle} all of whose neighbours are in it; -1 if none is. */
    private static int centre(CellGraph graph, BitSet in, Position middle) {
        int centre = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int c = in.nextSetBit(0); c >= 0; c = in.nextSetBit(c + 1)) {
            double d = graph.centroid(c).distanceTo(middle);
            if (d < best && graph.neighbours(c).stream().allMatch(in::get)) {
                best = d;
                centre = c;
            }
        }
        return centre;
    }

    /** Whether the room's cells are open already or next to an open cell. */
    private static boolean touches(CellGraph graph, Room room, BitSet open) {
        for (int c : room.cells()) {
            if (open.get(c) || graph.neighbours(c).stream().anyMatch(open::get)) {
                return true;
            }
        }
        return false;
    }

    /** Opens the cells within half a cell of the L from {@code a} to {@code b}. */
    private static void corridor(CellGraph graph, BitSet open, int a, int b, boolean alongXFirst) {
        Position from = graph.centroid(a);
        Position to = graph.centroid(b);
        Position bend = alongXFirst ? new Position(to.x(), from.y()) : new Position(from.x(), to.y());
        double half = graph.minEdge() / 2;
        for (int c = 0; c < graph.size(); c++) {
            Position p = graph.centroid(c);
            if (!graph.onBorder(c) && (near(p, from, bend, half) || near(p, bend, to, half))) {
                open.set(c);
            }
        }
    }

    /** Whether {@code p} is less than {@code half} from the segment {@code ab}. */
    private static boolean near(Position p, Position a, Position b, double half) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double len2 = ex * ex + ey * ey;
        double s = len2 == 0.0 ? 0.0 : Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / len2, 0.0, 1.0);
        return Math.hypot(p.x() - (a.x() + s * ex), p.y() - (a.y() + s * ey)) < half;
    }
}
