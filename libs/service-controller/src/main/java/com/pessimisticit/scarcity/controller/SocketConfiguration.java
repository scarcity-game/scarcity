package com.pessimisticit.scarcity.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@code controller.mode: socket} (the default): the service token, world's API, the subscriptions
 * socket and the turns, for an npc service that holds a socket and posts intents.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "controller", name = "mode", havingValue = "socket", matchIfMissing = true)
@EnableConfigurationProperties(ControllerProperties.class)
@Import({ServiceToken.class, World.class, Player.class, Turns.class, TurnSocket.class, SubscriptionHealth.class})
class SocketConfiguration {
}
