package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** An effect entry's {@code effect}: {@code {"type", "params"}}. */
public record LoggedEffect(String type, ObjectNode params) {

    public LoggedEffect {
        Objects.requireNonNull(type, "type");
        params = Objects.requireNonNull(params, "params").deepCopy();
    }

    @Override
    public ObjectNode params() {
        return params.deepCopy();
    }
}
