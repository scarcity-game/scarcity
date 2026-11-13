package com.pessimisticit.scarcity.world;

import java.util.random.RandomGenerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Context for an effect.
 */
public interface EffectContext {
    /** Game time of the event that set this effect off. */
    double time();

    /** The area's random generator. */
    RandomGenerator random();

    /** The area's id. */
    String areaId();

    /** True if the area or an entity of the area with this id exists now (not destroyed this tick). */
    boolean exists(String id);

    /** The mutable state of the area or of an entity of the area; the change is recorded and the resource is written once at the end of the tick. Throws IllegalArgumentException if it does not exist. */
    ObjectNode state(String id);

    /** Creates an entity with the given state as a child of parent (must be the area); returns its new id. */
    String create(String parentId, ObjectNode state);

    /** Destroys an entity. An action it has in progress is cancelled. */
    void destroy(String id);

    /** Starts an action on an entity right after this effect's log entry is written (cause: this effect). */
    void startAction(String id, String type, JsonNode params);

    /** Triggers another effect right after this effect's log entry is written (cause: this effect), depth first, in call order. */
    void trigger(String type, ObjectNode params);

    /** Sets the entry's {@code result}. */
    void result(ObjectNode result);

    /** Adds a random draw record (with a "for" key) to the entry's {@code draws}. */
    void draw(ObjectNode draw);

    /** Read-only view of the state of the area or of an entity; does not mark it changed. Throws IllegalArgumentException if it does not exist. */
    JsonNode peek(String id);

    /** Read-only view of the entities of the area now, ascending by id (created ones included, destroyed ones not). */
    java.util.Map<String, JsonNode> entities();
}