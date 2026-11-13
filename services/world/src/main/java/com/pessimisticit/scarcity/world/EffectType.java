package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An effect type.
 */
public interface EffectType {
    /** The name used in EffectCall.type and in the log. */
    String name();

    /** Runs the effect. All changes go through ctx. */
    void run(JsonNode params, EffectContext ctx);
}