package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.model.WorldJson;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * world's own service token (principal {@code world}, #374), used only to call intelligence
 * services: auth's {@code POST /api/auth/service-token} with {@code X-Api-Key} (root AGENTS.md,
 * "Auth"). Fetched when first needed and again once two thirds of its lifetime have passed, or after
 * {@link #renew()}. The key and the token are never logged.
 */
class WorldServiceToken {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String PATH = "/api/auth/service-token";

    private static final Logger LOG = LoggerFactory.getLogger(WorldServiceToken.class);

    private final RestClient http;
    private final Clock clock;
    private String token;
    private Instant renewAt;

    WorldServiceToken(RestClient.Builder builder, IntelligenceProperties intelligenceProperties, Clock clock) {
        this.http = builder.clone().baseUrl(String.valueOf(intelligenceProperties.authUrl()))
                .defaultHeader(API_KEY_HEADER, intelligenceProperties.apiKey())
                .build();
        this.clock = clock;
        this.renewAt = Instant.MIN;
    }

    /**
     * A token with at least a third of its lifetime left.
     *
     * @throws IllegalStateException if auth refuses the key
     * @throws org.springframework.web.client.ResourceAccessException if auth doesn't answer
     */
    synchronized String current() {
        if (token == null || !clock.instant().isBefore(renewAt)) {
            fetch();
        }
        return token;
    }

    /** Drops the token (a service answered 401): the next {@link #current()} fetches one. */
    synchronized void renew() {
        token = null;
    }

    private void fetch() {
        Instant asked = clock.instant();
        JsonNode body = http.post().uri(PATH).accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    int status = response.getStatusCode().value();
                    if (status != 200) {
                        throw new IllegalStateException("auth refused world's service token: " + status);
                    }
                    return WorldJson.fromJson(text, JsonNode.class);
                });
        Duration lifetime = Duration.ofSeconds(body.path("expiresIn").asLong());
        token = body.path("token").asText();
        renewAt = asked.plus(lifetime.multipliedBy(2).dividedBy(3));
        LOG.info("world's service token for {}, renewed after {}", lifetime, renewAt);
    }
}
