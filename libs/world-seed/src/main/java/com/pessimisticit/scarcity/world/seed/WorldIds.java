package com.pessimisticit.scarcity.world.seed;

import java.util.List;
import java.util.stream.IntStream;

/** Fixed ids of the seeded test area; part of the contract with clients. */
public final class WorldIds {

    public static final String GAME = "game:00000000-0000-4000-8000-000000000001";
    public static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    public static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    public static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";

    // The test room's mice (microbe plays them); ids sort after the orbiter's, so it stays up first.
    public static final String MOUSE_1 = "entity:00000000-0000-4000-8000-000000000005";
    public static final String MOUSE_2 = "entity:00000000-0000-4000-8000-000000000006";
    public static final String MOUSE_3 = "entity:00000000-0000-4000-8000-000000000007";

    // The dive game (#172): a depth 0 area with stairs, a mouse and the diver. The diver's id
    // sorts before the mouse's: both are idle from time 0 and the queue breaks that tie by id,
    // so the diver is up first.
    public static final String DIVE_GAME = "game:00000000-0000-4000-8000-000000000011";
    public static final String DIVE_AREA = "area:00000000-0000-4000-8000-000000000012";
    public static final String DIVE_STAIRS_UP = "entity:00000000-0000-4000-8000-000000000013";
    public static final String DIVE_STAIRS_DOWN = "entity:00000000-0000-4000-8000-000000000014";
    public static final String DIVE_MOUSE = "entity:00000000-0000-4000-8000-000000000016";
    public static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";

    // The four walls of each area (#296), in Rooms.enclose order: south, north, west, east.
    public static final List<String> WALLS = walls(0x21);
    public static final List<String> DIVE_WALLS = walls(0x25);

    private WorldIds() {
    }

    private static List<String> walls(int first) {
        return IntStream.range(0, 4)
                .mapToObj(i -> String.format("entity:00000000-0000-4000-8000-%012x", first + i)).toList();
    }
}
