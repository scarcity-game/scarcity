package com.pessimisticit.scarcity.brawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Plays the fighters (#454): every entity whose controller is {@code brawler}, in every area. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrawlerApplication.class, args);
    }
}
