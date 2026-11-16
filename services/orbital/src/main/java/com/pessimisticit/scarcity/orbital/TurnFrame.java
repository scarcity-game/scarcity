package com.pessimisticit.scarcity.orbital;

/**
 * subscriptions' {@code turn} frame (docs/websocket.md): {@code entity}, which orbital's
 * principal controls, is up in {@code area} at {@code version} since game time {@code since}.
 */
public record TurnFrame(String area, String entity, long version, double since) {
}
