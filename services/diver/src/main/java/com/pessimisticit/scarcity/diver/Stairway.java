package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.Comparator;
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

    public Stairway() {
        this.lastStairs = Optional.empty();
    }

    /** The nearest stairs down in the turn's area, if it has any. */
    public synchronized Optional<Stairs> down(TurnFrame turn, Entity self, World world) {
        if (!turn.equals(lastTurn)) {
            lastStairs = world.entities(turn.area()).stream()
                    .filter(e -> e instanceof Stairs s && s.leadsDown())
                    .map(Stairs.class::cast)
                    .min(Comparator.comparingDouble(s -> s.position().distanceTo(self.position())));
            lastTurn = turn;
        }
        return lastStairs;
    }

    /** Whether {@code stairs} are close enough for {@code traverse}. */
    public static boolean inReach(Entity self, Stairs stairs) {
        return self.position().distanceTo(stairs.position()) <= Traverse.REACH;
    }
}
