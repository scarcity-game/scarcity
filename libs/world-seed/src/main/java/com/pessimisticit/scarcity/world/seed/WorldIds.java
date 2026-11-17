package com.pessimisticit.scarcity.world.seed;

/** Fixed ids of the seeded test area; part of the contract with clients. */
public final class WorldIds {

    public static final String GAME = "game:00000000-0000-4000-8000-000000000001";
    public static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    public static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    public static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";

    // The dive game (#172): a depth 0 area with stairs, a mouse and the diver. The diver's id
    // sorts before the mouse's: both are idle from time 0 and the queue breaks that tie by id,
    // so the diver is up first.
    public static final String DIVE_GAME = "game:00000000-0000-4000-8000-000000000011";
    public static final String DIVE_AREA = "area:00000000-0000-4000-8000-000000000012";
    public static final String DIVE_STAIRS_UP = "entity:00000000-0000-4000-8000-000000000013";
    public static final String DIVE_STAIRS_DOWN = "entity:00000000-0000-4000-8000-000000000014";
    public static final String DIVE_MOUSE = "entity:00000000-0000-4000-8000-000000000016";
    public static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";

    private WorldIds() {
    }
}
