package com.pessimisticit.scarcity.world;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.SplittableRandom;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import java.util.random.RandomGenerator;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorldApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorldApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The only source of randomness in the service ({@link WorldRandomProperties}); tests pass a
     * seeded or scripted one. The seed is logged so a run can be replayed.
     */
    @Bean
    RandomGenerator randomGenerator(WorldRandomProperties worldRandomProperties) {
        long seed = worldRandomProperties.seed() != null ? worldRandomProperties.seed()
                : new SecureRandom().nextLong();
        LoggerFactory.getLogger(WorldApplication.class).info("world random seed {}", seed);
        return new SplittableRandom(seed);
    }

}
