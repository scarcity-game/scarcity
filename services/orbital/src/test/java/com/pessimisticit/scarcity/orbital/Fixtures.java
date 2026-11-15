package com.pessimisticit.scarcity.orbital;

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

    static final String KEY = "orbital-test-key-5f0c2a9e41d7";
    static final Map<String, Double> WEIGHTS = Map.of("step", 2.0, "wait", 1.0, "throw", 1.0);
    static final StepSettings ORBIT = new StepSettings(new Position(5.0, 4.0), 3.0, 0.5);
    static final String PROJECTILE = "{\"state\":{\"kind\":\"mobile\",\"name\":\"pebble\",\"speed\":6.0,"
            + "\"actions\":[\"fly\",\"decay\"]},\"speed\":6.0,"
            + "\"damage\":{\"mean\":2.0,\"sd\":0.5,\"min\":0.5,\"max\":4.0},\"p\":0.5,"
            + "\"debris\":{\"kind\":\"fixed\",\"name\":\"rubble\",\"actions\":[\"decay\"]},\"decay\":{\"duration\":20.0}}";

    private Fixtures() {
    }

    /** auth and world at {@code url}, subscriptions at {@code ws}. */
    static OrbitalProperties props(String url, String ws, String key) {
        return props(url, ws, key, Duration.ZERO);
    }

    static OrbitalProperties props(String url, String ws, String key, Duration think) {
        return props(url, ws, key, think, Duration.ofSeconds(90));
    }

    static OrbitalProperties props(String url, String ws, String key, Duration think, Duration silence) {
        return new OrbitalProperties(URI.create(url), key, URI.create(url), URI.create(ws), StubWorld.AREA,
                think, Duration.ofMillis(50), Duration.ofMillis(400), silence, 1L, WEIGHTS,
                ORBIT, new WaitSettings(1.0), new ThrowSettings(StubWorld.PILLAR, 0.2, 2.0, PROJECTILE));
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
        return new Player(world(url, token(url, KEY, clock)), chooser(weights, 42, clock), clock, meters);
    }
}
