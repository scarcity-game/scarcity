package com.pessimisticit.scarcity.subscriptions;

import java.util.List;
import java.util.Map;

import org.springframework.web.socket.TextMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code turn} frame (docs/websocket.md) a socket gets for the area's {@code up}: one if
 * its controller is the socket's principal, none otherwise. Pending entities get none. A user socket plays
 * {@code {"kind":"user","sub"}}; a service socket ({@code kind=service} JWT) plays
 * {@code {"kind":"service","principal"}}.
 */
final class TurnFrames {

    private TurnFrames() {
    }

    /** Caller holds the area's monitor ({@code versions} is the area's map). */
    static List<TextMessage> of(ObjectMapper json, String area, JsonNode up, Map<String, Long> versions,
            Client c) {
        if (up == null || !controls(up.path("controller"), c)) {
            return List.of();
        }
        String entity = up.path("entity").asText();
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "turn");
        frame.put("area", area);
        frame.put("entity", entity);
        Long version = versions.get(entity);
        if (version != null) {
            frame.put("version", version.longValue());
        }
        frame.set("since", up.path("since"));
        return List.of(new TextMessage(frame.toString()));
    }

    static boolean controls(JsonNode controller, Client c) {
        String user = c.user;
        if (user == null) {
            return false;
        }
        return switch (controller.path("kind").asText()) {
            case "user" -> !c.service && user.equals(controller.path("sub").asText(null));
            case "service" -> c.service && user.equals(controller.path("principal").asText(null));
            default -> false;
        };
    }
}
