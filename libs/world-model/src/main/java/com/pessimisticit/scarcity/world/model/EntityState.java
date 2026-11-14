package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of an {@code entity} (docs/resources.md, docs/actions.md).
 *
 * <p>Optional keys ({@code speed}, {@code radius}, {@code hp}, {@code action},
 * {@code behaviour}, {@code controller}) are null here and absent in JSON.
 * {@code actions} is never null; an empty list is absent in JSON. An entity
 * has at most one of {@code behaviour} and {@code controller}. Keys the model
 * doesn't know are kept in {@code extra}.
 */
public record EntityState(
        @JsonProperty(required = true) EntityKind kind,
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) Position position,
        Double speed,
        Double radius,
        Double hp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> actions,
        ActionInProgress action,
        Behaviour behaviour,
        Controller controller,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> extra) {

    private static final Set<String> KNOWN = Set.of("kind", "name", "position", "speed", "radius", "hp",
            "actions", "action", "behaviour", "controller");

    public EntityState {
        Check.required(kind, "kind");
        Check.text(name, "name");
        Check.required(position, "position");
        speed = Check.optionalPositive(speed, "speed");
        radius = Check.optionalPositive(radius, "radius");
        hp = Check.optionalNonNegative(hp, "hp");
        actions = actions == null ? List.of() : List.copyOf(actions);
        actions.forEach(a -> Check.text(a, "actions"));
        if (behaviour != null && controller != null) {
            throw new IllegalArgumentException("controller: an entity with a behaviour has no controller");
        }
        extra = Extra.copy(extra, KNOWN);
    }

    public EntityState withPosition(Position p) {
        return new EntityState(kind, name, p, speed, radius, hp, actions, action, behaviour, controller, extra);
    }

    public EntityState withHp(double newHp) {
        return new EntityState(kind, name, position, speed, radius, newHp, actions, action, behaviour, controller,
                extra);
    }

    /** The same state doing {@code a}; null makes it idle. */
    public EntityState withAction(ActionInProgress a) {
        return new EntityState(kind, name, position, speed, radius, hp, actions, a, behaviour, controller, extra);
    }

    public boolean idle() {
        return action == null;
    }
}
