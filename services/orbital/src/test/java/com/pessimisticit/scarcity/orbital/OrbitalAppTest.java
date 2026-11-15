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

/** The wired service against a stub world: health, metrics, traceparent on outgoing calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "orbital.interval=100ms",
        "orbital.seed=1",
        "orbital.api-key=" + Fixtures.KEY})
@AutoConfigureObservability
@DirtiesContext // its loop would keep playing against the closed stub while later tests run
class OrbitalAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final StubWorld WORLD;

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
        r.add("orbital.world-url", WORLD::url);
    }

    @AfterAll
    static void stop() {
        WORLD.close();
    }

    @Test
    void worldHealthIsUpOnceAnIntentIsAccepted() throws Exception {
        JsonNode world = eventually(() -> health().path("components").path("world"),
                w -> w.path("status").asText().equals("UP"));
        assertThat(world.path("details").path("window").asText()).isEqualTo("PT10S");
        assertThat(world.path("details").path("lastAcceptedIntent").asText()).isNotEqualTo("never");
    }

    @Test
    void prometheusCountsIntentsByOutcome() throws Exception {
        String metrics = eventually(() -> get("/actuator/prometheus"),
                m -> m.contains("orbital_intents_total{application=\"orbital\",outcome=\"ok\"}"));
        assertThat(metrics).contains("outcome=\"stale\"", "outcome=\"unreachable\"");
    }

    @Test
    void aTurnsCallsShareOneTrace() throws Exception {
        eventually(WORLD::posts, posts -> posts.size() >= 3);
        List<Request> requests = List.copyOf(WORLD.requests);
        int previousPost = -1;
        int checked = 0;
        for (int i = 0; i < requests.size(); i++) {
            if (!requests.get(i).method().equals("POST")) {
                continue;
            }
            // A turn: the reads since the previous intent, then this intent.
            String turn = traceId(requests.get(i));
            for (int j = previousPost + 1; j < i; j++) {
                assertThat(traceId(requests.get(j))).as("request %d of the turn ending at %d", j, i).isEqualTo(turn);
            }
            if (previousPost >= 0) {
                assertThat(traceId(requests.get(previousPost))).as("the previous turn").isNotEqualTo(turn);
            }
            previousPost = i;
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(3);
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
