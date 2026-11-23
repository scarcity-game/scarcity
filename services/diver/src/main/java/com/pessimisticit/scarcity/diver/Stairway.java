package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The stairs down nearest the diver in the turn's area, read once per turn (every choice asks
 * whether it is open, then the drawn one plans): turns are played one at a time on one thread.
 */
@Component
public class Stairway {

    private TurnFrame lastTurn;
    private Optional<Stairs> lastStairs;
    private List<Entity> lastEntities;

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
            lastTurn = turn;
        }
        return lastStairs;
    }

    /**
     * Whether the straight step toward the nearest stairs down would start touching an entity it
     * moves toward: the check {@code Step.validate} makes (world rejects such a step), made here
     * on the same entities with the same {@link Contacts}. Only the heading matters, not the
     * length, so the stairs' position stands for the step's target. The area's boundary is left
     * out (an unbounded area): the stairs are inside it, and so is the diver.
     */
    public synchronized boolean blocked(TurnFrame turn, Entity self, World world) {
        Optional<Stairs> stairs = down(turn, self, world);
        double distance = stairs.map(s -> s.position().distanceTo(self.position())).orElse(0.0);
        if (distance == 0.0 || self.speed() == null) {
            return false;
        }
        Position to = stairs.get().position();
        Position from = self.position();
        Motion heading = new Motion(self.speed() * (to.x() - from.x()) / distance,
                self.speed() * (to.y() - from.y()) / distance);
        Map<String, Entity> entities = new LinkedHashMap<>();
        lastEntities.forEach(e -> entities.put(e.id(), e));
        entities.put(self.id(), self);
        AreaView view = AreaView.of(turn.area(), new Area("seen", 0.0), entities);
        return Contacts.first(view, Map.of(), self.id(), heading).time() <= 0.0;
    }

    /** Whether {@code stairs} are close enough for {@code traverse}. */
    public static boolean inReach(Entity self, Stairs stairs) {
        return self.position().distanceTo(stairs.position()) <= Traverse.REACH;
    }
}
