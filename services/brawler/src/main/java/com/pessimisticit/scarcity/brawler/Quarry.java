package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.combat.Strike;
import com.pessimisticit.scarcity.controller.Choice.Situation;
import com.pessimisticit.scarcity.pathfinding.Pathfinder;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Factions;
import com.pessimisticit.scarcity.world.model.Mob;
import com.pessimisticit.scarcity.world.model.Npc;
import com.pessimisticit.scarcity.world.model.Player;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Whom the brawler goes for and the way to them, worked out once per turn (every choice plans
 * before the draw) from the turn's {@link Situation}: the nearest creature in range it is hostile
 * to under the game's faction relations ({@link Factions#hostile}: one of its {@code foes}, or of a
 * side its faction attacks on sight), ties broken by id. A creature is a mob, an npc or a player that
 * is damageable and has a radius, so something a {@code strike} can hurt; never a fixed entity.
 */
@Component
public class Quarry {

    private Situation situation;
    private Optional<Entity> lastTarget;
    private Optional<List<Position>> lastRoute;

    public Quarry() {
        this.lastTarget = Optional.empty();
    }

    /** The creature to fight this turn, if there is one in range. */
    public synchronized Optional<Entity> target(Situation turn) {
        if (turn != situation) {
            Entity self = turn.entity();
            lastTarget = turn.areaView().entities().values().stream()
                    .filter(e -> !e.id().equals(self.id()) && creature(e) && Factions.hostile(turn.factions(), self, e))
                    .min(Comparator.comparingDouble((Entity e) -> e.position().distanceTo(self.position()))
                            .thenComparing(Entity::id));
            lastRoute = null;
            situation = turn;
        }
        return lastTarget;
    }

    /**
     * The waypoints to where the brawler stands to strike the target ({@link #standAt}), round every
     * wall and other obstacle as they are this turn ({@link Pathfinder}); empty with no target, no
     * speed or no way there.
     */
    public synchronized Optional<List<Position>> route(Situation turn) {
        Optional<Entity> target = target(turn);
        if (lastRoute == null) {
            lastRoute = target.flatMap(t -> plan(turn, t)).filter(r -> !r.isEmpty());
        }
        return lastRoute;
    }

    /** Whether {@code target} is within {@code self}'s reach, edge to edge, as {@code strike} measures it. */
    public static boolean inReach(Entity self, Entity target) {
        return Strike.gap(self, target) <= Strike.reach(self);
    }

    /**
     * The point on the line from {@code target} to {@code self} where the gap between them is half
     * {@code self}'s reach: in reach, with room for the target to move a little.
     */
    static Position standAt(Entity self, Entity target) {
        Position from = target.position();
        Position to = self.position();
        double distance = from.distanceTo(to);
        double stand = self.radius() + target.radius() + Strike.reach(self) / 2;
        if (distance == 0.0) {
            return new Position(from.x() + stand, from.y());
        }
        return new Position(from.x() + (to.x() - from.x()) * stand / distance,
                from.y() + (to.y() - from.y()) * stand / distance);
    }

    private static boolean creature(Entity e) {
        boolean living = e instanceof Mob || e instanceof Npc || e instanceof Player;
        return living && e.damageable() && e.radius() != null;
    }

    private static Optional<List<Position>> plan(Situation turn, Entity target) {
        Entity self = turn.entity();
        if (self.speed() == null || self.radius() == null) {
            return Optional.empty();
        }
        return Pathfinder.path(turn.areaView(), self.id(), standAt(self, target));
    }
}
