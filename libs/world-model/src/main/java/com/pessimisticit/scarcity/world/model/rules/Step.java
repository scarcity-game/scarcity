package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;

/**
 * {@code step}: moves in a straight line toward {@code params.to}, taking
 * distance / speed. At its end the line is cast against the area as it is
 * then, and the entity stops at the boundary or at the edge of the first
 * entity with a radius, keeping its own radius clear.
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
        return Timing.END;
    }

    @Override
    public double duration(Params params, EntityState self) {
        double speed = self.speed() == null ? Double.NaN : self.speed();
        return self.position().distanceTo(params.to()) / speed;
    }

    @Override
    public void validate(Params params, Actor self) {
        if (self.state().speed() == null) {
            throw new InvalidAction("speed: the entity has none, so it can't step");
        }
        AreaState area = self.area().area();
        if (!area.contains(params.to())) {
            throw new InvalidAction("to: " + params.to() + " is outside the area (" + area.width() + " x "
                    + area.height() + ")");
        }
        if (self.state().position().equals(params.to())) {
            throw new InvalidAction("to: a zero-length step (it is the entity's position)");
        }
    }

    @Override
    public Applied apply(Params params, Actor self) {
        Position from = self.state().position();
        double distance = from.distanceTo(params.to());
        double dx = (params.to().x() - from.x()) / distance;
        double dy = (params.to().y() - from.y()) / distance;
        double clearance = self.state().radius() == null ? 0.0 : self.state().radius();
        RayCast.Hit hit = RayCast.first(self.area(), from, dx, dy, clearance, self.id());
        boolean reached = hit.t() >= distance;
        Position stoppedAt = reached ? params.to() : RayCast.along(from, dx, dy, hit.t());
        return new Applied(self.state().withPosition(stoppedAt), new Result(params.to(), reached, stoppedAt));
    }
}
