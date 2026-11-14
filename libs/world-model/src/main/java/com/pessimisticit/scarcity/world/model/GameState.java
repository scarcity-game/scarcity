package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/** The state of a {@code game} resource. Unknown keys are kept in {@code extra}. */
public record GameState(
        @JsonProperty(required = true) String name,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> extra) {

    private static final Set<String> KNOWN = Set.of("name");

    public GameState {
        Check.text(name, "name");
        extra = Extra.copy(extra, KNOWN);
    }

    public GameState(String name) {
        this(name, Map.of());
    }
}
