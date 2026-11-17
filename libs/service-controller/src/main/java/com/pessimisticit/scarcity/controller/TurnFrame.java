package com.pessimisticit.scarcity.controller;

/**
 * subscriptions' {@code turn} frame (docs/websocket.md): {@code entity}, which the controller's
 * principal controls, is up in {@code area} at {@code version} since game time {@code since}.
 */
public record TurnFrame(String area, String entity, long version, double since) {
}
