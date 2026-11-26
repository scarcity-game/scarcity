package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.node.ObjectNode;

/**
 * An intelligence service's answer to a {@link TurnRequest} (docs/actions.md, "Intelligence
 * services"): the intent's body without {@code entity} and {@code ifVersion}, which world sets
 * from the turn.
 *
 * @param reported the draws behind the choice, logged verbatim; null for none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnAnswer(
        @JsonProperty(required = true) ActionSpec actionSpec,
        ObjectNode reported) {

    public TurnAnswer {
        Check.required(actionSpec, "actionSpec");
        reported = reported == null ? null : reported.deepCopy();
    }

    /** A copy: an {@code ObjectNode} is mutable, and the record must stay immutable. */
    @Override
    public ObjectNode reported() {
        return reported == null ? null : reported.deepCopy();
    }
}
