package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.controller.StubSubscriptions;
import com.pessimisticit.scarcity.controller.StubWorld;
import com.pessimisticit.scarcity.world.model.Area;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The wired service against a stub auth, world and subscriptions: it subscribes to the turn
 * feed, and the diver's turn becomes one step toward the stairs down, the only open choice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "controller.seed=1",
        "controller.think=0s",
        "diver.arrival-pause=0s",
        "controller.api-key=" + StubWorld.KEY})
@AutoConfigureMetrics
@AutoConfigureTracing
@DirtiesContext // its socket would keep reconnecting to the closed stubs while later tests run
class DiverAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final StubWorld WORLD;
    private static final StubSubscriptions SUBSCRIPTIONS = new StubSubscriptions();

    static {
        try {
            WORLD = new StubWorld();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalManagementPort
    int managementPort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("controller.auth-url", WORLD::url);
        r.add("controller.world-url", WORLD::url);
        r.add("controller.subscriptions-url", SUBSCRIPTIONS::url);
    }

    @AfterAll
    static void stop() throws Exception {
        SUBSCRIPTIONS.close();
        WORLD.close();
    }

    @Test
    void itPlaysTheDiverFromTheFeedTowardTheStairsDown() throws Exception {
        eventually(this::health, h -> h.path("components").path("subscription").path("status").asText().equals("UP"));
        assertThat(SUBSCRIPTIONS.received).contains("{\"type\":\"subscribe\",\"turns\":true}");

        WORLD.area(new Area("level", 3.0).withId(StubWorld.AREA).withVersion(2));
        WORLD.entity(Fixtures.DIVER, 5, Fixtures.diver(4.0, 4.0));
        WORLD.entity(Fixtures.STAIRS_UP, 1, Fixtures.stairs("stairs-up", 5.0, 4.0));
        WORLD.entity(Fixtures.STAIRS_DOWN, 1, Fixtures.stairs("stairs-down", 1.5, 1.5));
        WORLD.children(StubWorld.AREA, List.of(Fixtures.STAIRS_UP, Fixtures.STAIRS_DOWN, Fixtures.DIVER));
        SUBSCRIPTIONS.sendTurn(Fixtures.DIVER, 5);

        eventually(WORLD::posts, posts -> !posts.isEmpty());
        JsonNode intent = JSON.readTree(WORLD.posts().getFirst().body());
        assertThat(intent.path("entity").asText()).isEqualTo(Fixtures.DIVER);
        assertThat(intent.path("ifVersion").asLong()).isEqualTo(5);
        assertThat(intent.path("actionSpec").path("type").asText()).isEqualTo("step");
        JsonNode to = intent.path("actionSpec").path("params").path("to");
        assertThat(to.path("x").asDouble()).as("toward (1.5, 1.5)").isLessThan(4.0);
        assertThat(to.path("y").asDouble()).isLessThan(4.0);
        assertThat(intent.path("reported").path("choices").toString()).isEqualTo("[\"step\"]");
        assertThat(get("/actuator/prometheus"))
                .contains("diver_intents_total{application=\"diver\",outcome=\"ok\"} 1.0");
    }

    private JsonNode health() throws IOException, InterruptedException {
        return JSON.readTree(get("/actuator/health"));
    }

    private String get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    interface Call<T> {
        T get() throws Exception;
    }

    private static <T> T eventually(Call<T> call, Predicate<T> done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        T value = call.get();
        while (!done.test(value) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            value = call.get();
        }
        assertThat(done.test(value)).as("eventually: %s", value).isTrue();
        return value;
    }
}
