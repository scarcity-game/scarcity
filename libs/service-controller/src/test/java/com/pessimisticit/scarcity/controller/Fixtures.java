package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.web.client.RestClient;

/** A controller's configuration and choices, built by hand for tests without Spring. */
final class Fixtures {

    static final String KEY = "controller-test-key-5f0c2a9e41d7";
    static final Map<String, Double> WEIGHTS = Map.of("step", 2.0, "wait", 1.0, "throw", 1.0);

    private Fixtures() {
    }

    /** auth and world at {@code url}, subscriptions at {@code ws}. */
    static ControllerProperties props(String url, String ws, String key) {
        return props(url, ws, key, Duration.ZERO);
    }

    static ControllerProperties props(String url, String ws, String key, Duration think) {
        return props(url, ws, key, think, Duration.ofSeconds(90));
    }

    static ControllerProperties props(String url, String ws, String key, Duration think, Duration silence) {
        return props(url, ws, key, think, silence, StubWorld.AREA);
    }

    /** {@code area} null: the turn feed. */
    static ControllerProperties props(String url, String ws, String key, Duration think, Duration silence, String area) {
        return new ControllerProperties("tester", URI.create(url), key, URI.create(url), URI.create(ws), area,
                think, Duration.ofMillis(50), Duration.ofMillis(400), silence, 1L, WEIGHTS);
    }

    /** Three choices named as orbital's; each plans a wait. */
    static List<Choice> choices() {
        return List.of(named("step"), named("wait"), named("throw"));
    }

    static Choice named(String name) {
        return new Choice() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Plan plan(TurnFrame turn, Entity self, World world) {
                return Plan.of(Wait.INSTANCE.spec(new Wait.Params(1.0)));
            }
        };
    }

    static ServiceToken token(String url, String key, Clock clock) {
        return new ServiceToken(RestClient.builder(), props(url, "ws://unused", key), clock);
    }

    static Chooser chooser(Map<String, Double> weights, long seed, Clock clock) {
        return new Chooser(choices(), weights, new SplittableRandom(seed), clock, Chooser.SKIP_FOR);
    }
}
