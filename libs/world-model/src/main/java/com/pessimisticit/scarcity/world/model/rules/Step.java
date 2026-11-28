package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.Map;
import java.util.Optional;

/**
 * {@code step}: moves in a straight line toward {@code params.to}, taking
 * distance / speed. It is a channel: before every event while it runs, it
 * moves on by speed x the time since. It checks nothing itself: the engine
 * forecasts its {@link #motion contact} with other entities, walls among
 * them, and interrupts it there (docs/actions.md, "Interruption"). It is
 * invalid to start one that touches what it moves toward, or one beyond the
 * entity's {@link #reach} (give or take {@link Contacts#TOUCH}, so a step aimed at exactly the
 * reach isn't refused for a rounding error): a fast entity can go further in one step.
 */
public final class Step implements Action<Step.Params> {

    public static final Step INSTANCE = new Step();

    /** The longest one step may take, in seconds of game time: its reach is speed x this. */
    public static final double MAX_DURATION = Entity.MAX_STEP_DURATION;

    private Step() {
    }

    public record Params(@JsonProperty(required = true) Position to) {
        public Params {
            Check.required(to, "to");
        }
    }

    /** The {@code action-end} result: where it was going, whether it got there, and where it stopped. */
    public record Result(Position to, boolean reached, Position stoppedAt) {
    }

    /** The {@code action-progress} result: where this part started and ended, and how far it went. */
    public record Moved(Position from, Position at, double distance) {
    }

    @Override
    public String name() {
        return "step";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public Timing timing() {
        return Timing.CHANNEL;
    }

    @Override
    public double duration(Params params, Entity self) {
        double speed = self.speed() == null ? Double.NaN : self.speed();
        return self.position().distanceTo(params.to()) / speed;
    }

    /** How far {@code entity} may go in one step: its speed x {@link #MAX_DURATION}, 0 with no speed. */
    public static double reach(Entity entity) {
        return entity.stepReach() == null ? 0.0 : entity.stepReach();
    }

    @Override
    public void validate(Params params, Actor self) {
        if (self.entity().speed() == null) {
            throw new InvalidAction("speed: the entity has none, so it can't step");
        }
        if (self.entity().polygon() != null) {
            throw new InvalidAction("polygon: only a circle can step, for now (docs/resources.md, \"Shapes\")");
        }
        if (self.entity().position().equals(params.to())) {
            throw new InvalidAction("to: a zero-length step (it is the entity's position)");
        }
        double distance = self.entity().position().distanceTo(params.to());
        if (distance > reach(self.entity()) + Contacts.TOUCH) {
            throw new InvalidAction("to: " + distance + " m away, beyond the entity's reach of " + reach(self.entity())
                    + " m (speed " + self.entity().speed() + " m/s x " + MAX_DURATION + " s)");
        }
        Contacts.Contact contact = Contacts.first(self.areaView(), Map.of(), self.id(), heading(self.entity(), params));
        if (contact.time() <= 0.0) {
            throw new InvalidAction("to: the step starts touching "
                    + contact.other() + ", which it moves toward");
        }
    }

    @Override
    public Optional<Motion> motion(Params params, Actor self) {
        return Optional.of(heading(self.entity(), params));
    }

    private static Motion heading(Entity entity, Params params) {
        Position from = entity.position();
        double remaining = from.distanceTo(params.to());
        if (remaining == 0.0) {
            return Motion.STILL;
        }
        double speed = entity.speed();
        return new Motion(speed * (params.to().x() - from.x()) / remaining, speed * (params.to().y() - from.y()) / remaining);
    }

    @Override
    public Progress progress(Params params, Actor self, double dt) {
        Entity entity = self.entity();
        Position from = entity.position();
        double remaining = from.distanceTo(params.to());
        double gone = Math.min(remaining, entity.speed() * dt);
        Position at = gone == remaining ? params.to() : RayCast.along(from, (params.to().x() - from.x()) / remaining,
                (params.to().y() - from.y()) / remaining, gone);
        return new Progress(entity.withPosition(at), new Moved(from, at, gone), false);
    }

    /** Interrupted: it stays where it has got to, short of {@code to}. */
    @Override
    public Applied interrupt(Params params, Actor self) {
        Position at = self.entity().position();
        return new Applied(self.entity(), new Result(params.to(), at.equals(params.to()), at));
    }

    /** The rest of the way (nothing, when it was moved on to its end already) and the result. */
    @Override
    public Applied apply(Params params, Actor self) {
        Entity moved = progress(params, self, Double.POSITIVE_INFINITY).entity();
        Position at = moved.position();
        return new Applied(moved, new Result(params.to(), at.equals(params.to()), at));
    }
}
