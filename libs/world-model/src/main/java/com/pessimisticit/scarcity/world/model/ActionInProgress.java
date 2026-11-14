package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An entity's {@code action}: what it is doing now (docs/actions.md,
 * "Actions"). {@code seq} is the log entry of its start; {@code draw} is the
 * behaviour's random pick, if it was one. Both are optional.
 */
public record ActionInProgress(
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) ObjectNode params,
        @JsonProperty(required = true) double start,
        @JsonProperty(required = true) double end,
        @JsonProperty(required = true) Timing effect,
        Long seq,
        Draw draw) {

    public ActionInProgress {
        Check.text(type, "type");
        params = Check.required(params, "params").deepCopy();
        Check.finite(start, "start");
        Check.finite(end, "end");
        if (end < start) {
            throw new IllegalArgumentException("end: must not be before start (" + start + "), got " + end);
        }
        Check.required(effect, "effect");
        if (seq != null && seq < 0) {
            throw new IllegalArgumentException("seq: must be >= 0, got " + seq);
        }
    }

    /** A copy: the record stays immutable. */
    @Override
    public ObjectNode params() {
        return params.deepCopy();
    }

    public double duration() {
        return end - start;
    }
}
