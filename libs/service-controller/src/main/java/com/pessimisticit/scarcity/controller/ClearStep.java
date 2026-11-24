package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.controller.Choice.Situation;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Contacts;
import com.pessimisticit.scarcity.world.model.rules.Motion;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Finding a {@code step} that world will let run to its end, before the choice is drawn: what every
 * movement choice shares (#316). A step is clear when, as the area is this turn, the mover meets
 * nothing before it arrives ({@link Contacts#first}, with everything else standing still, as world
 * checks it). A step that would be interrupted at once is one world refuses; one interrupted later
 * is accepted but goes less far than planned, so neither counts.
 */
public final class ClearStep {

    /** How many headings {@link #anyHeading} draws before it gives up. */
    public static final int TRIES = 10;

    private static final double TURN = 2 * Math.PI;

    private ClearStep() {
    }

    /** Whether a step of {@code self} from where it is to {@code to} meets nothing before it ends. */
    public static boolean clear(AreaView view, Entity self, Position to) {
        Position from = self.position();
        double distance = from.distanceTo(to);
        if (self.speed() == null || distance == 0.0) {
            return false;
        }
        double speed = self.speed();
        Motion motion = new Motion(speed * (to.x() - from.x()) / distance, speed * (to.y() - from.y()) / distance);
        double duration = distance / speed;
        return Contacts.first(view, Map.of(), self.id(), motion).time() >= duration - Contacts.TOUCH;
    }

    /**
     * A clear step of {@code distance} along a heading drawn uniformly from {@code [0, 2π)}: up to
     * {@link #TRIES} draws, each recorded as {@code heading.1}, {@code heading.2}, … in order, the
     * last of them the one taken. Not feasible when none of them is clear.
     */
    public static Plan anyHeading(Situation situation, RandomGenerator rng, double distance) {
        Entity self = situation.entity();
        Map<String, Draw> draws = new LinkedHashMap<>();
        for (int i = 1; i <= TRIES; i++) {
            Draw.Uniform heading = Draws.uniform(rng, 0.0, TURN);
            draws.put("heading." + i, heading);
            Position to = along(self.position(), heading.value(), distance);
            if (clear(situation.areaView(), self, to)) {
                return Plan.of(Step.INSTANCE.spec(new Step.Params(to)), draws);
            }
        }
        return Plan.none("no clear step in " + TRIES + " headings", draws);
    }

    /** The point {@code distance} from {@code from} at {@code angle}. */
    public static Position along(Position from, double angle, double distance) {
        return new Position(from.x() + distance * Math.cos(angle), from.y() + distance * Math.sin(angle));
    }
}
