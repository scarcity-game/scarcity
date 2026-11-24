package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice.Situation;
import com.pessimisticit.scarcity.pathfinding.Pathfinder;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The stairs down nearest the diver in the turn's area, and the way there, from the entities the
 * turn's {@link Situation} carries, found and planned once per turn (every choice plans before the
 * draw): turns are played one at a time on one thread.
 */
@Component
public class Stairway {

    private Situation situation;
    private Optional<Stairs> lastStairs;
    private Optional<List<Position>> lastRoute;

    public Stairway() {
        this.lastStairs = Optional.empty();
    }

    /** The nearest stairs down in the turn's area, if it has any. */
    public synchronized Optional<Stairs> down(Situation turn) {
        if (turn != situation) {
            Entity self = turn.entity();
            lastStairs = turn.areaView().entities().values().stream()
                    .filter(e -> e instanceof Stairs s && s.leadsDown())
                    .map(Stairs.class::cast)
                    .min(Comparator.comparingDouble(s -> s.position().distanceTo(self.position())));
            lastRoute = null;
            situation = turn;
        }
        return lastStairs;
    }

    /**
     * The waypoints from the diver to the nearest stairs down, round every wall and other obstacle
     * as they are this turn ({@link Pathfinder}): empty when there are no stairs down or no way to
     * them. Planned once per turn, from the same entities as {@link #down}, so the first leg is
     * one {@code step} world allows.
     */
    public synchronized Optional<List<Position>> route(Situation turn) {
        Optional<Stairs> stairs = down(turn);
        if (lastRoute == null) {
            lastRoute = stairs.flatMap(s -> plan(turn, s.position()));
        }
        return lastRoute;
    }

    private static Optional<List<Position>> plan(Situation turn, Position to) {
        if (turn.entity().speed() == null) {
            return Optional.empty();
        }
        return Pathfinder.path(turn.areaView(), turn.entity().id(), to);
    }

    /** Whether {@code stairs} are close enough for {@code traverse}. */
    public static boolean inReach(Entity self, Stairs stairs) {
        return self.position().distanceTo(stairs.position()) <= Traverse.REACH;
    }
}
