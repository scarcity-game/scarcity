package com.pessimisticit.scarcity.orbital;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * orbital's JWT from auth ({@code POST /api/auth/service-token} with {@code X-Api-Key};
 * root AGENTS.md, "Auth"). Fetched when first needed and again once two thirds of its
 * lifetime have passed, or after {@link #renew()}: no timer runs. The key and the token
 * are never logged.
 */
@Component
public class ServiceToken {

    /** The header auth reads a service principal's key from. */
    public static final String API_KEY_HEADER = "X-Api-Key";
    static final String PATH = "/api/auth/service-token";

    private static final Logger LOG = LoggerFactory.getLogger(ServiceToken.class);

    private final RestClient http;
    private final Clock clock;
    private String token;
    private Instant renewAt;

    public ServiceToken(RestClient.Builder builder, OrbitalProperties orbitalProperties, Clock clock) {
        this.renewAt = Instant.MIN;
        this.http = builder.clone().baseUrl(orbitalProperties.authUrl().toString())
                .defaultHeader(API_KEY_HEADER, orbitalProperties.apiKey())
                .build();
        this.clock = clock;
    }

    /**
     * A token that has at least a third of its lifetime left.
     *
     * @throws Refused if auth refuses the key
     * @throws org.springframework.web.client.ResourceAccessException if auth doesn't answer
     */
    public synchronized String current() {
        if (token == null || !clock.instant().isBefore(renewAt)) {
            fetch();
        }
        return token;
    }

    /** Drops the token (a 401 or a {@code 4401}): the next {@link #current()} fetches one. */
    public synchronized void renew() {
        token = null;
    }

    private void fetch() {
        Instant asked = clock.instant();
        JsonNode body = http.post().uri(PATH)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    int status = response.getStatusCode().value();
                    if (status != 200) {
                        throw new Refused(status);
                    }
                    return WorldJson.fromJson(text, JsonNode.class);
                });
        Duration lifetime = Duration.ofSeconds(body.path("expiresIn").asLong());
        token = body.path("token").asText();
        renewAt = asked.plus(lifetime.multipliedBy(2).dividedBy(3));
        LOG.info("service token for {}, renewed after {}", lifetime, renewAt);
    }

    /** auth answered the exchange with something other than 200 (401: the key is wrong). */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        Refused(int status) {
            super("auth refused the service token: " + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
