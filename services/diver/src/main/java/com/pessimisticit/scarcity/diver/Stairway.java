package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.pathfinding.Pathfinder;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The stairs down nearest the diver in the turn's area, and the way there, read and planned once
 * per turn (every choice asks whether it is open, then the drawn one plans): turns are played one
 * at a time on one thread.
 */
@Component
public class Stairway {

    private TurnFrame lastTurn;
    private Optional<Stairs> lastStairs;
    private List<Entity> lastEntities;
    private Optional<List<Position>> lastRoute;

    public Stairway() {
        this.lastStairs = Optional.empty();
        this.lastEntities = List.of();
    }

    /** The nearest stairs down in the turn's area, if it has any. */
    public synchronized Optional<Stairs> down(TurnFrame turn, Entity self, World world) {
        if (!turn.equals(lastTurn)) {
            lastEntities = world.entities(turn.area());
            lastStairs = lastEntities.stream()
                    .filter(e -> e instanceof Stairs s && s.leadsDown())
                    .map(Stairs.class::cast)
                    .min(Comparator.comparingDouble(s -> s.position().distanceTo(self.position())));
            lastRoute = null;
            lastTurn = turn;
        }
        return lastStairs;
    }

    /**
     * The waypoints from the diver to the nearest stairs down, round every wall and other obstacle
     * as they are this turn ({@link Pathfinder}): empty when there are no stairs down or no way to
     * them. Planned once per turn, from the same entities as {@link #down}, so the first leg is
     * one {@code step} world allows.
     */
    public synchronized Optional<List<Position>> route(TurnFrame turn, Entity self, World world) {
        Optional<Stairs> stairs = down(turn, self, world);
        if (lastRoute == null) {
            lastRoute = stairs.flatMap(s -> plan(turn, self, s.position()));
        }
        return lastRoute;
    }

    private Optional<List<Position>> plan(TurnFrame turn, Entity self, Position to) {
        if (self.speed() == null) {
            return Optional.empty();
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        lastEntities.forEach(e -> entities.put(e.id(), e));
        entities.put(self.id(), self);
        AreaView view = AreaView.of(turn.area(), new Area("seen", 0.0), entities);
        return Pathfinder.path(view, self.id(), to);
    }

    /** Whether {@code stairs} are close enough for {@code traverse}. */
    public static boolean inReach(Entity self, Stairs stairs) {
        return self.position().distanceTo(stairs.position()) <= Traverse.REACH;
    }
}
