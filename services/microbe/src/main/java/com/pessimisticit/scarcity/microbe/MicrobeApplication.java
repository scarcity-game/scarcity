package com.pessimisticit.scarcity.microbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Plays the mice (#170): every entity whose controller is {@code microbe}, in every area. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MicrobeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicrobeApplication.class, args);
    }
}
