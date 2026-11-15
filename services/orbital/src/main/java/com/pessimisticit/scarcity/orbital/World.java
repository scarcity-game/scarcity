package com.pessimisticit.scarcity.orbital;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * world's HTTP API as orbital uses it: read an entity, post an intent. Every call sends
 * {@code X-Api-Key} (set on the client, never logged). A non-2xx answer is a
 * {@link WorldStatus}; a network failure is Spring's {@code ResourceAccessException}.
 */
public class World {

    /** The header world reads a service principal's key from. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    private final RestClient http;

    /** {@code http} carries the base URL and the {@code X-Api-Key} header. */
    public World(RestClient http) {
        this.http = http;
    }

    /** The entity {@code id} as world holds it now. */
    public Entity entity(String id) {
        JsonNode body = http.get().uri("/api/world/resources/{id}", id)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
        return new Entity(body.path("id").asText(), body.path("version").asLong(),
                WorldJson.fromJson(body.path("state"), EntityState.class, "state of " + id));
    }

    /** Posts an intent; returns world's 200 body ({@code area, time, logSeq, version}). */
    public JsonNode post(Intent intent) {
        return http.post().uri("/api/world/intents")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(WorldJson.toJson(intent))
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
    }

    private static JsonNode read(int status, InputStream body) throws IOException {
        String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
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
     * @param ifVersion the entity version the choice was made on
     * @param reported  why orbital chose it: the draw and the names it picked from
     */
    public record Intent(String entity, ActionSpec action, long ifVersion, ObjectNode reported) {
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
