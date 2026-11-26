package com.pessimisticit.scarcity.controller;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * A service controller (libs/service-controller): the draws and the weighted {@link Choice}s every
 * service shares, and then one of two ways to be told its turns, switched by {@code controller.mode}:
 * {@link SocketConfiguration} ({@code socket}, the default: npc services) or
 * {@link EndpointConfiguration} ({@code endpoint}: intelligence services).
 */
@AutoConfiguration
@EnableConfigurationProperties(CommonProperties.class)
@Import({ControllerConfig.class, Chooser.class, SocketConfiguration.class, EndpointConfiguration.class})
public class ControllerAutoConfiguration {
}
