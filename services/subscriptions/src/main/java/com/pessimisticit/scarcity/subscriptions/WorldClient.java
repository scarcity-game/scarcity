package com.pessimisticit.scarcity.subscriptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

/**
 * Reads, from world's resource API with the subscribing user's JWT, what a subscription starts from: an
 * area's versions ({@code GET …/{area}/versions}, everything under the area) and the area itself (for its
 * {@code activeEntity}); and, for the turn feed, every area.
 */
@Component
public class WorldClient {

    private static final int PAGE_SIZE = 200;
    private final RestClient rest;

    WorldClient(RestClient.Builder builder, SubscriptionsProperties subscriptionsProperties) {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) subscriptionsProperties.getWorldConnectTimeout().toMillis());
        f.setReadTimeout((int) subscriptionsProperties.getWorldReadTimeout().toMillis());
        this.rest = builder.requestFactory(f).baseUrl(subscriptionsProperties.getWorldUrl()).build();
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

    /**
     * What a subscription starts from: every resource's version, the area's log head ({@code logSeq}, null if
     * world didn't say), and the area's {@code activeEntity} (null when nobody is) at {@code areaVersion}.
     */
    public record Loaded(Map<String, Long> versions, Long logSeq, long areaVersion, JsonNode activeEntity) {
    }

    public Loaded fetch(String area, String token) {
        try {
            JsonNode v = get("/api/world/resources/" + area + "/versions", token);
            Map<String, Long> versions = new HashMap<>();
            v.path("versions").properties().forEach(e -> versions.put(e.getKey(), e.getValue().asLong()));
            Long logSeq = v.path("logSeq").isNumber() ? v.path("logSeq").asLong() : null;
            JsonNode a = area(area, token);
            long areaVersion = a.path("version").asLong();
            versions.merge(area, areaVersion, Math::max);
            return new Loaded(versions, logSeq, areaVersion, a.get("activeEntity"));
        } catch (RestClientResponseException e) {
            throw new WorldException(e.getStatusCode().value(), "world answered " + e.getStatusCode().value(), e);
        }
    }

    /** The area as world returns it. */
    public JsonNode area(String area, String token) {
        try {
            return get("/api/world/resources/" + area, token);
        } catch (RestClientResponseException e) {
            throw new WorldException(e.getStatusCode().value(), "world answered " + e.getStatusCode().value(), e);
        }
    }

    /** Every area world holds (all pages of {@code ?type=area}), as world returns them, for the turn feed. */
    public List<JsonNode> areas(String token) {
        List<JsonNode> out = new ArrayList<>();
        try {
            int page = 0;
            while (true) {
                JsonNode p = get("/api/world/resources?type=area&size=" + PAGE_SIZE + "&page=" + page, token);
                JsonNode items = p.path("items");
                items.forEach(out::add);
                page++;
                if (items.isEmpty() || (long) page * p.path("size").asInt(PAGE_SIZE) >= p.path("total").asLong()) {
                    return out;
                }
            }
        } catch (RestClientResponseException e) {
            throw new WorldException(e.getStatusCode().value(), "world answered " + e.getStatusCode().value(), e);
        }
    }

    private JsonNode get(String uri, String token) {
        JsonNode n = rest.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
        if (n == null) {
            throw new WorldException(502, "empty answer from world", null);
        }
        return n;
    }
}
