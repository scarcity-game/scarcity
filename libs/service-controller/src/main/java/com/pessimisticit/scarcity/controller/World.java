package com.pessimisticit.scarcity.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * world's HTTP API as a controller uses it: read an entity or an area, post an intent. Every call sends
 * the controller's service token as {@code Authorization: Bearer} (never logged); a 401 drops it,
 * so the next call fetches a new one. A non-2xx answer is a {@link WorldStatus}; a network
 * failure is Spring's {@code ResourceAccessException}.
 */
@Component
public class World {

    private static final int UNAUTHORIZED = 401;
    /** Entities read from an area at most: the API's largest page. */
    static final int PAGE = 200;

    private final RestClient http;
    private final ServiceToken serviceToken;

    /**
     * Built from Boot's builder, so calls are observed and carry {@code traceparent}.
     *
     * <p>Token rotation: the {@code Authorization} header is set per request, not once on the
     * client. Each request asks {@link ServiceToken#current()}, which fetches a new token from
     * auth once two thirds of the old one's lifetime have passed (10 of 15 min), so a request
     * never goes out with a token near its {@code exp}. A 401 drops the token, so the next
     * request fetches one too.
     */
    public World(RestClient.Builder builder, ControllerProperties controllerProperties, ServiceToken serviceToken) {
        this.serviceToken = serviceToken;
        this.http = builder.clone().baseUrl(controllerProperties.worldUrl().toString())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(serviceToken.current());
                    return execution.execute(request, body);
                })
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** The entity {@code id} as world holds it now. */
    public Entity entity(String id) {
        JsonNode body = http.get().uri("/api/world/resources/{id}", id)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
        return WorldJson.fromJson(body, Entity.class);
    }

    /** The area {@code id} as world holds it now (its size, its time, who is up). */
    public Area area(String id) {
        JsonNode body = http.get().uri("/api/world/resources/{id}", id)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
        return WorldJson.fromJson(body, Area.class);
    }

    /**
     * The entities in the area {@code id} now: {@code GET /api/world/resources/{id}/children?type=entity},
     * the first {@value #PAGE} of them (a level holds a handful).
     */
    public List<Entity> entities(String id) {
        JsonNode body = http.get().uri("/api/world/resources/{id}/children?type=entity&size={size}", id, PAGE)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
        List<Entity> entities = new ArrayList<>();
        body.path("items").forEach(item -> entities.add(WorldJson.fromJson(item, Entity.class)));
        return List.copyOf(entities);
    }

    /** Posts an intent; returns world's 200 body ({@code area, time, logSeq, version}). */
    public JsonNode post(Intent intent) {
        return http.post().uri("/api/world/intents")
                .contentType(MediaType.APPLICATION_JSON)
                .body(WorldJson.toJson(intent))
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
    }

    private JsonNode read(int status, InputStream body) throws IOException {
        String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        if (status == UNAUTHORIZED) {
            serviceToken.renew();
        }
        if (status / 100 != 2) {
            throw new WorldStatus(status, errorOf(text));
        }
        return WorldJson.fromJson(text, JsonNode.class);
    }

    /** world's {@code {"error": "…"}}, or the status alone (401s have no body). */
    private static String errorOf(String text) {
        if (text.isBlank()) {
            return "";
        }
        try {
            return WorldJson.fromJson(text, JsonNode.class).path("error").asText(text);
        } catch (RuntimeException notJson) {
            return text;
        }
    }

    /**
     * {@code POST /api/world/intents}'s body (docs/actions.md, "Intents").
     *
     * @param ifVersion the entity version of the turn
     * @param reported  why the controller chose it: the draw and the names it picked from
     */
    public record Intent(String entity, ActionSpec actionSpec, long ifVersion, ObjectNode reported) {
    }

    /** world answered with a status other than 2xx. */
    public static final class WorldStatus extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String error;

        WorldStatus(int status, String error) {
            super(status + (error.isEmpty() ? "" : ": " + error));
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        /** world's error message, without the status; empty if it sent none. */
        public String error() {
            return error;
        }
    }
}
