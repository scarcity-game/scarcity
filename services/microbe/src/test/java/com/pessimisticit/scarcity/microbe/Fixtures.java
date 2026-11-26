package com.pessimisticit.scarcity.microbe;

/** microbe's configuration and a mouse, built by hand for tests without Spring. */
final class Fixtures {

    private Fixtures() {
    }

    /** A mouse of the levels (libs/world-seed {@code Levels.mouse}), without id and version. */
    static String mouse(double x, double y) {
        return "{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"mouse\",\"name\":\"mouse\","
                + "\"position\":{\"x\":" + x + ",\"y\":" + y + "},\"speed\":0.5,\"radius\":0.2,\"hp\":3.0,\"damageable\":true,"
                + "\"actions\":[\"step\",\"wait\"],\"controller\":{\"kind\":\"service\",\"principal\":\"microbe\"}}";
    }
}
