package com.pessimisticit.scarcity.world.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity prototypes: a name ({@code pillar}, {@code stairs-down}, {@code mouse})
 * and the defaults of that kind of entity, a flat entity JSON without an
 * {@code id}, a {@code version} or a {@code position}, in
 * {@code /prototypes/<name>.json}. Most prototypes are only data. One that has
 * keys of its own has a class, registered in {@link #CLASSES}; every other
 * prototype reads as its kind's class.
 */
public final class Prototypes {

    /** The classes of the prototypes that have one; the key is the prototype name. */
    private static final Map<String, Class<? extends Entity>> CLASSES = Map.of(
            Stairs.DOWN, Stairs.class,
            Stairs.UP, Stairs.class,
            Stash.PROTOTYPE, Stash.class,
            Placeholder.PROTOTYPE, Placeholder.class);

    private static final Map<String, Entity> LOADED = new ConcurrentHashMap<>();

    private Prototypes() {
    }

    /** The class that reads an entity of this kind and prototype. */
    static Class<? extends Entity> classOf(EntityKind kind, String prototype) {
        Class<? extends Entity> own = prototype == null ? null : CLASSES.get(prototype);
        if (own != null) {
            return own;
        }
        return switch (kind) {
            case FIXED -> Fixed.class;
            case MOB -> Mob.class;
            case NPC -> Npc.class;
            case PLAYER -> Player.class;
        };
    }

    /**
     * The prototype's defaults: an entity with no id, version or position.
     *
     * @throws IllegalArgumentException if there is no such prototype
     */
    public static Entity get(String prototype) {
        Check.text(prototype, "prototype");
        return LOADED.computeIfAbsent(prototype, Prototypes::load);
    }

    /**
     * A prototype with a {@code material}, a shape and no {@code mass} (the {@code pillar}) weighs what
     * that much of the material does, {@link Material#HEIGHT} tall: its mass is derived, not written.
     */
    private static Entity derived(Entity entity) {
        boolean shaped = entity.radius() != null || entity.polygon() != null;
        if (entity.material() == null || entity.mass() != null || !shaped) {
            return entity;
        }
        return Materials.get(entity.material()).make(entity);
    }

    private static Entity load(String prototype) {
        if (!prototype.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("prototype: not a prototype name: " + prototype);
        }
        try (InputStream in = Prototypes.class.getResourceAsStream("/prototypes/" + prototype + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("prototype: unknown prototype '" + prototype + "'");
            }
            Entity entity = WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Entity.class);
            if (!prototype.equals(entity.prototype())) {
                throw new IllegalStateException("prototype file " + prototype + ".json says " + entity.prototype());
            }
            return derived(entity);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
