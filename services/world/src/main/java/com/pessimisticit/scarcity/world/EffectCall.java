package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A request to run an effect of {@code type} with {@code params}.
 */
public record EffectCall(String type, ObjectNode params) {
}
