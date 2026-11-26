package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * String-pulling through a corridor's openings (the "simple stupid funnel"): the shortest path
 * from the start to the goal that crosses each opening. An opening's end that meets a solid cell
 * (or a cell the search refused) is a corner: the opening is shrunk by the clearance there, the
 * mover's radius plus {@link Pathfinder#MARGIN} (by at most half its length when both ends are
 * corners). The result is the bends: the start, each corner the path turns round (with the side
 * it is on), and the goal.
 */
final class Funnel {

    /** Where the path bends: {@code at} on the path, round {@code corner} (null for the start, the goal or a bend at a free vertex) on its {@code side}, +1 left, -1 right, keeping {@code clearance} from it. */
    record Bend(Position at, Position corner, int side, double clearance) {
    }

    /** One opening as crossed: its ends shrunk, and what they were. */
    private record Portal(Position left, Position right, Bend leftBend, Bend rightBend) {
    }

    private Funnel() {
    }

    static List<Bend> pull(CellSearch.Corridor corridor, Erosion erosion, Position from, Position to) {
        List<Portal> portals = new ArrayList<>();
        Bend start = new Bend(from, null, 0, 0.0);
        Bend goal = new Bend(to, null, 0, 0.0);
        portals.add(new Portal(from, from, start, start));
        int[] cells = corridor.cells();
        int[] links = corridor.links();
        for (int i = 0; i < links.length; i++) {
            portals.add(portal(erosion.opening(cells[i], links[i])));
        }
        portals.add(new Portal(to, to, goal, goal));
        return merged(funnel(portals));
    }

    /** The bends with each run round one corner (it meets several openings) made one, at its least clearance. */
    private static List<Bend> merged(List<Bend> bends) {
        List<Bend> out = new ArrayList<>();
        for (Bend b : bends) {
            Bend last = out.isEmpty() ? null : out.getLast();
            if (last != null && b.corner() != null && b.corner().equals(last.corner()) && b.side() == last.side()) {
                if (b.clearance() < last.clearance()) {
                    out.set(out.size() - 1, b);
                }
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static Portal portal(Erosion.Opening o) {
        double clearance = o.clearance();
        Position left = o.left();
        Position right = o.right();
        return new Portal(left, right, bend(left, o.leftCorner(), 1, clearance),
                bend(right, o.rightCorner(), -1, clearance));
    }

    private static Bend bend(Position at, Position corner, int side, double clearance) {
        return new Bend(at, corner, side, corner == null ? 0.0 : clearance);
    }

    /**
     * Mononen's funnel: the apex, and the funnel's two sides from it (the last portal end each
     * reached). When one side crosses the other, the other's end is a bend, the new apex, and the
     * scan restarts from the portal it came from.
     */
    private static final class State {
        private final List<Portal> portals;
        private final List<Bend> bends;
        private Position apex;
        private Position left;
        private Position right;
        private int leftIndex;
        private int rightIndex;

        State(List<Portal> portals) {
            this.portals = portals;
            this.bends = new ArrayList<>();
            Portal first = portals.getFirst();
            bends.add(first.leftBend());
            apex = first.left();
            left = apex;
            right = apex;
        }

        /** Narrows the right side to portal {@code i}; the portal to rescan from if the left end became the apex, else -1. */
        int right(int i) {
            Position p = portals.get(i).right();
            if (Geometry.cross(apex, right, p) < 0.0) {
                return -1;
            }
            if (apex.equals(right) || Geometry.cross(apex, left, p) < 0.0) {
                right = p;
                rightIndex = i;
                return -1;
            }
            bends.add(portals.get(leftIndex).leftBend());
            apex = left;
            right = left;
            rightIndex = leftIndex;
            return leftIndex;
        }

        /** As {@link #right}, for the left side. */
        int left(int i) {
            Position p = portals.get(i).left();
            if (Geometry.cross(apex, left, p) > 0.0) {
                return -1;
            }
            if (apex.equals(left) || Geometry.cross(apex, right, p) > 0.0) {
                left = p;
                leftIndex = i;
                return -1;
            }
            bends.add(portals.get(rightIndex).rightBend());
            apex = right;
            left = right;
            leftIndex = rightIndex;
            return rightIndex;
        }
    }

    private static List<Bend> funnel(List<Portal> portals) {
        State state = new State(portals);
        for (int i = 1; i < portals.size(); i++) {
            int restart = state.right(i);
            if (restart < 0) {
                restart = state.left(i);
            }
            if (restart >= 0) {
                i = restart;
            }
        }
        List<Bend> bends = state.bends;
        Bend goal = portals.getLast().leftBend();
        if (!bends.getLast().at().equals(goal.at())) {
            bends.add(goal);
        }
        return bends;
    }
}
