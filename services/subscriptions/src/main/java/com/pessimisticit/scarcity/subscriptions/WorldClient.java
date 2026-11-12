package com.pessimisticit.scarcity.subscriptions;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads an area's versions from world's resource API with the subscribing user's JWT: the area
 * and all pages of its children (entities). Items under entities are not fetched yet.
 */
@Component
public class WorldClient {

    private static final int PAGE_SIZE = 200;
    private final RestClient rest;

    WorldClient(RestClient.Builder builder, SubscriptionsProperties props) {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        this.rest = builder.requestFactory(f).baseUrl(props.getWorldUrl()).build();
    }

    /** world answered with a non-2xx status (or something unusable: 502). */
    public static class WorldException extends RuntimeException {
        private final int status;

        WorldException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Map<String, Long> fetchVersions(String area, String token) {
        Map<String, Long> versions = new HashMap<>();
        try {
            JsonNode a = get("/api/world/resources/" + area, token);
            versions.put(a.path("id").asText(area), a.path("version").asLong());
            int page = 0;
            while (true) {
                JsonNode p = get("/api/world/resources/" + area + "/children?size=" + PAGE_SIZE + "&page=" + page,
                        token);
                JsonNode items = p.path("items");
                for (JsonNode r : items) {
                    versions.put(r.path("id").asText(), r.path("version").asLong());
                }
                page++;
                if (items.isEmpty() || (long) page * p.path("size").asInt(PAGE_SIZE) >= p.path("total").asLong()) {
                    break;
                }
            }
        } catch (RestClientResponseException e) {
            throw new WorldException(e.getStatusCode().value(), "world answered " + e.getStatusCode().value(), e);
        }
        return versions;
    }

    private JsonNode get(String uri, String token) {
        JsonNode n = rest.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
        if (n == null) {
            throw new WorldException(502, "empty answer from world", null);
        }
        return n;
    }
}
