package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pessimisticit.scarcity.controller.ServiceProcess;
import com.pessimisticit.scarcity.controller.ServiceToken;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * diver in-process against the real auth, world, subscriptions and microbe (each from its jar, in
 * its own JVM) on one Testcontainers Postgres, world seeded with the dive game, every level of it
 * generated from world's seed (#311). With no area configured and no pause, the diver must find
 * its way round the walls to the stairs down, level after level, down to depth {@link #DEPTH},
 * with no intent of its rejected.
 */
class PlaysTheDiveTest {

    /** libs/world-seed {@code WorldIds}: the dive game and its depth 0. */
    private static final String DIVE_GAME = "game:00000000-0000-4000-8000-000000000011";
    private static final String DIVE_AREA = "area:00000000-0000-4000-8000-000000000012";
    /** How deep the diver must get: three generated levels walked through. */
    private static final int DEPTH = 3;
    /** world's service principal key at auth (#374). */
    private static final String WORLD_KEY = "world-test-key-0123456789";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
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
        Files.writeString(keys.resolve("diver"), Fixtures.KEY + "\n");
        // world's own key (#374): it exchanges it for the token it calls microbe with.
        Files.writeString(keys.resolve("world"), WORLD_KEY + "\n");

        auth = start("auth", db, Map.of("AUTH_SERVICE_KEYS_DIR", keys.toString(),
                "AUTH_PASSWORD_BCRYPT_STRENGTH", "10"));
        auth.awaitReady();
        String jwks = auth.url + "/api/auth/.well-known/jwks.json";
        // An intelligence service: it needs only auth's keys, and is up before world, which is told its address.
        ServiceProcess microbe = start("microbe", Map.of(), Map.of("AUTH_JWKS_URI", jwks, "MICROBE_SEED", "1"));
        microbe.awaitReady();
        world = start("world", db, Map.of("AUTH_JWKS_URI", jwks, "WORLD_RANDOM_SEED", "311",
                "WORLD_INTELLIGENCE_SERVICES_MICROBE_BASEURL", microbe.url,
                "WORLD_INTELLIGENCE_SERVICES_MICROBE_RANGE", "8.0",
                "AUTH_URL", auth.url, "WORLD_API_KEY", WORLD_KEY));
        world.awaitReady();
        // A failed turn is played again on the next checksum: 1 s here, not 30 s.
        subscriptions = start("subscriptions", db, Map.of("AUTH_JWKS_URI", jwks, "WORLD_URL", world.url,
                "SUBSCRIPTIONS_CHECKSUM_INTERVAL", "1s"));
        subscriptions.awaitReady();
    }

    private static String socket() {
        return subscriptions.url.replace("http:", "ws:") + "/api/subscriptions/ws";
    }

    private static ServiceProcess start(String name, Map<String, String> db, Map<String, String> env)
            throws IOException {
        Map<String, String> all = new HashMap<>(db);
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
    void itFindsTheStairsDownLevelAfterLevel() throws Exception {
        String token = serviceToken(Fixtures.KEY);
        try (ConfigurableApplicationContext diver = new SpringApplicationBuilder(DiverApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--controller.auth-url=" + auth.url,
                "--controller.world-url=" + world.url,
                "--controller.subscriptions-url=" + socket(),
                "--controller.api-key=" + Fixtures.KEY,
                "--controller.think=0s",
                "--diver.arrival-pause=0s",
                "--controller.seed=1")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
            Optional<JsonNode> below = Optional.empty();
            while (System.nanoTime() < deadline && below.isEmpty()) {
                Thread.sleep(250);
                below = levelWithTheDiver(token, DEPTH);
            }
            assertThat(below).as("the diver at depth %d after 150 s\n%s\n%s", DEPTH, world.tail(),
                    SERVICES.getLast().tail()).isPresent();

            List<JsonNode> intents = entries(read("/api/world/log?area=" + DIVE_AREA + "&kind=intent", token))
                    .stream().filter(e -> Fixtures.DIVER.equals(e.path("resource").asText())).toList();
            List<String> types = intents.stream().map(e -> e.path("actionSpec").path("type").asText()).toList();
            assertThat(types).as("steps to the stairs, then down them").last().isEqualTo("traverse");
            assertThat(types.subList(0, types.size() - 1)).isNotEmpty().containsOnly("step");
            MeterRegistry meters = diver.getBean(MeterRegistry.class);
            assertThat(meters.get("diver.intents").tag("outcome", "invalid").counter().count()).isZero();
        }
    }

    /** The area of the dive game at {@code depth} if the diver itself (not its placeholder) is in it. */
    private static Optional<JsonNode> levelWithTheDiver(String token, int depth) throws Exception {
        for (JsonNode area : read("/api/world/resources/" + DIVE_GAME + "/children?type=area", token).path("items")) {
            if (area.path("depth").asInt() != depth) {
                continue;
            }
            if (holdsTheDiver(area.path("id").asText(), token)) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    private static String serviceToken(String key) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(auth.url + ServiceToken.PATH))
                .header(ServiceToken.API_KEY_HEADER, key).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("token").asText();
    }

    private static List<JsonNode> entries(JsonNode log) {
        List<JsonNode> entries = new ArrayList<>();
        log.path("entries").forEach(entries::add);
        return entries;
    }

    /** Whether the diver is among the area's entities, on any page: a generated level has a wall per cell. */
    private static boolean holdsTheDiver(String areaId, String token) throws Exception {
        for (int page = 0; ; page++) {
            JsonNode p = read("/api/world/resources/" + areaId + "/children?type=entity&page=" + page, token);
            for (JsonNode e : p.path("items")) {
                if (Fixtures.DIVER.equals(e.path("id").asText()) && "diver".equals(e.path("prototype").asText())) {
                    return true;
                }
            }
            if ((long) (page + 1) * p.path("size").asLong() >= p.path("total").asLong()) {
                return false;
            }
        }
    }

    private static JsonNode read(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(world.url + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
