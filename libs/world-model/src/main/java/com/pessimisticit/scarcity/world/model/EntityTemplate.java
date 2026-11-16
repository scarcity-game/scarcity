package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The state of an entity that doesn't exist yet: a projectile's or a
 * debris's, carried in action and effect params. It is an
 * {@link EntityState} without a {@code position} (given where it is
 * created, {@link #at}) and without an {@code action} (a created entity
 * starts idle; {@code create}'s {@code start} gives it one). Same keys,
 * same checks, same JSON otherwise.
 */
public record EntityTemplate(
        @JsonProperty(required = true) EntityKind entityKind,
        @JsonProperty(required = true) String name,
        Double speed,
        Double radius,
        Double hp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> actions,
        Behaviour behaviour,
        Controller controller,
        Direction direction) {

    private static final Position ORIGIN = new Position(0.0, 0.0);

    public EntityTemplate {
        actions = actions == null ? List.of() : List.copyOf(actions);
        // the state's own checks, so a template is valid exactly when its state would be
        new EntityState(entityKind, name, ORIGIN, speed, radius, hp, actions, null, behaviour, controller, direction, null);
    }

    /** The state of this entity created at {@code position}, idle. */
    public EntityState at(Position position) {
        return new EntityState(entityKind, name, position, speed, radius, hp, actions, null, behaviour, controller, direction, null);
    }
}
