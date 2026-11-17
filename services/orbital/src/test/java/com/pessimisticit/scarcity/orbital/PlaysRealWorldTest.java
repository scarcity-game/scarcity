package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * orbital in-process against the real auth, world and subscriptions (each from its jar, in
 * its own JVM) on one Testcontainers Postgres, world seeded with the test area. orbital must
 * get its token from auth, be told its turns over subscriptions' WebSocket, and move the
 * orbiter through intents that world logs as orbital's, one per turn.
 */
class PlaysRealWorldTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));
    private static final List<ServiceProcess> SERVICES = new ArrayList<>();
    private static ServiceProcess auth;
    private static ServiceProcess world;
    private static ServiceProcess subscriptions;

    @BeforeAll
    static void startServices() throws Exception {
        POSTGRES.start();
        Map<String, String> db = Map.of(
                "SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        Path keys = Files.createTempDirectory("service-api-keys");
        Files.writeString(keys.resolve("orbital"), Fixtures.KEY + "\n");

        auth = start("auth", db, Map.of("AUTH_SERVICE_KEYS_DIR", keys.toString(),
                "AUTH_PASSWORD_BCRYPT_STRENGTH", "10"));
        auth.awaitReady();
        String jwks = auth.url + "/api/auth/.well-known/jwks.json";
        world = start("world", db, Map.of("AUTH_JWKS_URI", jwks));
        world.awaitReady();
        subscriptions = start("subscriptions", db, Map.of("AUTH_JWKS_URI", jwks, "WORLD_URL", world.url));
        subscriptions.awaitReady();
    }

    private static ServiceProcess start(String name, Map<String, String> db, Map<String, String> env)
            throws IOException {
        Map<String, String> all = new java.util.HashMap<>(db);
        all.putAll(env);
        ServiceProcess p = new ServiceProcess(name, all);
        SERVICES.add(p);
        return p;
    }

    @AfterAll
    static void stopServices() throws InterruptedException {
        for (ServiceProcess p : SERVICES.reversed()) {
            p.close();
        }
        POSTGRES.stop();
    }

    @Test
    void orbitalPlaysTheOrbitersTurnsThroughLoggedIntents() throws Exception {
        String token = serviceToken();
        JsonNode start = read("/api/world/resources/" + StubWorld.ORBITER, token).path("position");

        // Arguments, not builder properties: those are defaults, below application.yml.
        try (ConfigurableApplicationContext orbital = new SpringApplicationBuilder(OrbitalApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--orbital.auth-url=" + auth.url,
                "--orbital.world-url=" + world.url,
                "--orbital.subscriptions-url=" + subscriptions.url.replace("http:", "ws:") + "/api/subscriptions/ws",
                "--orbital.api-key=" + Fixtures.KEY,
                "--orbital.think=50ms",
                "--orbital.seed=1")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            List<JsonNode> intents = List.of();
            while (System.nanoTime() < deadline && intents.size() < 3) {
                Thread.sleep(200);
                intents = entries(read("/api/world/log?area=" + StubWorld.AREA + "&kind=intent", token));
            }
            assertThat(intents).as("intents after 20 s\n%s\n%s", subscriptions.tail(), world.tail())
                    .hasSizeGreaterThanOrEqualTo(3)
                    .allSatisfy(entry -> {
                        assertThat(entry.path("resource").asText()).isEqualTo(StubWorld.ORBITER);
                        assertThat(entry.path("controller").toString())
                                .isEqualTo("{\"kind\":\"service\",\"principal\":\"orbital\"}");
                        assertThat(entry.path("reported").path("draw").path("dist").asText()).isEqualTo("categorical");
                    });
            // One intent per turn: none was refused as stale or not up.
            MeterRegistry meters = orbital.getBean(MeterRegistry.class);
            assertThat(meters.get("orbital.intents").tag("outcome", "conflict").counter().count()).isZero();
            assertThat(meters.get("orbital.intents").tag("outcome", "ok").counter().count()).isGreaterThanOrEqualTo(3);
        }
        assertStepsMovedTheOrbiterExactlyWhereTheyStopped(start, token);
    }

    /**
     * A step is ray-cast and may stop at once, so movement is asserted from the {@code action-end}
     * results (docs/log.md, docs/action-types.md: {@code result} is {@code {to, reached, stoppedAt}}),
     * not from the intents. Each step starts where the previous one stopped.
     */
    private static void assertStepsMovedTheOrbiterExactlyWhereTheyStopped(JsonNode start, String token)
            throws Exception {
        JsonNode end;
        List<JsonNode> ends;
        do { // orbital is closed, but an action in flight may still end: read until the position is stable
            end = read("/api/world/resources/" + StubWorld.ORBITER, token).path("position");
            ends = entries(read("/api/world/log?area=" + StubWorld.AREA + "&kind=action-end", token)).stream()
                    .filter(e -> StubWorld.ORBITER.equals(e.path("resource").asText()))
                    .filter(e -> "step".equals(e.path("actionInProgress").path("type").asText()))
                    .toList();
        } while (!end.equals(read("/api/world/resources/" + StubWorld.ORBITER, token).path("position")));
        assertThat(ends).as("step results in the log").isNotEmpty();

        JsonNode at = start;
        boolean moved = false;
        for (JsonNode e : ends) {
            JsonNode stoppedAt = e.path("result").path("stoppedAt");
            assertThat(stoppedAt.isObject()).as("result of %s", e).isTrue();
            if (e.path("result").path("reached").asBoolean()) {
                assertThat(stoppedAt).as("a reached step ends at its target").isEqualTo(e.path("result").path("to"));
            }
            moved |= !stoppedAt.equals(at);
            at = stoppedAt;
        }
        // The orbiter is exactly where its last step stopped; it moved iff some step did.
        assertThat(end).isEqualTo(at);
        if (moved) {
            assertThat(end).isNotEqualTo(start);
        } else {
            assertThat(end).as("every step was blocked at once").isEqualTo(start);
        }
    }

    private static String serviceToken() throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(auth.url + ServiceToken.PATH))
                .header(ServiceToken.API_KEY_HEADER, Fixtures.KEY).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("token").asText();
    }

    private static List<JsonNode> entries(JsonNode log) {
        List<JsonNode> entries = new ArrayList<>();
        log.path("entries").forEach(entries::add);
        return entries;
    }

    private static JsonNode read(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(world.url + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
