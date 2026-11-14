package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** When an action changes the state: its {@code effect} key (docs/actions.md, "Actions"). */
public enum Timing {
    /** When the action starts; the rest is recovery. */
    @JsonProperty("start") START,
    /** When the action ends; until then it is a wind-up that can be cancelled. */
    @JsonProperty("end") END,
    /** Never; the action only takes time. */
    @JsonProperty("none") NONE
}
