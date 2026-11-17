package com.pessimisticit.scarcity.microbe;

import com.pessimisticit.scarcity.controller.ControllerProperties;
import com.pessimisticit.scarcity.controller.StubWorld;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** microbe's configuration and a mouse, built by hand for tests without Spring. */
final class Fixtures {

    static final String KEY = StubWorld.KEY;

    private Fixtures() {
    }

    /** auth and world at {@code url}, subscriptions at {@code ws}; the turn feed. */
    static ControllerProperties props(String url, String ws) {
        return new ControllerProperties("microbe", URI.create(url), KEY, URI.create(url), URI.create(ws), null,
                Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(400), Duration.ofSeconds(90), 1L,
                Map.of("step", 3.0, "wait", 1.0));
    }

    /** A mouse of the levels (libs/world-seed {@code Levels.mouse}), without id and version. */
    static String mouse(double x, double y) {
        return "{\"resourceKind\":\"entity\",\"entityKind\":\"mob\",\"prototype\":\"mouse\",\"name\":\"mouse\","
                + "\"position\":{\"x\":" + x + ",\"y\":" + y + "},\"speed\":0.5,\"radius\":0.2,\"hp\":3.0,"
                + "\"actions\":[\"step\",\"wait\"],\"controller\":{\"kind\":\"service\",\"principal\":\"microbe\"}}";
    }
}
