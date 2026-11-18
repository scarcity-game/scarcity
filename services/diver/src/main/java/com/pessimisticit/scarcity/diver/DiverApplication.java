package com.pessimisticit.scarcity.diver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Plays the diver (#171): the npc that walks to the stairs down and takes them, on every level. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiverApplication.class, args);
    }
}
