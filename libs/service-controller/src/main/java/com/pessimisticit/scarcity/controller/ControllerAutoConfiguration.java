package com.pessimisticit.scarcity.controller;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * A service controller (libs/service-controller): the service token, world's API, the
 * subscriptions socket and the turns, for every {@link Choice} bean the service defines.
 */
@AutoConfiguration
@EnableConfigurationProperties(ControllerProperties.class)
@Import({ControllerConfig.class, ServiceToken.class, World.class, Chooser.class, Player.class, Turns.class,
        TurnSocket.class, SubscriptionHealth.class})
public class ControllerAutoConfiguration {
}
