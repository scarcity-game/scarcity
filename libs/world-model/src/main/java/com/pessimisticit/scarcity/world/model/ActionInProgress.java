package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.node.ObjectNode;

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
        @JsonProperty(required = true) Timing timing,
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
        Check.required(timing, "timing");
        if (seq != null && seq < 0) {
            throw new IllegalArgumentException("seq: must be >= 0, got " + seq);
        }
    }

    /**
     * Replaces the generated accessor of the {@code params} component so that
     * it returns a copy: an {@code ObjectNode} is mutable, and the record must
     * stay immutable.
     */
    public ObjectNode params() {
        return params.deepCopy();
    }

    /** This action ending at {@code time} instead: a channel that was stopped (docs/actions.md, "Actions"). */
    public ActionInProgress endingAt(double time) {
        return new ActionInProgress(type, params, start, time, timing, seq, draw);
    }

    public double duration() {
        return end - start;
    }
}
