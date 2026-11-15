package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.orbital.StubWorld.Request;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The wired service against a stub auth, world and subscriptions: a turn frame becomes one
 * intent after the thinking time, in one trace; health and metrics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "orbital.think=100ms",
        "orbital.seed=1",
        "orbital.api-key=" + Fixtures.KEY})
@AutoConfigureObservability
@DirtiesContext // its socket would keep reconnecting to the closed stubs while later tests run
class OrbitalAppTest {

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
        r.add("orbital.auth-url", WORLD::url);
        r.add("orbital.world-url", WORLD::url);
        r.add("orbital.subscriptions-url", SUBSCRIPTIONS::url);
    }

    @AfterAll
    static void stop() throws Exception {
        SUBSCRIPTIONS.close();
        WORLD.close();
    }

    @Test
    void aTurnBecomesOneIntentAfterThinkingInOneTrace() throws Exception {
        eventually(this::health, h -> h.path("components").path("subscription").path("status").asText().equals("UP"));
        assertThat(WORLD.worldRequests()).as("nothing is played before a turn").isEmpty();

        WORLD.entity(StubWorld.ORBITER, 7, StubWorld.IDLE_ORBITER);
        long sent = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            SUBSCRIPTIONS.sendTurn(StubWorld.ORBITER, 7);
        }
        eventually(WORLD::posts, posts -> !posts.isEmpty());
        Thread.sleep(300);
        assertThat(WORLD.posts()).hasSize(1);
        Request post = WORLD.posts().getFirst();
        assertThat(Duration.ofNanos(System.nanoTime() - sent)).isGreaterThan(Duration.ofMillis(100));
        assertThat(JSON.readTree(post.body()).path("ifVersion").asLong()).isEqualTo(7);
        assertThat(post.header("Authorization")).isEqualTo("Bearer token-1");

        List<Request> turn = WORLD.worldRequests();
        assertThat(turn).extracting(Request::method).first().isEqualTo("GET");
        String trace = traceId(post);
        assertThat(turn).allSatisfy(r -> assertThat(traceId(r)).isEqualTo(trace));

        JsonNode subscription = health().path("components").path("subscription");
        assertThat(subscription.path("details").path("lastAcceptedIntent").asText()).isNotEqualTo("never");
        assertThat(get("/actuator/prometheus"))
                .contains("orbital_intents_total{application=\"orbital\",outcome=\"ok\"} 1.0");
    }

    private static String traceId(Request request) {
        String traceparent = request.header("traceparent");
        assertThat(traceparent).as("traceparent on %s %s", request.method(), request.path())
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        return traceparent.split("-")[1];
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

    private static <T> T eventually(Call<T> call, java.util.function.Predicate<T> done) throws Exception {
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
