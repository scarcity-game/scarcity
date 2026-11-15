package com.pessimisticit.scarcity.orbital;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires the player from {@link OrbitalProperties}. */
@Configuration(proxyBeanMethods = false)
public class OrbitalConfig {

    /** How long a rejected or unavailable choice sits out. */
    static final Duration SKIP_FOR = Duration.ofMinutes(1);
    /** The longest wait between turns (401/403, and the back-off's cap). */
    static final Duration LONGEST_WAIT = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(OrbitalConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Built from Boot's builder, so calls are observed and carry {@code traceparent}. */
    @Bean
    World world(RestClient.Builder builder, OrbitalProperties props) {
        return new World(builder.baseUrl(props.worldUrl().toString())
                .defaultHeader(World.API_KEY_HEADER, props.apiKey())
                .build());
    }

    @Bean
    Player player(World world, OrbitalProperties props, Clock clock, MeterRegistry meters) {
        long seed = props.seed() != null ? props.seed() : new SecureRandom().nextLong();
        LOG.info("playing {} every {} with seed {}, weights {}", props.entity(), props.interval(), seed,
                new TreeMap<>(props.weights()));
        List<Choice> choices = List.of(new StepChoice(props.step()), new WaitChoice(props.waiting()),
                new ThrowChoice(props.throwing()));
        Chooser chooser = new Chooser(choices, props.weights(), new SplittableRandom(seed), clock, SKIP_FOR);
        return new Player(world, props.entity(), chooser, clock, meters);
    }

    @Bean
    OrbitalLoop orbitalLoop(Player player, OrbitalProperties props, ObservationRegistry observations) {
        return new OrbitalLoop(player, new Pacing(props.interval(), LONGEST_WAIT), observations);
    }

    @Bean
    WorldHealth worldHealthIndicator(Player player, Clock clock, OrbitalProperties props) {
        return new WorldHealth(player, clock, props.healthWindow());
    }
}
