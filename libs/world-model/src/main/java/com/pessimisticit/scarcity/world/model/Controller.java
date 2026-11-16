package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Who decides what an entity does next (docs/actions.md, "Controllers"). The
 * JSON {@code kind} selects the implementation; nothing else looks at it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Controller.Service.class, name = "service"),
    @JsonSubTypes.Type(value = Controller.User.class, name = "user")
})
public sealed interface Controller permits Controller.Service, Controller.User {

    /** A service plays the entity, authenticated by API key as {@code principal}. */
    record Service(@JsonProperty(required = true) String principal) implements Controller {
        public Service {
            Check.text(principal, "principal");
        }
    }

    /** A player plays the entity: a JWT whose {@code sub} matches. */
    record User(@JsonProperty(required = true) String sub) implements Controller {
        public User {
            Check.text(sub, "sub");
        }
    }
}
