package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
import java.util.stream.Stream;

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
 * reads as false. A creature has {@link Attributes} ({@code level},
 * {@code strength}, {@code dexterity}, {@code intelligence}: all four or none)
 * and a {@code faction}, both from its prototype.
 *
 * <p>Optional keys ({@code mass}, {@code speed}, {@code vision}, {@code radius},
 * {@code hp}, {@code actionInProgress}, {@code behaviour},
 * {@code controller}) are null here and absent in JSON. {@code actions} is
 * never null; an empty list is absent in JSON. An entity has at most one of
 * {@code behaviour} and {@code controller}. {@code position} is null on a
 * prototype and on the template of an entity not created yet
 * ({@link EntityTemplate}); the store needs it ({@link #requireStored()}).
 */
public abstract class Entity extends Resource {

    /** The longest one {@code step} may take, in seconds of game time: its {@link #stepReach} is speed x this. */
    public static final double MAX_STEP_DURATION = 5.0;

    @JsonProperty(required = true)
    private String prototype;
    @JsonProperty(required = true)
    private String name;
    private Position position;
    private Double mass;
    private Double speed;
    private Double vision;
    private Double radius;
    private List<Position> polygon;
    private String cell;
    /** A wall's shape, from its cell ({@link #resolve}); never written, so the cell stays the one source. */
    @JsonIgnore
    private List<Position> cellPolygon;
    private String material;
    private Double hp;
    private Double level;
    private Double strength;
    private Double dexterity;
    private Double intelligence;
    private String faction;
    private Double reach;
    private Double strikeTime;
    private String spawner;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> foes;
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

    /**
     * How far one {@code step} may take it, in metres: {@code speed} x {@link #MAX_STEP_DURATION}; null with no
     * speed. Derived, so it is written (for the client to draw) and dropped on read, never a second source.
     */
    @JsonProperty("stepReach")
    public Double stepReach() {
        return speed == null ? null : speed * MAX_STEP_DURATION;
    }

    /** Read and dropped: {@link #stepReach} is derived from {@code speed}, so what was written is never a source. */
    @JsonSetter("stepReach")
    @SuppressWarnings("unused")
    private void writtenStepReach(double ignored) {
        // Derived again from the speed.
    }

    /** How far it sees, in metres, from its prototype; null on something that doesn't see. Nothing reads it yet (#238). */
    public Double vision() {
        return vision;
    }

    /** Metres; null on a placeholder. */
    public Double radius() {
        return radius;
    }

    /** The convex outline in area coordinates, counter-clockwise, its centroid the {@code position}; null with a radius. */
    public List<Position> polygon() {
        return polygon;
    }

    /** The id of the wall cell this entity is (docs/resources.md, "Walls"); null on any other entity. */
    public String cell() {
        return cell;
    }

    /** Whether its shape comes from its cell and has been read from it ({@link #resolve}). */
    public boolean resolved() {
        return cell == null || cellPolygon != null;
    }

    /**
     * The same wall with its shape read from {@code wallCell}, the {@code wall} cell it names; its position
     * becomes the cell's centroid. Nothing of the cell is written with the entity.
     */
    public final Entity resolve(Cell wallCell) {
        if (cell == null || !cell.equals(wallCell.id())) {
            throw new IllegalArgumentException("cell: " + id() + " names " + cell + ", not " + wallCell.id());
        }
        if (wallCell.kind() != Cell.Kind.WALL) {
            throw new IllegalArgumentException("cell: " + wallCell.id() + " is " + wallCell.kind() + ", not a wall");
        }
        Entity copy = copy();
        copy.cellPolygon = wallCell.polygon();
        copy.position = wallCell.position();
        return copy;
    }

    /** What it is made of ({@code granite}, one of {@link Materials}); a client draws a polygon with that material's pattern. Null when absent. */
    public String material() {
        return material;
    }

    /**
     * Its shape: a circle of its {@code radius} at its {@code position}, its polygon, or its
     * cell's polygon; null on an entity with none (a placeholder, a prototype).
     *
     * @throws IllegalStateException if it has a radius but no position yet, or names a cell it
     *     hasn't been {@link #resolve resolved} against: a wall nobody can see is a bug (#345)
     */
    public Shape shape() {
        return EntityShapes.of(id(), position, radius, polygon, cell, cellPolygon);
    }

    public Double hp() {
        return hp;
    }

    /** Its level, strength, dexterity and intelligence; null on an entity with none (a wall, a pebble). */
    public Attributes attributes() {
        return level == null ? null : new Attributes(level, strength, dexterity, intelligence);
    }

    /** The side it is on ({@code vermin}, {@code goblin}); null on an entity on none. */
    public String faction() {
        return faction;
    }

    /** How far it strikes, edge to edge, in metres (libs/combat's {@code Strike}); null means its radius. */
    public Double reach() {
        return reach;
    }

    /** How long its strike takes, in seconds (libs/combat); null means the default, 1 s. */
    public Double strikeTime() {
        return strikeTime;
    }

    /** The id of the den that made it ({@code spawn}); null on an entity no den made. */
    public String spawner() {
        return spawner;
    }

    /** The ids of those that attacked it ({@code melee-attack}), oldest first; never null (resources.md, "Factions"). */
    public List<String> foes() {
        return foes;
    }

    /** Whether others can stand on or in it (stairs, rubble): it is no obstacle. */
    public boolean overlap() {
        return overlap;
    }

    /** Whether damage affects it; it has {@code hp} exactly when it does. */
    public boolean damageable() {
        return damageable;
    }

    /** Whether it is a physical object, with a mass and a radius: every entity but a placeholder. */
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
        vision = Check.optionalPositive(vision, "vision");
        radius = Check.optionalPositive(radius, "radius");
        material = material == null ? null : Check.text(material, "material");
        validateShape();
        hp = Check.optionalNonNegative(hp, "hp");
        Attributes.checkAllOrNone(level, strength, dexterity, intelligence);
        validateLabels();
        reach = Check.optionalPositive(reach, "reach");
        strikeTime = Check.optionalPositive(strikeTime, "strikeTime");
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

    private void validateLabels() {
        faction = faction == null ? null : Check.text(faction, "faction");
        spawner = spawner == null ? null : Ids.require(ResourceKind.ENTITY.prefix(), spawner);
        foes = foes == null ? List.of() : List.copyOf(foes);
        foes.forEach(f -> Ids.require(ResourceKind.ENTITY.prefix(), f));
    }


    private void validateShape() {
        polygon = EntityShapes.checkKeys(cell, radius, polygon);
        if (polygon != null) {
            position = ConvexPolygon.centroid(polygon);
        }
    }

    @Override
    public Entity requireStored() {
        super.requireStored();
        Check.required(position, "position");
        if (physical()) {
            Check.required(mass, "mass");
            if (radius == null && polygon == null && cell == null) {
                throw new IllegalArgumentException("radius: required, or a polygon or a cell instead");
            }
        }
        if (damageable) {
            Check.required(hp, "hp");
        }
        return this;
    }

    public final Entity withPosition(Position newPosition) {
        Entity copy = copy();
        if (polygon != null) {
            copy.position = ConvexPolygon.centroid(polygon);
        } else if (cellPolygon == null) {
            copy.position = newPosition;
        }
        return copy;
    }

    /** The same entity as the wall of {@code wallCell}: it names the cell, takes its shape from it, and has no polygon. */
    public final Entity withCell(Cell wallCell) {
        Entity copy = copy();
        copy.cell = Ids.require(ResourceKind.CELL.prefix(), wallCell.id());
        copy.polygon = null;
        copy.validate();
        return copy.resolve(wallCell);
    }

    /** The same entity with this outline (a convex polygon), and its centroid as the position; null drops it. */
    public final Entity withPolygon(List<Position> newPolygon) {
        Entity copy = copy();
        copy.polygon = newPolygon == null ? null : List.copyOf(newPolygon);
        copy.validate();
        return copy;
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

    /** The same entity made of {@code newMaterial} (a {@link Materials} name); null drops it. */
    public final Entity withMaterial(String newMaterial) {
        Entity copy = copy();
        copy.material = newMaterial;
        copy.validate();
        return copy;
    }

    public final Entity withSpeed(Double newSpeed) {
        Entity copy = copy();
        copy.speed = newSpeed;
        copy.validate();
        return copy;
    }

    public final Entity withVision(Double newVision) {
        Entity copy = copy();
        copy.vision = newVision;
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

    /** The same entity with these attributes; null drops them. */
    public final Entity withAttributes(Attributes newAttributes) {
        Entity copy = copy();
        copy.level = newAttributes == null ? null : newAttributes.level();
        copy.strength = newAttributes == null ? null : newAttributes.strength();
        copy.dexterity = newAttributes == null ? null : newAttributes.dexterity();
        copy.intelligence = newAttributes == null ? null : newAttributes.intelligence();
        copy.validate();
        return copy;
    }

    /** The same entity on the side {@code newFaction}; null drops it. */
    public final Entity withFaction(String newFaction) {
        Entity copy = copy();
        copy.faction = newFaction;
        copy.validate();
        return copy;
    }

    /** The same entity with this reach (m, edge to edge); null drops it, back to its radius. */
    public final Entity withReach(Double newReach) {
        Entity copy = copy();
        copy.reach = newReach;
        copy.validate();
        return copy;
    }

    /** The same entity with this strike time (s); null drops it, back to the default. */
    public final Entity withStrikeTime(Double newStrikeTime) {
        Entity copy = copy();
        copy.strikeTime = newStrikeTime;
        copy.validate();
        return copy;
    }

    /** The same entity made by the den {@code newSpawner}, an entity id; null drops it. */
    public final Entity withSpawner(String newSpawner) {
        Entity copy = copy();
        copy.spawner = newSpawner;
        copy.validate();
        return copy;
    }

    /** The same entity with {@code foe}, an entity id, among its {@link #foes()}; itself if it is already. */
    public final Entity withFoe(String foe) {
        if (foes.contains(foe)) {
            return this;
        }
        Entity copy = copy();
        copy.foes = Stream.concat(foes.stream(), Stream.of(foe)).toList();
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
