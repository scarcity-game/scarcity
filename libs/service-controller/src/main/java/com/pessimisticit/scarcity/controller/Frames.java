package com.pessimisticit.scarcity.controller;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The frames of docs/websocket.md that a controller sends and reads. */
final class Frames {

    private Frames() {
    }

    static String auth(String token) {
        return frame("auth", "token", token);
    }

    static String subscribe(String area) {
        return frame("subscribe", "area", area);
    }

    /** The turn feed (docs/websocket.md): every area this socket's principal is up in. */
    static String subscribeTurns() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "subscribe");
        frame.put("turns", true);
        return WorldJson.toJson(frame);
    }

    /**
     * {@code frame} as a turn in {@code area}, or in any area when {@code area} is null (the feed);
     * empty if it is another type or another area's.
     */
    static Optional<TurnFrame> turn(JsonNode frame, String area) {
        String in = frame.path("area").asText();
        if (!"turn".equals(frame.path("type").asText()) || area != null && !area.equals(in)) {
            return Optional.empty();
        }
        return Optional.of(new TurnFrame(in, frame.path("entity").asText(), frame.path("version").asLong(),
                frame.path("since").asDouble()));
    }

    /** A {@code subscribed} frame for what was asked: the area, or the feed ({@code "turns": true}). */
    static boolean subscribedTo(JsonNode frame, String area) {
        return area == null ? frame.path("turns").asBoolean(false) : area.equals(frame.path("area").asText());
    }

    /** {@code {"type": type, key: value}}, in that order. */
    private static String frame(String type, String key, String value) {
        Map<String, String> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put(key, value);
        return WorldJson.toJson(frame);
    }
}
