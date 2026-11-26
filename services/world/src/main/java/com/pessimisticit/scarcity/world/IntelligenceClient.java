package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.model.TurnAnswer;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Calls an intelligence service's {@code POST /turn} with world's service token (docs/actions.md,
 * "Intelligence services"). A 401 renews the token and tries once more; any other failure is a
 * {@link Failed} for the dispatcher to retry later.
 */
class IntelligenceClient {

    private static final int UNAUTHORIZED = 401;

    private final Map<String, RestClient> clients;
    private final WorldServiceToken worldServiceToken;

    IntelligenceClient(RestClient.Builder builder, IntelligenceProperties intelligenceProperties,
            WorldServiceToken worldServiceToken) {
        this.worldServiceToken = worldServiceToken;
        this.clients = new TreeMap<>();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(intelligenceProperties.timeout());
        factory.setReadTimeout(intelligenceProperties.timeout());
        intelligenceProperties.services().forEach((principal, service) -> clients.put(principal,
                builder.clone().requestFactory(factory).baseUrl(service.baseUrl().toString()).build()));
    }

    /**
     * The service's answer to {@code request}.
     *
     * @throws Failed if it doesn't give one: an error status, a body that isn't an answer, or no answer
     */
    TurnAnswer turn(String principal, TurnRequest request) {
        RestClient client = clients.get(principal);
        if (client == null) {
            throw new Failed(principal + " is not a registered intelligence service");
        }
        try {
            Reply reply = post(client, request);
            if (reply.status() == UNAUTHORIZED) {
                worldServiceToken.renew();
                reply = post(client, request);
            }
            if (reply.status() != 200) {
                throw new Failed(principal + " answered " + reply.status() + ": " + abbreviate(reply.body()));
            }
            return WorldJson.fromJson(reply.body(), TurnAnswer.class);
        } catch (Failed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Failed(principal + ": " + e.getMessage(), e);
        }
    }

    private Reply post(RestClient client, TurnRequest request) {
        return client.post().uri("/turn")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + worldServiceToken.current())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(WorldJson.toJson(request))
                .exchange((req, response) -> new Reply(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static String abbreviate(String body) {
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    private record Reply(int status, String body) {
    }

    /** The service gave no usable answer; the turn is retried. */
    static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failed(String message) {
            super(message);
        }

        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
