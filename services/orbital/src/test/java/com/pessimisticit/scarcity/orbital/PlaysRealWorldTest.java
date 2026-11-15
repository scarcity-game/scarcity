package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * orbital in-process against the real world: world's jar in its own JVM (so the two
 * services' {@code application.yml} and auto-configuration don't meet on one classpath)
 * on a Testcontainers Postgres, seeded with the test area. orbital must move the orbiter
 * through intents that world logs as orbital's.
 */
class PlaysRealWorldTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final Duration WORLD_STARTUP = Duration.ofSeconds(120);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));
    private static Process world;
    private static Path worldLog;
    private static String worldUrl;

    @BeforeAll
    static void startWorld() throws Exception {
        File jar = new File(System.getProperty("world.jar", "../world/target/scarcity-world.jar"));
        assertThat(jar).as("world's jar: build with -pl services/orbital -am, which packages world first").isFile();
        POSTGRES.start();
        Path keys = Files.createTempDirectory("world-api-keys");
        Files.writeString(keys.resolve("orbital"), Fixtures.KEY + "\n");
        int port = freePort();
        int management = freePort();
        worldUrl = "http://127.0.0.1:" + port;
        worldLog = Files.createTempFile("world-it", ".log");

        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--sun-misc-unsafe-memory-access=allow", "-jar", jar.getAbsolutePath());
        builder.environment().putAll(Map.of(
                "SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword(),
                "SERVER_PORT", Integer.toString(port),
                "MANAGEMENT_SERVER_PORT", Integer.toString(management),
                "SCARCITY_WORLD_API_KEYS_DIR", keys.toString()));
        builder.redirectErrorStream(true).redirectOutput(worldLog.toFile());
        world = builder.start();
        awaitReady("http://127.0.0.1:" + management + "/actuator/health/readiness");
    }

    @AfterAll
    static void stopWorld() throws InterruptedException {
        if (world != null) {
            world.destroy();
            if (!world.waitFor(20, TimeUnit.SECONDS)) {
                world.destroyForcibly();
            }
        }
        POSTGRES.stop();
    }

    @Test
    void orbitalMovesTheOrbiterThroughLoggedIntents() throws Exception {
        JsonNode before = read("/api/world/resources/" + StubWorld.ORBITER);
        JsonNode start = before.path("state").path("position");

        // Arguments, not builder properties: those are defaults, below application.yml.
        try (ConfigurableApplicationContext orbital = new SpringApplicationBuilder(OrbitalApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--orbital.world-url=" + worldUrl,
                "--orbital.api-key=" + Fixtures.KEY,
                "--orbital.interval=100ms",
                "--orbital.seed=1")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            JsonNode position = start;
            List<JsonNode> intents = List.of();
            while (System.nanoTime() < deadline && (position.equals(start) || intents.isEmpty())) {
                Thread.sleep(100);
                position = read("/api/world/resources/" + StubWorld.ORBITER).path("state").path("position");
                intents = entries(read("/api/world/log?area=" + AREA + "&kind=intent"));
            }
            assertThat(position).as("the orbiter's position after 5 s").isNotEqualTo(start);
            assertThat(intents).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.path("resource").asText()).isEqualTo(StubWorld.ORBITER);
                assertThat(entry.path("by").toString()).isEqualTo("{\"kind\":\"service\",\"principal\":\"orbital\"}");
                assertThat(entry.path("reported").path("draw").path("dist").asText()).isEqualTo("categorical");
            });
        }
    }

    private static List<JsonNode> entries(JsonNode log) {
        List<JsonNode> entries = new ArrayList<>();
        log.path("entries").forEach(entries::add);
        return entries;
    }

    private static JsonNode read(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(worldUrl + path))
                .header(World.API_KEY_HEADER, Fixtures.KEY).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void awaitReady(String readiness) throws Exception {
        long deadline = System.nanoTime() + WORLD_STARTUP.toNanos();
        while (System.nanoTime() < deadline) {
            if (!world.isAlive()) {
                throw new AssertionError("world exited with " + world.exitValue() + ":\n" + tail());
            }
            try {
                HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(readiness)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(250);
        }
        throw new AssertionError("world not ready after " + WORLD_STARTUP + ":\n" + tail());
    }

    private static String tail() throws IOException {
        List<String> lines = Files.readAllLines(worldLog);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
