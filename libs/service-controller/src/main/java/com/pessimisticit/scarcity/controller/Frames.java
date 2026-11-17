package com.pessimisticit.scarcity.controller;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** {@code frame} as a turn in {@code area}; empty if it is another type or another area's. */
    static Optional<TurnFrame> turn(JsonNode frame, String area) {
        if (!"turn".equals(frame.path("type").asText()) || !area.equals(frame.path("area").asText())) {
            return Optional.empty();
        }
        return Optional.of(new TurnFrame(area, frame.path("entity").asText(), frame.path("version").asLong(),
                frame.path("since").asDouble()));
    }

    /** {@code {"type": type, key: value}}, in that order. */
    private static String frame(String type, String key, String value) {
        Map<String, String> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put(key, value);
        return WorldJson.toJson(frame);
    }
}
