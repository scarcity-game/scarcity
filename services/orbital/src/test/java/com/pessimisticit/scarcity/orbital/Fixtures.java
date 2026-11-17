package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.Chooser;
import com.pessimisticit.scarcity.controller.ControllerProperties;
import com.pessimisticit.scarcity.controller.Player;
import com.pessimisticit.scarcity.controller.ServiceToken;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.StepSettings;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.ThrowSettings;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.WaitSettings;
import com.pessimisticit.scarcity.world.model.Position;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.web.client.RestClient;

/** The defaults of application.yml, built by hand for tests without Spring. */
final class Fixtures {

    /** The key the stub auth accepts; PlaysRealWorldTest gives it to the real one. */
    static final String KEY = StubWorld.KEY;
    static final Map<String, Double> WEIGHTS = Map.of("step", 2.0, "wait", 1.0, "throw", 1.0);
    static final StepSettings ORBIT = new StepSettings(new Position(5.0, 4.0), 3.0, 0.5);
    static final String PROJECTILE = "{\"state\":{\"prototype\":\"pebble\"},\"speed\":6.0,"
            + "\"damageRoll\":{\"mean\":2.0,\"sd\":0.5,\"min\":0.5,\"max\":4.0},\"p\":0.5,"
            + "\"debris\":{\"prototype\":\"rubble\"},\"decay\":{\"duration\":20.0}}";

    private Fixtures() {
    }

    /** auth and world at {@code url}, subscriptions at {@code ws}. */
    static ControllerProperties props(String url, String ws, String key) {
        return new ControllerProperties("orbital", URI.create(url), key, URI.create(url), URI.create(ws),
                StubWorld.AREA, Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(400), Duration.ofSeconds(90),
                1L, WEIGHTS);
    }

    static List<Choice> choices() {
        return List.of(new StepChoice(ORBIT), new WaitChoice(new WaitSettings(1.0)),
                new ThrowChoice(new ThrowSettings(StubWorld.PILLAR, 0.2, 2.0, PROJECTILE)));
    }

    static ServiceToken token(String url, String key, Clock clock) {
        return new ServiceToken(RestClient.builder(), props(url, "ws://unused", key), clock);
    }

    static World world(String url, ServiceToken token) {
        return new World(RestClient.builder(), props(url, "ws://unused", KEY), token);
    }

    static Chooser chooser(Map<String, Double> weights, long seed, Clock clock) {
        return new Chooser(choices(), weights, new SplittableRandom(seed), clock, Chooser.SKIP_FOR);
    }

    static Player player(String url, Map<String, Double> weights, Clock clock, MeterRegistry meters) {
        return new Player(world(url, token(url, KEY, clock)), chooser(weights, 42, clock),
                props(url, "ws://unused", KEY), clock, meters);
    }
}
