package com.pessimisticit.scarcity.subscriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.socket.TextMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code turn} frames (docs/websocket.md) a socket gets for an area's {@code turns}: one per
 * entry whose controller is the socket's principal. A user socket plays
 * {@code {"kind":"user","sub"}}; a service socket ({@code kind=service} JWT) plays
 * {@code {"kind":"service","principal"}}.
 */
final class TurnFrames {

    private TurnFrames() {
    }

    /** Caller holds the area's monitor ({@code versions} is the area's map). */
    static List<TextMessage> of(ObjectMapper json, String area, JsonNode turns, Map<String, Long> versions,
            Client c) {
        if (turns == null) {
            return List.of();
        }
        List<TextMessage> out = new ArrayList<>();
        for (JsonNode turn : turns) {
            if (!controls(turn.path("controller"), c)) {
                continue;
            }
            String entity = turn.path("entity").asText();
            ObjectNode frame = json.createObjectNode();
            frame.put("type", "turn");
            frame.put("area", area);
            frame.put("entity", entity);
            Long version = versions.get(entity);
            if (version != null) {
                frame.put("version", version.longValue());
            }
            frame.set("since", turn.path("since"));
            out.add(new TextMessage(frame.toString()));
        }
        return out;
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
