package com.pessimisticit.scarcity.orbital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Plays the test area's orbiter through world's intents API. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrbitalApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrbitalApplication.class, args);
    }
}
