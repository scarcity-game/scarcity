package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The body world POSTs to an intelligence service's {@code /turn} (docs/actions.md, "Intelligence
 * services"): the turn, and everything the service may know to decide it.
 *
 * @param area the area the entity is in
 * @param entity the entity whose turn it is
 * @param version the entity's version the turn is for; the answer is applied with it as {@code ifVersion}
 * @param since the game time the entity became idle
 * @param range how far the context reaches from {@code self}'s position, in metres
 * @param self the entity as world holds it
 * @param entities the other entities in range: those whose position is within {@code range}, and
 *     the walls of the cells in range, unresolved
 * @param cells the cells whose polygon comes within {@code range} of {@code self}'s position
 */
public record TurnRequest(
        @JsonProperty(required = true) String area,
        @JsonProperty(required = true) String entity,
        @JsonProperty(required = true) long version,
        @JsonProperty(required = true) double since,
        @JsonProperty(required = true) double range,
        @JsonProperty(required = true) Entity self,
        @JsonProperty(required = true) List<Entity> entities,
        @JsonProperty(required = true) List<Cell> cells) {

    public TurnRequest {
        Check.text(area, "area");
        Check.text(entity, "entity");
        Check.nonNegative(since, "since");
        Check.nonNegative(range, "range");
        Check.required(self, "self");
        if (!self.id().equals(entity)) {
            throw new IllegalArgumentException("self: is " + self.id() + ", not the turn's entity " + entity);
        }
        entities = List.copyOf(Check.required(entities, "entities"));
        cells = List.copyOf(Check.required(cells, "cells"));
    }
}
