package com.pessimisticit.scarcity.world;

import java.time.Clock;

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
     * The only source of randomness in the service; tests pass a seeded or scripted one.
     */
    @Bean
    RandomGenerator randomGenerator() {
        return new java.util.SplittableRandom();
    }

}
