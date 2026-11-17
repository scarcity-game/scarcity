package com.pessimisticit.scarcity.controller;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock and the seeded source of the controller's draws. */
@Configuration(proxyBeanMethods = false)
public class ControllerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** All of the controller's draws; the seed is logged so a run can be replayed. */
    @Bean
    RandomGenerator draws(ControllerProperties props) {
        long seed = props.seed() != null ? props.seed() : new SecureRandom().nextLong();
        LOG.info("{} playing in {} with seed {}, weights {}", props.principal(), props.area(), seed, new TreeMap<>(props.weights()));
        return new SplittableRandom(seed);
    }
}
