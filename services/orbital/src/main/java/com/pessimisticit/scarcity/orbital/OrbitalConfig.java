package com.pessimisticit.scarcity.orbital;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock and the seeded source of orbital's draws. */
@Configuration(proxyBeanMethods = false)
public class OrbitalConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OrbitalConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** All of orbital's draws; the seed is logged so a run can be replayed. */
    @Bean
    RandomGenerator draws(OrbitalProperties props) {
        long seed = props.seed() != null ? props.seed() : new SecureRandom().nextLong();
        LOG.info("playing in {} with seed {}, weights {}", props.area(), seed, new TreeMap<>(props.weights()));
        return new SplittableRandom(seed);
    }
}
