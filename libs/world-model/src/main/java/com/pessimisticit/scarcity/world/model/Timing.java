package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** When an action changes the state: its {@code effect} key (docs/actions.md, "Actions"). */
public enum Timing {
    /** When the action starts; the rest is recovery. */
    @JsonProperty("start") START,
    /** When the action ends; until then it is a wind-up that can be cancelled. */
    @JsonProperty("end") END,
    /**
     * Never: the action changes no state at all. Its only result is that the
     * entity is busy (can't start another action) until {@code end}, like {@code wait}.
     */
    @JsonProperty("none") NONE
}
