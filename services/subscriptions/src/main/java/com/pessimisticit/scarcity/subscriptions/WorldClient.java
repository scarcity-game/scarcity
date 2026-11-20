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
 * Reads an area's versions and who is the {@code activeEntity} from world's resource API with the subscribing
 * user's JWT: the area and all pages of its children (entities). Items under entities are not
 * fetched yet.
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
     * What a subscription starts from: every resource's version, and the area's {@code activeEntity}
     * (null when nobody is) at {@code areaVersion}.
     */
    public record Loaded(Map<String, Long> versions, long areaVersion, JsonNode activeEntity) {
    }

    public Loaded fetch(String area, String token) {
        Map<String, Long> versions = new HashMap<>();
        long areaVersion;
        JsonNode activeEntity;
        try {
            JsonNode a = get("/api/world/resources/" + area, token);
            areaVersion = a.path("version").asLong();
            activeEntity = a.get("activeEntity");
            versions.put(a.path("id").asText(area), areaVersion);
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
        return new Loaded(versions, areaVersion, activeEntity);
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
