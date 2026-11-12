package com.pessimisticit.scarcity.subscriptions;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ResourceIds;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

/**
 * The WebSocket protocol (root AGENTS.md, "WebSocket protocol"). Frames of one session arrive
 * sequentially on one container thread, so per-client state needs no locking beyond volatiles.
 */
@Component
public class SubscriptionsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsHandler.class);

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final SubscriptionsProperties props;
    private final JwtDecoder jwtDecoder;
    private final AreaRegistry registry;
    private final TaskScheduler scheduler;
    private final ObjectMapper json;

    SubscriptionsHandler(SubscriptionsProperties props, JwtDecoder jwtDecoder, AreaRegistry registry,
            TaskScheduler scheduler, ObjectMapper json) {
        this.props = props;
        this.jwtDecoder = jwtDecoder;
        this.registry = registry;
        this.scheduler = scheduler;
        this.json = json;
    }

    Collection<Client> clients() {
        return clients.values();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Client c = new Client(session, props);
        clients.put(session.getId(), c);
        c.authTimer = scheduler.schedule(() -> {
            if (!c.authed) {
                c.close(Client.CLOSE_UNAUTHORIZED, "auth timeout");
            }
        }, Instant.now().plus(props.getAuthTimeout()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client c = clients.get(session.getId());
        if (c == null) {
            return;
        }
        JsonNode frame = parse(message.getPayload());
        String type = frame != null && frame.path("type").isTextual() ? frame.get("type").asText() : null;
        if (!c.authed) {
            if (!"auth".equals(type)) {
                c.close(Client.CLOSE_BAD_FRAME, "auth frame required");
                return;
            }
            authenticate(c, frame);
            return;
        }
        if (frame == null) {
            error(c, "malformed frame");
            return;
        }
        try {
            switch (type == null ? "" : type) {
                case "ping" -> c.send(text("{\"type\":\"pong\"}"));
                case "subscribe" -> subscribe(c, frame);
                case "unsubscribe" -> unsubscribe(c, frame);
                default -> error(c, "unknown type");
            }
        } catch (RuntimeException e) {
            log.warn("frame handling failed for {}", c.id(), e);
            error(c, "internal error");
        }
    }

    private void authenticate(Client c, JsonNode frame) {
        JsonNode t = frame.get("token");
        if (t == null || !t.isTextual() || t.asText().isEmpty()) {
            c.close(Client.CLOSE_UNAUTHORIZED, "invalid token");
            return;
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(t.asText());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("rejected token for {}: {}", c.id(), e.toString());
            c.close(Client.CLOSE_UNAUTHORIZED, "invalid token");
            return;
        }
        Instant exp = jwt.getExpiresAt();
        // The decoder tolerates 60 s of clock skew; this service doesn't.
        if (exp == null || !exp.isAfter(Instant.now()) || jwt.getSubject() == null) {
            c.close(Client.CLOSE_UNAUTHORIZED, "invalid token");
            return;
        }
        c.user = jwt.getSubject();
        c.username = jwt.getClaimAsString("username");
        c.token = t.asText();
        c.authed = true;
        c.expiryTimer = scheduler.schedule(() -> c.close(Client.CLOSE_UNAUTHORIZED, "token expired"), exp);
        ObjectNode ready = json.createObjectNode();
        ready.put("type", "ready");
        ready.put("user", c.user);
        ready.put("username", c.username);
        c.send(text(ready.toString()));
    }

    private void subscribe(Client c, JsonNode frame) {
        String area = areaOf(c, frame);
        if (area == null) {
            return;
        }
        try {
            registry.subscribe(c, area);
        } catch (WorldClient.WorldException e) {
            switch (e.status()) {
                case 404 -> error(c, "unknown area");
                case 401 -> c.close(Client.CLOSE_UNAUTHORIZED, "token rejected");
                default -> error(c, "could not load area: world answered " + e.status());
            }
        } catch (RestClientException e) {
            log.warn("world unreachable while subscribing {} to {}: {}", c.id(), area, e.toString());
            error(c, "could not load area: world unavailable");
        }
    }

    private void unsubscribe(Client c, JsonNode frame) {
        String area = areaOf(c, frame);
        if (area == null) {
            return;
        }
        registry.unsubscribe(c, area);
        ObjectNode out = json.createObjectNode();
        out.put("type", "unsubscribed");
        out.put("area", area);
        c.send(text(out.toString()));
    }

    /** The frame's area id if it is a well-formed {@code area:...} id; else an error is sent and null returned. */
    private String areaOf(Client c, JsonNode frame) {
        JsonNode a = frame.get("area");
        if (a == null || !a.isTextual() || !ResourceIds.matches(ResourceType.AREA, a.asText())) {
            error(c, "invalid area id");
            return null;
        }
        return a.asText();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client c = clients.remove(session.getId());
        if (c != null) {
            c.markClosed();
            c.cancelTimers();
            registry.remove(c);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error on {}: {}", session.getId(), exception.toString());
    }

    private JsonNode parse(String payload) {
        try {
            JsonNode n = json.readTree(payload);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void error(Client c, String message) {
        ObjectNode out = json.createObjectNode();
        out.put("type", "error");
        out.put("message", message);
        c.send(text(out.toString()));
    }

    private static TextMessage text(String s) {
        return new TextMessage(s);
    }
}
