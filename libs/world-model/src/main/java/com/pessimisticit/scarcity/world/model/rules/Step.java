package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;

/**
 * {@code step}: moves in a straight line toward {@code params.to}, taking
 * distance / speed. It is a channel: before every event while it runs, it
 * moves on by speed x the time since, cast against the area as it is then.
 * It stops at the boundary or at the edge of the first entity with a radius,
 * keeping its own radius clear, and then ends.
 */
public final class Step implements Action<Step.Params> {

    public static final Step INSTANCE = new Step();

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

    /** The {@code action-progress} result: where this part started and ended, how far it went, and whether it was blocked. */
    public record Moved(Position from, Position at, double distance, boolean blocked) {
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

    @Override
    public void validate(Params params, Actor self) {
        if (self.entity().speed() == null) {
            throw new InvalidAction("speed: the entity has none, so it can't step");
        }
        Area area = self.areaView().area();
        if (!area.contains(params.to())) {
            throw new InvalidAction("to: " + params.to() + " is outside the area (" + area.width() + " x "
                    + area.height() + ")");
        }
        if (self.entity().position().equals(params.to())) {
            throw new InvalidAction("to: a zero-length step (it is the entity's position)");
        }
    }

    @Override
    public Progress progress(Params params, Actor self, double dt) {
        Entity entity = self.entity();
        Position from = entity.position();
        double remaining = from.distanceTo(params.to());
        if (remaining == 0.0) {
            return new Progress(entity, new Moved(from, from, 0.0, false), false);
        }
        double wanted = Math.min(remaining, entity.speed() * dt);
        double dx = (params.to().x() - from.x()) / remaining;
        double dy = (params.to().y() - from.y()) / remaining;
        double clearance = entity.radius() == null ? 0.0 : entity.radius();
        RayCast.Hit hit = RayCast.first(self.areaView(), from, dx, dy, clearance, self.id());
        boolean blocked = hit.t() < wanted;
        double gone = blocked ? hit.t() : wanted;
        Position at = gone == remaining ? params.to() : RayCast.along(from, dx, dy, gone);
        return new Progress(entity.withPosition(at), new Moved(from, at, gone, blocked), blocked);
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
