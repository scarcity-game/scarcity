package com.pessimisticit.scarcity.microbe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * microbe in-process against the real auth, world and subscriptions (each from its jar, in its
 * own JVM) on one Testcontainers Postgres, world seeded with the dive game. The test plays the
 * diver, an npc that keeps the area running (#190), with a wait whenever it is up; microbe must
 * hear the mouse's turns over the turn feed, with no area configured, and move it.
 */
class PlaysTheMiceTest {

    /** libs/world-seed {@code WorldIds}: the dive game's depth 0, its mouse and its diver. */
    private static final String DIVE_AREA = "area:00000000-0000-4000-8000-000000000012";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000016";
    private static final String DIVER = "entity:00000000-0000-4000-8000-000000000015";
    private static final String DIVER_KEY = "diver-test-key-0b6e1f";

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
        Files.writeString(keys.resolve("microbe"), Fixtures.KEY + "\n");
        Files.writeString(keys.resolve("diver"), DIVER_KEY + "\n");

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
    void itMovesTheMouseWhileTheDiverIsThere() throws Exception {
        String diver = serviceToken(DIVER_KEY);
        JsonNode start = read("/api/world/resources/" + MOUSE, diver).path("position");

        try (ConfigurableApplicationContext microbe = new SpringApplicationBuilder(MicrobeApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--controller.auth-url=" + auth.url,
                "--controller.world-url=" + world.url,
                "--controller.subscriptions-url=" + subscriptions.url.replace("http:", "ws:") + "/api/subscriptions/ws",
                "--controller.api-key=" + Fixtures.KEY,
                "--controller.seed=1")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<JsonNode> intents = List.of();
            while (System.nanoTime() < deadline && intents.size() < 3) {
                playTheDiver(diver);
                Thread.sleep(100);
                intents = entries(read("/api/world/log?area=" + DIVE_AREA + "&kind=intent", diver)).stream()
                        .filter(e -> MOUSE.equals(e.path("resource").asText())).toList();
            }
            assertThat(intents).as("the mouse's intents after 30 s\n%s\n%s", subscriptions.tail(), world.tail())
                    .hasSizeGreaterThanOrEqualTo(3)
                    .allSatisfy(entry -> {
                        assertThat(entry.path("controller").toString())
                                .isEqualTo("{\"kind\":\"service\",\"principal\":\"microbe\"}");
                        assertThat(entry.path("reported").path("draw").path("dist").asText()).isEqualTo("categorical");
                    });
            assertThat(intents).filteredOn(e -> "step".equals(e.path("actionSpec").path("type").asText()))
                    .allSatisfy(e -> assertThat(e.path("reported").path("plan").path("heading").path("dist").asText())
                            .isEqualTo("uniform"));
            MeterRegistry meters = microbe.getBean(MeterRegistry.class);
            assertThat(meters.get("microbe.intents").tag("outcome", "ok").counter().count()).isGreaterThanOrEqualTo(3);
            assertThat(meters.get("microbe.intents").tag("outcome", "invalid").counter().count()).isZero();
        }
        assertThat(start.isObject()).isTrue();
    }

    /** The diver waits one time unit whenever it is the active entity; a 409 (not up any more) is fine. */
    private static void playTheDiver(String token) throws Exception {
        JsonNode up = read("/api/world/resources/" + DIVE_AREA, token).path("activeEntity");
        if (!DIVER.equals(up.path("entity").asText())) {
            return;
        }
        String body = "{\"entity\":\"" + DIVER + "\",\"actionSpec\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(world.url + "/api/world/intents"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isIn(200, 409);
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

    private static JsonNode read(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(world.url + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
