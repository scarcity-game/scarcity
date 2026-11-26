package com.pessimisticit.scarcity.world.gen;

import java.util.Map;

/**
 * Chooses which cells of a {@link CellGraph} are open floor and where the stairs and spawns go
 * (#386): {@link RoomsAndCorridors} or {@link CaveGenerator}, on any {@link Tessellation}.
 */
public interface Generator {

    /** Its name in a level's log ({@code generator.type}). */
    String type();

    /** Its settings as a level's log gives them ({@code generator.params}), for a passer that needs {@code need} m from every wall. */
    Map<String, Object> params(double need);

    /**
     * The plan on {@code graph}, every choice drawn from {@code recorder}. A disc of radius
     * {@code need} should be able to go from the stairs up to the stairs down; {@link WorldGen#make}
     * checks that it can.
     */
    Plan generate(CellGraph graph, Recorder recorder, double need);
}
