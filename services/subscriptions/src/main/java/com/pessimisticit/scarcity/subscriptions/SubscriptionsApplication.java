package com.pessimisticit.scarcity.subscriptions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pessimisticit.scarcity.resourcemodel.autoconfigure.ResourceApiAutoConfiguration;

/** The resource API (ResourceApiAutoConfiguration) is excluded: this service serves only the WebSocket. */
@SpringBootApplication(exclude = ResourceApiAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class SubscriptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionsApplication.class, args);
    }
}
