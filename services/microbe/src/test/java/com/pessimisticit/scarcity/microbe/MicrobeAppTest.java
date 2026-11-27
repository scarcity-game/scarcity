package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.TestJwks;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The wired service, as world calls it: {@code POST /turn} with a token from a test JWKS answers a
 * mouse's turn with one step, its heading draw reported, and refuses anyone else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "controller.seed=1",
        "controller.weights.wait=0"})
@AutoConfigureMetrics
@AutoConfigureTracing
class MicrobeAppTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000016";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final TestJwks JWKS;

    static {
        try {
            JWKS = new TestJwks();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("controller.jwk-set-uri", JWKS::url);
    }

    @AfterAll
    static void stop() {
        JWKS.close();
    }

    @Test
    void itAnswersAMouseTurnWithAStepAndItsHeadingReported() throws Exception {
        HttpResponse<String> response = post(JWKS.worldToken(), turn());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode answer = WorldJson.readTree(response.body());
        assertThat(answer.path("actionSpec").path("type").asString()).isEqualTo("step");
        JsonNode heading = answer.path("reported").path("plan").path("heading.1");
        assertThat(heading.path("dist").asString()).isEqualTo("uniform");
        double angle = heading.path("value").asDouble();
        JsonNode to = answer.path("actionSpec").path("params").path("to");
        assertThat(to.path("x").asDouble()).isCloseTo(4.0 + Math.cos(angle), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(to.path("y").asDouble()).isCloseTo(4.0 + Math.sin(angle), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(answer.path("reported").path("choices").toString()).isEqualTo("[\"step\"]");
        assertThat(get("/actuator/prometheus"))
                .contains("microbe_intents_total{application=\"microbe\",outcome=\"ok\"} 1.0");
    }

    @Test
    void itAnswersOnlyWorld() throws Exception {
        assertThat(post(null, turn()).statusCode()).isEqualTo(401);
        assertThat(post(JWKS.token("orbital", "service", Instant.now().plusSeconds(60)), turn()).statusCode())
                .isEqualTo(403);
        assertThat(post(JWKS.token("world", null, Instant.now().plusSeconds(60)), turn()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void itHoldsNoSocketAndCallsNoOne() throws Exception {
        assertThat(get("/actuator/health")).doesNotContain("subscription");
    }

    private static String turn() {
        Entity mouse = WorldJson.fromJson(Fixtures.mouse(4.0, 4.0), Entity.class).withId(MOUSE);
        return WorldJson.toJson(new TurnRequest(AREA, MOUSE, 5, 3.0, 8.0, mouse, List.of(), List.of(), Map.of()));
    }

    private HttpResponse<String> post(String token, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/turn"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
