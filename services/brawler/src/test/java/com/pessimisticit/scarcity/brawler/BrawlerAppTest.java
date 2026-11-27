package com.pessimisticit.scarcity.brawler;

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
 * goblin's turn with a strike at the beast beside it, under the turn's faction relations, and
 * refuses anyone else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "controller.seed=1"})
@AutoConfigureMetrics
@AutoConfigureTracing
class BrawlerAppTest {

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
    void itAnswersAGoblinsTurnWithAStrikeAtTheBeastBesideIt() throws Exception {
        HttpResponse<String> response = post(JWKS.worldToken(), turn());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode answer = WorldJson.readTree(response.body());
        assertThat(answer.path("actionSpec").path("type").asString()).isEqualTo("strike");
        assertThat(answer.path("actionSpec").path("params").path("target").asString()).isEqualTo(ChoicesTest.DOG);
        assertThat(answer.path("reported").path("choices").toString()).isEqualTo("[\"strike\"]");
        assertThat(get("/actuator/prometheus"))
                .contains("brawler_intents_total{application=\"brawler\",outcome=\"ok\"} 1.0");
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
        Entity goblin = ChoicesTest.goblin(ChoicesTest.SELF, 4.0, 4.0);
        Entity dog = ChoicesTest.dog(ChoicesTest.DOG, 4.6, 4.0);
        return WorldJson.toJson(new TurnRequest(ChoicesTest.AREA, ChoicesTest.SELF, 1, 3.0, 8.0, goblin, List.of(dog),
                List.of(), ChoicesTest.ARENA));
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
