package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * An {@code entity}: something that occupies a position in an area
 * (docs/resources.md, actions in docs/actions.md). The class is picked by
 * {@code entityKind} and {@code prototype} ({@link Prototypes}); a prototype
 * is data (its defaults are a JSON file), and gets a class only when it has
 * keys of its own ({@link Stairs}).
 *
 * <p>Every entity but a placeholder is a physical object, in SI units
 * (docs/resources.md): {@code mass} in grams and {@code radius} in metres,
 * which a stored one has ({@link #requireStored()}); {@code speed} in m/s
 * for one that moves. {@code overlap}: others can stand on or in it.
 * {@code damageable}: damage affects its {@code hp}, which it has exactly
 * when it is damageable. Both flags are always written, and absent in JSON
 * reads as false.
 *
 * <p>Optional keys ({@code mass}, {@code speed}, {@code radius},
 * {@code hp}, {@code actionInProgress}, {@code behaviour},
 * {@code controller}) are null here and absent in JSON. {@code actions} is
 * never null; an empty list is absent in JSON. An entity has at most one of
 * {@code behaviour} and {@code controller}. {@code position} is null on a
 * prototype and on the template of an entity not created yet
 * ({@link EntityTemplate}); the store needs it ({@link #requireStored()}).
 */
public abstract class Entity extends Resource {

    @JsonProperty(required = true)
    private String prototype;
    @JsonProperty(required = true)
    private String name;
    private Position position;
    private Double mass;
    private Double speed;
    private Double radius;
    private Double hp;
    @JsonProperty
    private Boolean overlap;
    @JsonProperty
    private Boolean damageable;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> actions;
    private ActionInProgress actionInProgress;
    private Behaviour behaviour;
    private Controller controller;

    /** For Jackson. */
    protected Entity() {
    }

    protected Entity(String prototype, String name) {
        this.prototype = prototype;
        this.name = name;
    }

    @Override
    public final ResourceKind resourceKind() {
        return ResourceKind.ENTITY;
    }

    @JsonProperty("entityKind")
    public abstract EntityKind entityKind();

    public String prototype() {
        return prototype;
    }

    public String name() {
        return name;
    }

    /** Null on a prototype and on a template; never null on a stored entity. */
    public Position position() {
        return position;
    }

    /** Grams; null on a placeholder. */
    public Double mass() {
        return mass;
    }

    /** Metres per second; null on something that doesn't move. */
    public Double speed() {
        return speed;
    }

    /** Metres; null on a placeholder. */
    public Double radius() {
        return radius;
    }

    public Double hp() {
        return hp;
    }

    /** Whether others can stand on or in it (stairs, rubble): it is no obstacle. */
    public boolean overlap() {
        return overlap;
    }

    /** Whether damage affects it; it has {@code hp} exactly when it does. */
    public boolean damageable() {
        return damageable;
    }

    /** Whether it is a physical object, with a mass and a radius: every entity but a placeholder and a wall. */
    public boolean physical() {
        return true;
    }

    public List<String> actions() {
        return actions;
    }

    public ActionInProgress actionInProgress() {
        return actionInProgress;
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    public Controller controller() {
        return controller;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(prototype, "prototype");
        Check.text(name, "name");
        mass = Check.optionalPositive(mass, "mass");
        speed = Check.optionalPositive(speed, "speed");
        radius = Check.optionalPositive(radius, "radius");
        hp = Check.optionalNonNegative(hp, "hp");
        overlap = overlap != null && overlap;
        damageable = damageable != null && damageable;
        if (hp != null && !damageable) {
            throw new IllegalArgumentException("hp: only a damageable entity has hp");
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
        actions.forEach(a -> Check.text(a, "actions"));
        if (behaviour != null && controller != null) {
            throw new IllegalArgumentException("controller: an entity with a behaviour has no controller");
        }
    }

    @Override
    public Entity requireStored() {
        super.requireStored();
        Check.required(position, "position");
        if (physical()) {
            Check.required(mass, "mass");
            Check.required(radius, "radius");
        }
        if (damageable) {
            Check.required(hp, "hp");
        }
        return this;
    }

    public final Entity withPosition(Position newPosition) {
        Entity copy = copy();
        copy.position = newPosition;
        copy.validate();
        return copy;
    }

    /** For a subclass whose position is computed (a {@link Wall}'s centroid). */
    protected final void placeAt(Position newPosition) {
        position = newPosition;
    }

    public final Entity withName(String newName) {
        Entity copy = copy();
        copy.name = newName;
        copy.validate();
        return copy;
    }

    public final Entity withMass(Double newMass) {
        Entity copy = copy();
        copy.mass = newMass;
        copy.validate();
        return copy;
    }

    public final Entity withSpeed(Double newSpeed) {
        Entity copy = copy();
        copy.speed = newSpeed;
        copy.validate();
        return copy;
    }

    public final Entity withRadius(Double newRadius) {
        Entity copy = copy();
        copy.radius = newRadius;
        copy.validate();
        return copy;
    }

    public final Entity withHp(Double newHp) {
        Entity copy = copy();
        copy.hp = newHp;
        copy.validate();
        return copy;
    }

    public final Entity withOverlap(boolean newOverlap) {
        Entity copy = copy();
        copy.overlap = newOverlap;
        return copy;
    }

    /** Not damageable drops the {@code hp}; damageable needs one before it is stored. */
    public final Entity withDamageable(boolean newDamageable) {
        Entity copy = copy();
        copy.damageable = newDamageable;
        copy.hp = newDamageable ? copy.hp : null;
        return copy;
    }

    public final Entity withActions(List<String> newActions) {
        Entity copy = copy();
        copy.actions = newActions;
        copy.validate();
        return copy;
    }

    /** The same entity doing {@code a}; null makes it idle. */
    public final Entity withAction(ActionInProgress a) {
        Entity copy = copy();
        copy.actionInProgress = a;
        return copy;
    }

    public final Entity withBehaviour(Behaviour newBehaviour) {
        Entity copy = copy();
        copy.behaviour = newBehaviour;
        copy.validate();
        return copy;
    }

    public final Entity withController(Controller newController) {
        Entity copy = copy();
        copy.controller = newController;
        copy.validate();
        return copy;
    }

    public final boolean idle() {
        return actionInProgress == null;
    }

    /**
     * Whether the area runs while this entity is in it: a player or an npc, or a placeholder
     * (one arriving). An area with none gets no turns and its time stands still
     * (docs/actions.md, "Turns").
     */
    public boolean occupies() {
        return false;
    }
}
