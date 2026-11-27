package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How a faction treats the others (docs/resources.md, "Factions"): {@code hostileTo} lists the sides
 * its creatures attack on sight, each a faction or {@link Factions#PLAYER}. Empty is neutral: it
 * attacks only its {@code foes}, those that attacked it.
 */
public record Faction(@JsonProperty(required = true) List<String> hostileTo) {

    /** A neutral faction. */
    public static final Faction NEUTRAL = new Faction(List.of());

    public Faction {
        hostileTo = List.copyOf(Check.required(hostileTo, "hostileTo"));
        Set<String> seen = new HashSet<>();
        for (String side : hostileTo) {
            if (!seen.add(Check.text(side, "hostileTo"))) {
                throw new IllegalArgumentException("hostileTo: '" + side + "' is listed twice");
            }
        }
    }
}
