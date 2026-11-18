package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.ControllerProperties;
import com.pessimisticit.scarcity.controller.ServiceToken;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.TestClock;
import com.pessimisticit.scarcity.controller.World;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** diver's configuration, the dive game's entities and a World on a stub, built by hand without Spring. */
final class Fixtures {

    static final String KEY = StubWorld.KEY;
    static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";
    static final String STAIRS_UP = "entity:00000000-0000-4000-8000-000000000013";
    static final String STAIRS_DOWN = "entity:00000000-0000-4000-8000-000000000014";

    private Fixtures() {
    }

    /** auth and world at {@code url}, subscriptions at {@code ws}; the turn feed, no pause. */
    static ControllerProperties props(String url, String ws) {
        return new ControllerProperties("diver", URI.create(url), KEY, URI.create(url), URI.create(ws), null,
                Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(400), Duration.ofSeconds(90), 1L,
                Map.of("step", 1.0, "traverse", 1.0, "wait", 1.0));
    }

    static World world(StubWorld stubWorld) {
        ControllerProperties props = props(stubWorld.url(), "ws://unused");
        return new World(RestClient.builder(), props, new ServiceToken(RestClient.builder(), props, new TestClock()));
    }

    /** The diver (libs/world-seed {@code seed/diver.json}) at {@code (x, y)}, without id and version. */
    static String diver(double x, double y) {
        return "{\"entityKind\":\"npc\",\"prototype\":\"diver\",\"name\":\"diver\","
                + "\"position\":{\"x\":" + x + ",\"y\":" + y + "},\"speed\":0.5,\"radius\":0.3,\"hp\":10.0,"
                + "\"actions\":[\"step\",\"wait\",\"traverse\"],\"controller\":{\"kind\":\"service\",\"principal\":\"diver\"}}";
    }

    /** Stairs of {@code prototype} at {@code (x, y)}, without id and version. */
    static String stairs(String prototype, double x, double y) {
        return "{\"entityKind\":\"fixed\",\"prototype\":\"" + prototype + "\",\"name\":\"" + prototype.replace('-', ' ')
                + "\",\"position\":{\"x\":" + x + ",\"y\":" + y + "},\"overlap\":true,\"damageable\":false}";
    }
}
