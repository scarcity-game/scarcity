package com.pessimisticit.scarcity.subscriptions;

import java.util.List;
import java.util.Map;

import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code turn} frame (docs/websocket.md) a socket gets for the area's {@code activeEntity}: one if
 * its controller is the socket's principal, none otherwise. Pending entities get none. A user socket plays
 * {@code {"kind":"user","sub"}}; a service socket ({@code kind=service} JWT) plays
 * {@code {"kind":"service","principal"}}.
 */
final class TurnFrames {

    private TurnFrames() {
    }

    /** Caller holds the area's monitor ({@code versions} is the area's map). */
    static List<TextMessage> of(ObjectMapper json, String area, JsonNode activeEntity, Map<String, Long> versions,
            Client c) {
        if (activeEntity == null || !controls(activeEntity.path("controller"), c)) {
            return List.of();
        }
        String entity = activeEntity.path("entity").asText();
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "turn");
        frame.put("area", area);
        frame.put("entity", entity);
        Long version = versions.get(entity);
        if (version != null) {
            frame.put("version", version.longValue());
        }
        frame.set("since", activeEntity.path("since"));
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
